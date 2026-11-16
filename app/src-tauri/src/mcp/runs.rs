//! Results by reference, so that an agent chains steps without copying bodies through its context: every
//! run that produces a message gets a `runId`; `messageFrom` / `messagesFrom` take that message (body,
//! headers, properties) as the next step's input, as the message flows to the next step in an iFlow;
//! `read_file` and `save_file` take a run's body. Large bodies come back shortened in results.
//!
//! The ids are opaque handles of this server process (MCP 2026-07-28 on stateful tools: validate on every
//! call, an unknown one is a tool error). The process belongs to one client; the store keeps the newest
//! runs within a size limit and forgets everything when the process ends. The prefix differs per process,
//! so an id from an earlier process is unknown rather than another run.

use serde_json::{json, Value};
use std::collections::VecDeque;
use std::sync::Mutex;

/// Runs kept, and the bodies' total size; the oldest go first.
const MAX_RUNS: usize = 50;
const MAX_BYTES: usize = 64 * 1024 * 1024;
/// Bodies longer than this come back shortened; the run keeps the whole body.
pub const PREVIEW_CHARS: usize = 20_000;
/// A Splitter's messages are shown shorter: there may be many.
pub const PART_PREVIEW_CHARS: usize = 2_000;
/// A body the step did not change is left out of the result from this length on.
const UNCHANGED_MIN_CHARS: usize = 200;

#[derive(Clone, Debug, PartialEq)]
pub struct Message {
    pub body: String,
    /// Rows as the engines take and give them: [{name, value, type}].
    pub headers: Value,
    pub properties: Value,
}

impl Message {
    pub fn from_output(o: &Value) -> Option<Message> {
        Some(Message {
            body: o.get("body")?.as_str()?.to_string(),
            headers: o.get("headers").filter(|v| v.is_array()).cloned().unwrap_or(json!([])),
            properties: o.get("properties").filter(|v| v.is_array()).cloned().unwrap_or(json!([])),
        })
    }

    pub fn with_body(&self, body: &str) -> Message {
        Message { body: body.to_string(), ..self.clone() }
    }

    fn size(&self) -> usize {
        self.body.len() + self.headers.to_string().len() + self.properties.to_string().len()
    }
}

/// What a run produced: one message, or several by name (a Splitter's 1, 2, …; `runtime: both`'s runtimes).
#[derive(Default)]
pub struct Produced {
    pub main: Option<Message>,
    pub parts: Vec<(String, Message)>,
}

struct Run {
    id: String,
    produced: Produced,
    size: usize,
}

pub struct Runs {
    prefix: String,
    inner: Mutex<(u64, VecDeque<Run>, usize)>,
}

impl Default for Runs {
    fn default() -> Self {
        let seed = std::time::SystemTime::now().duration_since(std::time::UNIX_EPOCH).map(|d| d.subsec_nanos()).unwrap_or(0)
            ^ std::process::id().rotate_left(16);
        Runs { prefix: format!("run-{:04x}", seed & 0xffff), inner: Mutex::default() }
    }
}

impl Runs {
    /// Keeps what a run produced and returns its id, or None when it produced no message.
    pub fn keep(&self, produced: Produced) -> Option<String> {
        if produced.main.is_none() && produced.parts.is_empty() {
            return None;
        }
        let size = produced.main.iter().chain(produced.parts.iter().map(|(_, m)| m)).map(Message::size).sum();
        let mut g = self.inner.lock().unwrap();
        g.0 += 1;
        let id = format!("{}-{}", self.prefix, g.0);
        g.1.push_back(Run { id: id.clone(), produced, size });
        g.2 += size;
        while g.1.len() > MAX_RUNS || (g.2 > MAX_BYTES && g.1.len() > 1) {
            let old = g.1.pop_front().unwrap();
            g.2 -= old.size;
        }
        Some(id)
    }

    /// One message: `id` (a run with one message) or `id/part`.
    pub fn message(&self, reference: &str) -> Result<Message, String> {
        let (id, part) = match reference.split_once('/') {
            Some((i, p)) => (i, Some(p)),
            None => (reference, None),
        };
        let g = self.inner.lock().unwrap();
        let run = g.1.iter().find(|r| r.id == id).ok_or_else(|| self.unknown(reference))?;
        let p = &run.produced;
        match part {
            Some(name) => p.parts.iter().find(|(n, _)| n == name).map(|(_, m)| m.clone())
                .ok_or_else(|| format!("{id} has no message '{name}'; it has {}.", names(id, p))),
            None => p.main.clone().ok_or_else(|| format!("{id} produced several messages: {}. Give one, or \
                messagesFrom: \"{id}\" to gather them all.", names(id, p))),
        }
    }

