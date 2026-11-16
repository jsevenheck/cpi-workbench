//! The receiver for CPI Helper's links (docs/plan.md W2b), off unless the user turns it on.
//!
//! CPI Helper's GroovyDebugX and xsltDebugX plugins open `http://127.0.0.1:<port>/#share/<data>` in the
//! browser. The part after `#` never reaches a server, so this serves one small page whose script reads it
//! and POSTs it back to `/message` on the same origin. The receiver hands the text to the UI, which asks the
//! user before it imports anything (`app/src/lib/receiver.svelte.ts`). It listens on 127.0.0.1 only, answers
//! only requests that name this machine and port (DNS rebinding), and takes a POST only from its own page.
//! Any web page can still open such a link, so every request is bounded: its size, its time and how many
//! are handled at once.

use std::io::{self, BufRead, BufReader, Read, Write};
use std::net::{TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

/// The event the UI listens for; its payload is the data after `#share/`.
pub const EVENT: &str = "cpi-helper-link";

/// Above Chromium's URL limit of 2 MB, so every link a browser can open fits.
const MAX_BODY: usize = 8 * 1024 * 1024;
/// Request line and headers together, counted while they are read.
const MAX_HEAD: u64 = 16 * 1024;
/// Connections handled at once; more are closed. A click needs two requests, one after the other.
const MAX_CONNECTIONS: usize = 4;
/// The whole request must arrive within this, however slowly it trickles in (shorter in the tests).
const DEADLINE: Duration = Duration::from_secs(if cfg!(test) { 2 } else { 15 });

pub type Deliver = Arc<dyn Fn(String) + Send + Sync + 'static>;

#[derive(Default)]
pub struct Receiver {
    running: Mutex<Option<Running>>,
}

struct Running {
    port: u16,
    stop: Arc<AtomicBool>,
    thread: JoinHandle<()>,
}

impl Receiver {
    /// Stops the receiver, and with a port starts it there. The same port again keeps it running. Blocks
    /// until a stopped receiver has freed its port; requests still under way then deliver nothing.
    pub fn set(&self, port: Option<u16>, deliver: Deliver) -> Result<(), String> {
        let mut running = self.running.lock().unwrap();
        if port.is_some() && running.as_ref().map(|r| r.port) == port {
            return Ok(());
        }
        if let Some(r) = running.take() {
            r.stop.store(true, Ordering::SeqCst);
            let _ = TcpStream::connect(("127.0.0.1", r.port)); // wakes the accept
            let _ = r.thread.join(); // the port is free again when this returns
        }
        let Some(port) = port else { return Ok(()) };
        let listener = TcpListener::bind(("127.0.0.1", port))
            .map_err(|e| format!("Port {port} cannot be used ({e}). Choose another port."))?;
        let stop = Arc::new(AtomicBool::new(false));
        let deliver: Deliver = {
            let stop = stop.clone();
            Arc::new(move |data| {
                if !stop.load(Ordering::SeqCst) {
                    deliver(data)
                }
            })
        };
        let flag = stop.clone();
        let busy = Arc::new(AtomicUsize::new(0));
        let thread = std::thread::spawn(move || {
            for stream in listener.incoming() {
                if flag.load(Ordering::SeqCst) {
                    break;
                }
                let Ok(stream) = stream else { continue };
                // Each connection has its own thread, so one that sends nothing holds up no other.
                if busy.fetch_add(1, Ordering::SeqCst) >= MAX_CONNECTIONS {
                    busy.fetch_sub(1, Ordering::SeqCst);
                    continue; // closed
                }
                let (busy, deliver) = (busy.clone(), deliver.clone());
                std::thread::spawn(move || {
                    handle(stream, port, &deliver);
                    busy.fetch_sub(1, Ordering::SeqCst);
                });
            }
        });
        *running = Some(Running { port, stop, thread });
        Ok(())
    }
}

struct Request {
    method: String,
    path: String,
    host: String,
    origin: Option<String>,
    body: Vec<u8>,
}

/// One request per connection.
fn handle(mut stream: TcpStream, port: u16, deliver: &Deliver) {
    let _ = stream.set_write_timeout(Some(DEADLINE));
    let (status, content_type, body) = match read_request(&stream, Instant::now() + DEADLINE) {
        Ok(req) => answer(&req, port, deliver),
        Err((status, text)) => (status, "text/plain; charset=utf-8", text),
    };
    let reason = match status {
        200 => "OK",
        204 => "No Content",
        400 => "Bad Request",
        403 => "Forbidden",
        413 => "Payload Too Large",
        _ => "Not Found",
    };
    let head = format!(
        "HTTP/1.1 {status} {reason}\r\nContent-Type: {content_type}\r\nContent-Length: {}\r\nCache-Control: no-store\r\n\
         X-Content-Type-Options: nosniff\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: {CSP}\r\nConnection: close\r\n\r\n",
        body.len()
    );
    let _ = stream.write_all(head.as_bytes()).and_then(|_| stream.write_all(body.as_bytes()));
}

/// Reads until a fixed point in time, not only with a timeout per read.
struct Deadline<'a> {
    stream: &'a TcpStream,
    until: Instant,
}

impl Read for Deadline<'_> {
    fn read(&mut self, buf: &mut [u8]) -> io::Result<usize> {
        let left = self.until.saturating_duration_since(Instant::now());
        if left.is_zero() {
            return Err(io::ErrorKind::TimedOut.into());
        }
        self.stream.set_read_timeout(Some(left))?;
        let mut stream = self.stream;
        stream.read(buf)
    }
}

fn read_request(stream: &TcpStream, until: Instant) -> Result<Request, (u16, String)> {
    let bad = |text: &str| (400, text.to_string());
    let mut reader = BufReader::new(Deadline { stream, until });
    // The request line and the headers, read through a limit, so that no line grows past it.
    let mut lines = Vec::new();
    {
        let mut head = (&mut reader).take(MAX_HEAD);
        let mut line = String::new();
        loop {
            line.clear();
            let n = head.read_line(&mut line).map_err(|_| bad("Could not read the request."))?;
            if n == 0 || !line.ends_with('\n') {
                return Err(bad("The request's headers are too long or incomplete."));
            }
            let text = line.trim_end();
            if text.is_empty() {
                break;
            }
            lines.push(text.to_string());
        }
    }
    let mut parts = lines.first().map(String::as_str).unwrap_or_default().split_whitespace();
    let method = parts.next().unwrap_or_default().to_string();
    let path = parts.next().unwrap_or_default().split('?').next().unwrap_or_default().to_string();
    let (mut host, mut origin, mut length) = (String::new(), None, 0usize);
    for text in lines.iter().skip(1) {
        if let Some((name, value)) = text.split_once(':') {
            let value = value.trim().to_string();
            match name.trim().to_ascii_lowercase().as_str() {
                "host" => host = value,
                "origin" => origin = Some(value),
                "content-length" => length = value.parse().map_err(|_| bad("Content-Length is not a number."))?,
                _ => {}
            }
        }
    }
    if length > MAX_BODY {
        return Err((413, "The message is too large.".into()));
    }
    let mut body = vec![0; length];
    reader.read_exact(&mut body).map_err(|_| bad("The message ended early."))?;
    Ok(Request { method, path, host, origin, body })
}

/// Status, content type and body of the answer. Calls `deliver` only for a link from the receiver's own page.
fn answer(req: &Request, port: u16, deliver: &Deliver) -> (u16, &'static str, String) {
    let text = "text/plain; charset=utf-8";
    let local = [format!("127.0.0.1:{port}"), format!("localhost:{port}")];
    if !local.iter().any(|h| h.eq_ignore_ascii_case(&req.host)) {
        return (403, text, "This address is for CPI Workbench on this computer only.".into());
    }
    match (req.method.as_str(), req.path.as_str()) {
        ("GET", "/") => (200, "text/html; charset=utf-8", PAGE.into()),
        ("POST", "/message") => {
            let own = req.origin.as_deref().is_some_and(|o| local.iter().any(|h| o.eq_ignore_ascii_case(&format!("http://{h}"))));
            if !own {
                return (403, text, "Only CPI Workbench's own page may send a message here.".into());
            }
            match std::str::from_utf8(&req.body) {
                Ok(data) if !data.is_empty() && data.bytes().all(|b| b.is_ascii_alphanumeric() || b == b'-' || b == b'_') => {
                    deliver(data.to_string());
                    (204, text, String::new())
                }
                _ => (400, text, "This is not a link from CPI Helper: no data after #share/.".into()),
            }
        }
        _ => (404, text, "Not found.".into()),
    }
}

const CSP: &str = "default-src 'none'; script-src 'unsafe-inline'; style-src 'unsafe-inline'; connect-src 'self'; frame-ancestors 'none'";