    /// All messages of a run (a Splitter's parts in order), or the one message of `id/part`.
    pub fn messages(&self, reference: &str) -> Result<Vec<Message>, String> {
        if reference.contains('/') {
            return Ok(vec![self.message(reference)?]);
        }
        let g = self.inner.lock().unwrap();
        let run = g.1.iter().find(|r| r.id == reference).ok_or_else(|| self.unknown(reference))?;
        let p = &run.produced;
        Ok(if p.parts.is_empty() { p.main.iter().cloned().collect() } else { p.parts.iter().map(|(_, m)| m.clone()).collect() })
    }

    fn unknown(&self, reference: &str) -> String {
        format!("Unknown run '{reference}': it is older than the last {MAX_RUNS} runs, or from an earlier server process. \
            Run the step again, or give the body itself.")
    }
}

fn names(id: &str, p: &Produced) -> String {
    p.parts.iter().map(|(n, _)| format!("{id}/{n}")).collect::<Vec<_>>().join(", ")
}

/// The message a run passes on, as the step does in an iFlow. `input` is the message the step got.
pub fn produced(tool: &str, input: &Message, result: &Value) -> Produced {
    let mut p = Produced::default();
    if let Some(runs) = result.get("runs").and_then(Value::as_array) {
        // runtime: both. Each runtime's output by name; the first is the run's message.
        for r in runs.iter().filter(|r| r["result"]["ok"] != false) {
            if let (Some(rt), Some(m)) = (r["runtime"].as_str(), Message::from_output(&r["result"]["output"])) {
                p.main.get_or_insert_with(|| m.clone());
                p.parts.push((rt.to_string(), m));
            }
        }
        if p.parts.len() > 1 {
            p.main = None; // two results: the agent names the runtime
        }
        return p;
    }
    match tool {
        "run_xslt" | "run_groovy" => p.main = Message::from_output(&result["output"]),
        // The XML Validator and the Router pass the message on unchanged.
        "validate_xsd" => p.main = Some(input.clone()),
        "run_flow_step" => match result["step"]["type"].as_str().unwrap_or_default() {
            "contentModifier" => p.main = Message::from_output(&result["output"]),
            "router" => p.main = Some(input.clone()),
            "splitter" => {
                for (i, m) in result["messages"].as_array().into_iter().flatten().enumerate() {
                    if let Some(b) = m["body"].as_str() {
                        p.parts.push(((i + 1).to_string(), input.with_body(b)));
                    }
                }
            }
            _ => p.main = result["body"].as_str().map(|b| input.with_body(b)),
        },
        _ => {}
    }
    p
}

/// Shortens a result's bodies, saying where the whole body is: a body that is the input body unchanged is left
/// out, a long one is cut, and a Splitter's messages are cut shorter (there may be many).
pub fn shorten(result: &mut Value, id: &str, input_body: &str) {
    fn cut(holder: &mut Value, reference: &str, limit: usize, input_body: &str) {
        let Some(body) = holder.get("body").and_then(Value::as_str) else { return };
        let chars = body.chars().count();
        if body == input_body && chars > UNCHANGED_MIN_CHARS {
            holder.as_object_mut().unwrap().remove("body");
            holder["bodyUnchanged"] = json!(format!("The body is the input body, unchanged ({chars} characters); read_file with runId \"{reference}\" reads it, messageFrom \"{reference}\" passes it on."));
            return;
        }
        if chars <= limit {
            return;
        }
        let short: String = body.chars().take(limit).collect();
        holder["body"] = json!(short);
        holder["bodyShortened"] = json!(format!("The body has {chars} characters; the first {limit} are shown. read_file with runId \"{reference}\" reads it all; messageFrom \"{reference}\" passes it to the next step."));
    }
    if let Some(runs) = result.get_mut("runs").and_then(Value::as_array_mut) {
        for r in runs {
            let rt = r["runtime"].as_str().unwrap_or_default().to_string();
            cut(&mut r["result"]["output"], &format!("{id}/{rt}"), PREVIEW_CHARS, input_body);
        }
        return;
    }
    if result.get("output").is_some_and(Value::is_object) {
        cut(&mut result["output"], id, PREVIEW_CHARS, input_body);
    }
    cut(result, id, PREVIEW_CHARS, input_body);
    if let Some(ms) = result.get_mut("messages").and_then(Value::as_array_mut) {
        for (i, m) in ms.iter_mut().enumerate() {
            cut(m, &format!("{id}/{}", i + 1), PART_PREVIEW_CHARS, "");
        }
    }
}

/// Rows of `over` replace the rows of `base` with the same name; the others are added.
pub fn merge_rows(base: &Value, over: &Value) -> Value {
    let mut rows: Vec<Value> = base.as_array().cloned().unwrap_or_default();
    for o in over.as_array().into_iter().flatten() {
        match rows.iter_mut().find(|r| r["name"] == o["name"]) {
            Some(r) => *r = o.clone(),
            None => rows.push(o.clone()),
        }
    }
    Value::Array(rows)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn msg(body: &str) -> Message {
        Message { body: body.into(), headers: json!([{ "name": "h", "value": "1", "type": "String" }]), properties: json!([]) }
    }

    #[test]
    fn ids_are_opaque_bounded_and_per_process() {
        let runs = Runs::default();
        let first = runs.keep(Produced { main: Some(msg("a")), parts: vec![] }).unwrap();
        assert!(first.starts_with("run-") && first.ends_with("-1"), "{first}");
        assert_eq!(runs.message(&first).unwrap().body, "a");
        for i in 0..MAX_RUNS {
            runs.keep(Produced { main: Some(msg(&i.to_string())), parts: vec![] });
        }
        assert!(runs.message(&first).unwrap_err().contains("Unknown run"), "the oldest is gone");
        assert!(runs.message("run-zzzz-1").unwrap_err().contains("earlier server process"));
        assert!(runs.keep(Produced::default()).is_none(), "no message, no id");
    }

    #[test]
    fn parts_are_named_and_gathered_in_order() {
        let runs = Runs::default();
        let id = runs.keep(Produced { main: None, parts: vec![("1".into(), msg("x")), ("2".into(), msg("y"))] }).unwrap();
        assert!(runs.message(&id).unwrap_err().contains(&format!("{id}/1, {id}/2")));
        assert_eq!(runs.message(&format!("{id}/2")).unwrap().body, "y");
        assert!(runs.message(&format!("{id}/3")).unwrap_err().contains("no message '3'"));
        let all: Vec<String> = runs.messages(&id).unwrap().into_iter().map(|m| m.body).collect();
        assert_eq!(all, ["x", "y"]);
    }

    #[test]
    fn long_bodies_are_shortened_with_a_pointer() {
        let mut r = json!({ "output": { "body": "é".repeat(PREVIEW_CHARS + 5) }, "messages": [{ "body": "short" }] });
        shorten(&mut r, "run-1-1", "");
        assert_eq!(r["output"]["body"].as_str().unwrap().chars().count(), PREVIEW_CHARS);
        assert!(r["output"]["bodyShortened"].as_str().unwrap().contains("run-1-1"));
        assert!(r["messages"][0].get("bodyShortened").is_none());
    }

    #[test]
    fn an_unchanged_body_is_left_out() {
        let body = "<a>".to_string() + &"x".repeat(500) + "</a>";
        let mut r = json!({ "output": { "body": body, "headers": [] } });
        shorten(&mut r, "run-1-2", &body);
        assert!(r["output"].get("body").is_none() && r["output"]["bodyUnchanged"].as_str().unwrap().contains("run-1-2"));
        let mut small = json!({ "output": { "body": "<a/>" } });
        shorten(&mut small, "run-1-3", "<a/>");
        assert_eq!(small["output"]["body"], "<a/>", "a short body stays");
    }

    #[test]
    fn rows_given_with_the_call_win() {
        let base = json!([{ "name": "a", "value": "1", "type": "String" }, { "name": "b", "value": "2", "type": "String" }]);
        let over = json!([{ "name": "b", "value": "3", "type": "Integer" }, { "name": "c", "value": "4", "type": "String" }]);
        let m = merge_rows(&base, &over);
        assert_eq!(m.as_array().unwrap().len(), 3);
        assert_eq!(m[1], json!({ "name": "b", "value": "3", "type": "Integer" }));
    }
}