/// The page the plugins open: sends the data after `#share/` to the app, then takes it out of the address.
const PAGE: &str = r#"<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>CPI Workbench</title>
<style>
  body { font: 14px/1.5 system-ui, sans-serif; margin: 48px auto; max-width: 560px; padding: 0 16px; color: #222; background: #fff; }
  @media (prefers-color-scheme: dark) { body { color: #ddd; background: #1e1e1e; } }
</style></head>
<body><h1 style="font-size:18px">CPI Workbench</h1><p id="m">Sending the message to CPI Workbench…</p>
<script>
  const m = document.getElementById('m');
  const hash = location.hash;
  if (!hash.startsWith('#share/')) {
    m.textContent = 'This address holds no message. CPI Helper adds one when you send a step from its trace.';
  } else {
    fetch('/message', { method: 'POST', body: hash.slice('#share/'.length) }).then(async (r) => {
      if (!r.ok) throw new Error(await r.text());
      history.replaceState(null, '', location.pathname);
      m.textContent = 'Sent. Confirm the import in CPI Workbench; you can close this tab.';
    }).catch((e) => {
      m.textContent = 'CPI Workbench did not take the message: ' + (e.message || e) +
        ' Check that the app is running with "Receive links from CPI Helper" on, then reload this tab.';
    });
  }
</script></body></html>
"#;

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::mpsc;

    fn req(method: &str, path: &str, host: &str, origin: Option<&str>, body: &str) -> Request {
        Request { method: method.into(), path: path.into(), host: host.into(), origin: origin.map(Into::into), body: body.into() }
    }

    fn collect() -> (Deliver, mpsc::Receiver<String>) {
        let (tx, rx) = mpsc::channel();
        (Arc::new(move |s| tx.send(s).unwrap()), rx)
    }

    fn none() -> Deliver {
        Arc::new(|_| {})
    }

    #[test]
    fn answers_only_its_own_page() {
        let (deliver, got) = collect();
        let own = Some("http://127.0.0.1:7778");
        assert_eq!(answer(&req("GET", "/", "127.0.0.1:7778", None, ""), 7778, &deliver).0, 200);
        assert_eq!(answer(&req("GET", "/", "LOCALHOST:7778", None, ""), 7778, &deliver).0, 200);
        // DNS rebinding: a foreign name that resolves to 127.0.0.1.
        assert_eq!(answer(&req("GET", "/", "evil.example:7778", None, ""), 7778, &deliver).0, 403);
        assert_eq!(answer(&req("POST", "/message", "127.0.0.1:7779", own, "abc"), 7778, &deliver).0, 403);
        // Another page, or none, may not send.
        for origin in [None, Some("https://tenant.example"), Some("http://127.0.0.1:7777"), Some("null")] {
            assert_eq!(answer(&req("POST", "/message", "127.0.0.1:7778", origin, "abc"), 7778, &deliver).0, 403, "{origin:?}");
        }
        for body in ["", "a b", "<script>", "abc="] {
            assert_eq!(answer(&req("POST", "/message", "127.0.0.1:7778", own, body), 7778, &deliver).0, 400, "{body}");
        }
        assert_eq!(answer(&req("GET", "/other", "127.0.0.1:7778", None, ""), 7778, &deliver).0, 404);
        assert!(got.try_recv().is_err(), "nothing delivered so far");
        assert_eq!(answer(&req("POST", "/message", "127.0.0.1:7778", own, "q1Z-_9"), 7778, &deliver).0, 204);
        assert_eq!(got.try_recv().unwrap(), "q1Z-_9");
    }

    fn free_port() -> u16 {
        TcpListener::bind(("127.0.0.1", 0)).unwrap().local_addr().unwrap().port()
    }

    fn send(port: u16, request: &[u8]) -> String {
        let mut s = TcpStream::connect(("127.0.0.1", port)).unwrap();
        s.set_read_timeout(Some(Duration::from_secs(5))).unwrap();
        let _ = s.write_all(request); // the receiver may answer and close before all of it is sent
        let mut out = Vec::new();
        let _ = s.read_to_end(&mut out);
        String::from_utf8_lossy(&out).into_owned()
    }

    fn get(port: u16) -> String {
        send(port, format!("GET / HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\n\r\n").as_bytes())
    }

    #[test]
    fn serves_the_page_takes_a_link_and_stops() {
        let port = free_port();
        let receiver = Receiver::default();
        let (deliver, got) = collect();
        receiver.set(Some(port), deliver).unwrap();
        let page = send(port, format!("GET /?x HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\n\r\n").as_bytes());
        assert!(page.starts_with("HTTP/1.1 200") && page.contains("#share/") && page.contains("frame-ancestors 'none'"), "{page}");
        let post = format!("POST /message HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nOrigin: http://127.0.0.1:{port}\r\nContent-Length: 4\r\n\r\nab-_");
        assert!(send(port, post.as_bytes()).starts_with("HTTP/1.1 204"));
        assert_eq!(got.recv_timeout(Duration::from_secs(5)).unwrap(), "ab-_");
        let big = format!("POST /message HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nContent-Length: {}\r\n\r\n", MAX_BODY + 1);
        assert!(send(port, big.as_bytes()).starts_with("HTTP/1.1 413"));

        // Off, then on again at the same port: the old listener is gone first.
        receiver.set(None, none()).unwrap();
        assert!(TcpStream::connect(("127.0.0.1", port)).is_err());
        receiver.set(Some(port), none()).unwrap();
        assert!(get(port).starts_with("HTTP/1.1 200"));

        // A port in use is an error that names it.
        let err = Receiver::default().set(Some(port), none()).unwrap_err();
        assert!(err.contains(&port.to_string()), "{err}");
        receiver.set(None, none()).unwrap();
    }

    #[test]
    fn a_long_request_line_or_header_is_refused_while_it_is_read() {
        let port = free_port();
        let receiver = Receiver::default();
        receiver.set(Some(port), none()).unwrap();
        let long_line = format!("GET /{} HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\n\r\n", "a".repeat(128 * 1024));
        assert!(send(port, long_line.as_bytes()).starts_with("HTTP/1.1 400"));
        let long_header = format!("GET / HTTP/1.1\r\nHost: 127.0.0.1:{port}\r\nX: {}\r\n\r\n", "a".repeat(MAX_HEAD as usize));
        assert!(send(port, long_header.as_bytes()).starts_with("HTTP/1.1 400"));
        receiver.set(None, none()).unwrap();
    }

    #[test]
    fn idle_connections_do_not_hold_up_a_request_or_the_switch_off() {
        let port = free_port();
        let receiver = Receiver::default();
        receiver.set(Some(port), none()).unwrap();
        // Connections that send nothing, more than are handled at once; then all but one are closed.
        let mut idle: Vec<_> = (0..MAX_CONNECTIONS + 2).map(|_| TcpStream::connect(("127.0.0.1", port)).unwrap()).collect();
        std::thread::sleep(Duration::from_millis(100));
        let held = idle.remove(0);
        drop(idle);
        std::thread::sleep(Duration::from_millis(100));
        let started = Instant::now();
        assert!(get(port).starts_with("HTTP/1.1 200"), "a request after an idle connection is answered");
        receiver.set(None, none()).unwrap();
        assert!(started.elapsed() < DEADLINE / 2, "neither waits for the idle connection");
        drop(held);
    }

    #[test]
    fn a_request_that_trickles_in_ends_at_the_deadline() {
        let port = free_port();
        let receiver = Receiver::default();
        receiver.set(Some(port), none()).unwrap();
        let mut s = TcpStream::connect(("127.0.0.1", port)).unwrap();
        s.set_read_timeout(Some(Duration::from_millis(50))).unwrap();
        let started = Instant::now();
        s.write_all(b"GET / HTTP/1.1\r\n").unwrap();
        // A header byte every 100 ms: each read is quick, the request never ends. The receiver answers and
        // closes at the deadline; a write after that can reset the connection, which on Windows drops the
        // answer, so the test looks at when the connection ends, not at what it said.
        let mut out = Vec::new();
        let mut ended = false;
        while started.elapsed() < DEADLINE * 4 {
            let _ = s.write_all(b"X");
            match s.read_to_end(&mut out) {
                Err(e) if matches!(e.kind(), io::ErrorKind::WouldBlock | io::ErrorKind::TimedOut) => {}
                _ => {
                    ended = true;
                    break;
                }
            }
            std::thread::sleep(Duration::from_millis(100));
        }
        let elapsed = started.elapsed();
        assert!(ended && elapsed >= DEADLINE && elapsed < DEADLINE * 3, "ended: {ended} after {elapsed:?}");
        let answer = String::from_utf8_lossy(&out);
        assert!(answer.is_empty() || answer.starts_with("HTTP/1.1 400"), "{answer}");
        receiver.set(None, none()).unwrap();
    }
}
