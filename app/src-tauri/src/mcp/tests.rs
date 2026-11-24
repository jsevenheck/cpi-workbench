use super::*;
use std::io::Cursor;

/// Sends the lines to a server and returns its answers, by id (`null` for errors without an id).
fn session(server: Server, lines: &[Value]) -> Vec<Value> {
    let input: String = lines.iter().map(|l| format!("{l}\n")).collect();
    let out = Arc::new(Mutex::new(Vec::<u8>::new()));
    for w in serve(Arc::new(server), Cursor::new(input), out.clone()) {
        w.join().unwrap();
    }
    let text = String::from_utf8(out.lock().unwrap().clone()).unwrap();
    let mut msgs: Vec<Value> = text.lines().map(|l| serde_json::from_str(l).unwrap()).collect();
    msgs.sort_by_key(|m| m["id"].to_string());
    msgs
}

#[test]
fn a_slow_writer_does_not_block_cancellation_or_admission() {
    let out = Arc::new(Mutex::new(Vec::new()));
    let held = out.lock().unwrap();
    let blocked = out.clone();
    let (done, ready) = std::sync::mpsc::channel();
    let reader = std::thread::spawn(move || {
        let lines = (1..=12).map(|id| json!({ "jsonrpc": "2.0", "id": id, "method": "ping" }))
            .chain((1..=8).map(|id| json!({ "jsonrpc": "2.0", "method": "notifications/cancelled", "params": {"requestId": id} })))
            .map(|v| format!("{v}\n")).collect::<String>();
        done.send(serve(Arc::new(bare()), Cursor::new(lines), blocked)).unwrap();
    });
    let handles = ready.recv_timeout(Duration::from_secs(3));
    drop(held);
    for handle in handles.expect("input reader blocked on the writer") { handle.join().unwrap(); }
    reader.join().unwrap();
    let msgs = out.lock().unwrap().split(|b| *b == b'\n').filter(|l| !l.is_empty())
        .map(|l| serde_json::from_slice(l).unwrap()).collect::<Vec<Value>>();
    // The writer can free one slot before blocking. Any of the later requests (not necessarily id 9)
    // can claim it, depending on when the writer takes its first reply.
    let mut overflow_admitted = 0;
    for id in 9..=12 {
        let response = by_id(&msgs, id);
        if response.get("result").is_some() { overflow_admitted += 1; }
        else { assert_eq!(response["error"]["code"], SERVER_BUSY, "{response}"); }
    }
    assert!(overflow_admitted <= 1, "at most one queued reply frees a slot before stdout blocks");
    let survived = msgs.iter().filter(|m| m.get("result").is_some() && m["id"].as_i64().is_some_and(|id| id <= 8)).count();
    assert!(survived <= 1, "only a reply already writing can survive cancellation");
}

#[test]
fn oversized_tool_previews_do_not_duplicate_the_payload() {
    let r = tool_result("run_xslt", json!({ "output": "a".repeat(MAX_TOOL_JSON), "runId": "run-test" }));
    assert_eq!(r["isError"], true);
    let text = r["content"][0]["text"].as_str().unwrap();
    assert!(text.starts_with("UnsupportedInSimulator: run_xslt completed") && text.contains("run-test"), "{text}");
    assert!(r.get("structuredContent").is_none());
    let r = tool_result("import_iflow", json!({ "kind": "iflow", "files": "a".repeat(MAX_TOOL_JSON) }));
    assert!(r["content"][0]["text"].as_str().unwrap().contains("files were written"), "{r}");
}

#[test]
fn preview_ranks_lists_by_encoded_bytes_without_materializing_them() {
    let escaped = json!(["\u{0000}".repeat(60)]);
    let plain = json!(["x".repeat(100), "ä".repeat(100)]);
    assert_eq!(compact_size(&escaped), escaped.to_string().len());
    assert_eq!(compact_size(&plain), plain.to_string().len());
    assert!(compact_size(&escaped) > compact_size(&plain));
}

/// An import that wrote its files reports success; a step list over the preview limit is cut, not an error.
#[test]
fn an_oversized_list_is_shortened_not_an_error() {
    let steps: Vec<Value> = (0..20_000).map(|i| json!({ "iflow": "Big", "id": format!("CallActivity_{i}"), "name": "x".repeat(60) })).collect();
    let r = tool_result("import_iflow", json!({ "kind": "iflow", "imported": ["Big"], "files": ["iflows/Big/a.iflw"], "steps": steps }));
    assert_eq!(r["isError"], false, "{}", r["content"][0]["text"]);
    let v = &r["structuredContent"];
    let shown = v["steps"].as_array().unwrap().len();
    assert!(shown > 0 && shown < 20_000, "{shown}");
    assert_eq!(v["files"], json!(["iflows/Big/a.iflw"]), "the small lists stay whole");
    let note = v["resultShortened"].as_str().unwrap();
    assert!(note.starts_with("UnsupportedInSimulator") && note.contains(&format!("steps: {shown} of 20000")), "{note}");
    assert!(r["content"][0]["text"].as_str().unwrap().len() <= MAX_TOOL_JSON);
}

/// A run too large for the run cache still returns its result, shortened, without a runId.
#[test]
fn an_oversized_run_returns_its_result_without_a_run_id() {
    let server = bare();
    let input = runs::Message::text("", json!([]), json!([]));
    let mut result = json!({ "ok": true, "output": { "body": "x".repeat(64 * 1024 * 1024 + 1), "headers": [] } });
    tools::keep_run(&server, "run_xslt", &input, &mut result);
    assert!(result.get("runId").is_none(), "{}", result["runNotice"]);
    assert!(result["runNotice"].as_str().unwrap().starts_with("UnsupportedInSimulator") && result["runNotice"].as_str().unwrap().contains("No runId"));
    assert!(result["output"]["bodyShortened"].as_str().unwrap().contains("No runId was stored"));
    assert_eq!(tool_result("run_xslt", result)["isError"], false);
}

/// The id is free again before its answer is written: a client that reuses it right after reading the answer
/// gets a second answer, not "already in use".
#[test]
fn an_answered_id_can_be_reused_at_once() {
    struct Lines(std::sync::mpsc::Receiver<Vec<u8>>, Vec<u8>);
    impl std::io::Read for Lines {
        fn read(&mut self, buf: &mut [u8]) -> std::io::Result<usize> {
            if self.1.is_empty() {
                match self.0.recv() { Ok(l) => self.1 = l, Err(_) => return Ok(0) }
            }
            let n = buf.len().min(self.1.len());
            buf[..n].copy_from_slice(&self.1[..n]);
            self.1.drain(..n);
            Ok(n)
        }
    }
    /// Hands each answer to the client as it is flushed, then holds the writer a moment longer.
    struct Client(std::sync::mpsc::Sender<Value>, Vec<u8>);
    impl Write for Client {
        fn write(&mut self, data: &[u8]) -> std::io::Result<usize> { self.1.extend_from_slice(data); Ok(data.len()) }
        fn flush(&mut self) -> std::io::Result<()> {
            for l in self.1.split(|b| *b == b'\n').filter(|l| !l.is_empty()) {
                let _ = self.0.send(serde_json::from_slice(l).unwrap());
            }
            self.1.clear();
            std::thread::sleep(Duration::from_millis(200));
            Ok(())
        }
    }
    let (lines, input) = std::sync::mpsc::channel();
    let (answers, answered) = std::sync::mpsc::channel();
    let out = Arc::new(Mutex::new(Client(answers, Vec::new())));
    let reader = std::thread::spawn(move || serve(Arc::new(bare()), std::io::BufReader::new(Lines(input, Vec::new())), out));
    let ping = format!("{}\n", json!({ "jsonrpc": "2.0", "id": 1, "method": "ping" })).into_bytes();
    lines.send(ping.clone()).unwrap();
    assert_eq!(answered.recv_timeout(Duration::from_secs(3)).unwrap()["result"], json!({}));
    lines.send(ping).unwrap();
    let second = answered.recv_timeout(Duration::from_secs(3)).unwrap();
    assert_eq!(second["result"], json!({}), "{second}");
    drop(lines);
    for w in reader.join().unwrap() { w.join().unwrap(); }
}

fn bare() -> Server {
    Server::new(Arc::new(Engines::default()), None)
}

fn modern(id: u64, method: &str, mut params: Value) -> Value {
    params["_meta"] = json!({ META_VERSION: MODERN, "io.modelcontextprotocol/clientCapabilities": {},
        "io.modelcontextprotocol/clientInfo": { "name": "test", "version": "1" } });
    json!({ "jsonrpc": "2.0", "id": id, "method": method, "params": params })
}

fn by_id(msgs: &[Value], id: u64) -> &Value {
    msgs.iter().find(|m| m["id"] == id).unwrap_or_else(|| panic!("no answer {id}: {msgs:?}"))
}

#[test]
fn legacy_handshake_negotiates_the_version() {
    let msgs = session(bare(), &[
        json!({ "jsonrpc": "2.0", "id": 1, "method": "initialize", "params": { "protocolVersion": "2025-06-18",
            "capabilities": {}, "clientInfo": { "name": "t", "version": "1" } } }),
        json!({ "jsonrpc": "2.0", "method": "notifications/initialized" }),
        json!({ "jsonrpc": "2.0", "id": 2, "method": "initialize", "params": { "protocolVersion": "2030-01-01" } }),
        json!({ "jsonrpc": "2.0", "id": 3, "method": "ping" }),
        json!({ "jsonrpc": "2.0", "id": 4, "method": "tools/list" }),
    ]);
    assert_eq!(msgs.len(), 4, "a notification gets no answer: {msgs:?}");
    let init = &by_id(&msgs, 1)["result"];
    assert_eq!(init["protocolVersion"], "2025-06-18");
    assert_eq!(init["capabilities"], json!({ "tools": {}, "prompts": {} }));
    assert_eq!(init["serverInfo"]["name"], "cpi-workbench");
    assert_eq!(by_id(&msgs, 2)["result"]["protocolVersion"], LEGACY[0], "unknown version: the newest legacy one");
    assert_eq!(by_id(&msgs, 3)["result"], json!({}));
    let list = &by_id(&msgs, 4)["result"];
    assert!(list.get("resultType").is_none() && list.get("ttlMs").is_none(), "legacy results stay legacy: {list}");
    assert_eq!(list["tools"].as_array().unwrap().len(), 12);
}

#[test]
fn modern_requests_are_stateless() {
    let msgs = session(bare(), &[
        modern(1, "server/discover", json!({})),
        modern(2, "tools/list", json!({})),
        json!({ "jsonrpc": "2.0", "id": 3, "method": "tools/list",
            "params": { "_meta": { META_VERSION: "2099-01-01" } } }),
    ]);
    let d = &by_id(&msgs, 1)["result"];
    assert_eq!(d["resultType"], "complete");
    assert_eq!(d["supportedVersions"][0], MODERN);
    assert!(d["supportedVersions"].as_array().unwrap().iter().any(|v| v == "2025-11-25"));
    assert_eq!(d["_meta"][META_SERVER]["name"], "cpi-workbench");
    assert!(d["ttlMs"].as_u64().is_some() && d["cacheScope"] == "public");
    let l = &by_id(&msgs, 2)["result"];
    assert_eq!(l["resultType"], "complete");
    assert!(l["ttlMs"].as_u64().is_some());
    let e = &by_id(&msgs, 3)["error"];
    assert_eq!(e["code"], UNSUPPORTED_PROTOCOL_VERSION);
    assert_eq!(e["data"]["requested"], "2099-01-01");
    assert_eq!(e["data"]["supported"][0], MODERN);
}

#[test]
fn protocol_errors() {
    let lines = "not json\n[1,2]\n{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"tools/list\"}\n\
        {\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"resources/list\"}\n\
        {\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"tools/call\",\"params\":{\"name\":\"nope\"}}\n\
        {\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{}}\n";
    let out = Arc::new(Mutex::new(Vec::<u8>::new()));
    for w in serve(Arc::new(bare()), Cursor::new(lines), out.clone()) {
        w.join().unwrap();
    }
    let text = String::from_utf8(out.lock().unwrap().clone()).unwrap();
    let msgs: Vec<Value> = text.lines().map(|l| serde_json::from_str(l).unwrap()).collect();
    let code = |id: Value| msgs.iter().filter(|m| m["id"] == id).map(|m| m["error"]["code"].clone()).collect::<Vec<_>>();
    assert_eq!(code(Value::Null), vec![json!(PARSE_ERROR), json!(INVALID_REQUEST), json!(INVALID_REQUEST)]);
    assert_eq!(code(json!(5)), vec![json!(METHOD_NOT_FOUND)]);
    assert_eq!(code(json!(6)), vec![json!(INVALID_PARAMS)]);
    assert_eq!(code(json!(7)), vec![json!(INVALID_PARAMS)]);

    // JSON-RPC 2.0 only; an object with an id but neither method nor result is no message; modern requests
    // carry their client capabilities.
    let msgs = session(bare(), &[
        json!({ "jsonrpc": "1.0", "id": 1, "method": "ping" }),
        json!({ "jsonrpc": "2.0", "id": 2 }),
        json!({ "jsonrpc": "2.0", "id": 3, "method": "tools/list", "params": { "_meta": { META_VERSION: MODERN } } }),
        json!({ "jsonrpc": "2.0", "id": 4, "result": {} }),
        json!({ "jsonrpc": "1.0", "method": "notifications/initialized" }),
    ]);
    assert_eq!(msgs.len(), 3, "a response and a notification get no answer: {msgs:?}");
    assert_eq!(by_id(&msgs, 1)["error"]["code"], INVALID_REQUEST);
    assert_eq!(by_id(&msgs, 2)["error"]["code"], INVALID_REQUEST);
    assert_eq!(by_id(&msgs, 3)["error"]["code"], INVALID_PARAMS);

    // params is an object; server/discover is a 2026-07-28 method and needs its _meta.
    let msgs = session(bare(), &[
        json!({ "jsonrpc": "2.0", "id": 1, "method": "tools/list", "params": "x" }),
        json!({ "jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": [] }),
        json!({ "jsonrpc": "2.0", "id": 3, "method": "server/discover" }),
    ]);
    assert_eq!(by_id(&msgs, 1)["error"]["code"], INVALID_REQUEST);
    assert_eq!(by_id(&msgs, 2)["error"]["code"], INVALID_PARAMS);
    assert_eq!(by_id(&msgs, 3)["error"]["code"], INVALID_PARAMS);
}

#[test]
fn tool_definitions_follow_the_spec() {
    let tools = tools::definitions();
    let names: Vec<&str> = tools.iter().map(|t| t["name"].as_str().unwrap()).collect();
    assert_eq!(names, ["project_info", "open_project", "read_file", "run_xslt", "run_groovy", "validate_xsd", "evaluate_xpath", "run_flow_step",
        "save_file", "import_iflow", "iflow_where_used", "reference"]);
    // Only tools that cannot run code are read-only: XSLT's java: calls and Groovy can (JavaExtensions).
    let read_only: Vec<&str> = tools.iter().filter(|t| t["annotations"]["readOnlyHint"] == true)
        .map(|t| t["name"].as_str().unwrap()).collect();
    assert_eq!(read_only, ["project_info", "read_file", "validate_xsd", "evaluate_xpath", "iflow_where_used", "reference"]);
    // Writing tools say so, so that a client can ask before they run; tools that run code keep the defaults.
    for name in ["save_file", "import_iflow"] {
        let a = &tools.iter().find(|t| t["name"] == name).unwrap()["annotations"];
        assert_eq!(*a, json!({ "readOnlyHint": false, "destructiveHint": true, "idempotentHint": true, "openWorldHint": false }), "{name}");
    }
    for name in ["run_xslt", "run_groovy", "run_flow_step"] {
        assert!(tools.iter().find(|t| t["name"] == name).unwrap().get("annotations").is_none(), "{name}");
    }
    // Switching the project creates at most a folder and replaces nothing.
    assert_eq!(tools.iter().find(|t| t["name"] == "open_project").unwrap()["annotations"],
        json!({ "readOnlyHint": false, "destructiveHint": false, "idempotentHint": true, "openWorldHint": false }));
    for t in &tools {
        let name = t["name"].as_str().unwrap();
        assert!(name.len() <= 128 && name.chars().all(|c| c.is_ascii_alphanumeric() || "_-.".contains(c)));
        assert!(!t["description"].as_str().unwrap().is_empty());
        assert_eq!(t["inputSchema"]["type"], "object");
        assert_eq!(t["inputSchema"]["additionalProperties"], false);
        for r in t["inputSchema"]["required"].as_array().unwrap() {
            assert!(t["inputSchema"]["properties"].get(r.as_str().unwrap()).is_some(), "{name}: {r}");
        }
    }
}

#[test]
fn wrong_arguments_are_tool_errors() {
    let call_tool = |name: &str, args: Value| {
        let msgs = session(bare(), &[json!({ "jsonrpc": "2.0", "id": 1, "method": "tools/call",
            "params": { "name": name, "arguments": args } })]);
        let r = msgs[0]["result"].clone();
        assert_eq!(r["isError"], true, "{r}");
        r["content"][0]["text"].as_str().unwrap().to_string()
    };
    let call = |args: Value| call_tool("evaluate_xpath", args);
    assert!(call(json!({ "expresion": "/a" })).contains("Unknown argument 'expresion'"));
    assert!(call(json!({})).contains("Missing argument 'expression'"));
    assert!(call(json!({ "expression": "/a", "version": "2.0" })).contains("must be one of"));
    assert!(call(json!({ "expression": "/a", "body": 1 })).contains("must be of type string"));
    assert!(call(json!({ "expression": "/a", "body": "<a/>", "bodyPath": "x.xml" })).contains("not both"));
    assert!(call(json!({ "expression": "/a", "headers": {} })).contains("Unknown argument 'headers'"), "the XPath tab has no headers");
    assert!(call_tool("run_groovy", json!({ "script": "x", "headers": { "h": 1 } })).contains("headers.h"));
    assert!(call(json!({ "expression": "/a", "bodyPath": "x.xml" })).contains("No project is open"));
    assert!(call_tool("read_file", json!({ "path": "a.xml", "startLine": 0 })).contains("startLine"));
    assert!(call_tool("save_file", json!({ "path": "a.xml", "content": "x", "overwrite": "yes" })).contains("type boolean"));
    assert!(call_tool("save_file", json!({ "path": "a.xml" })).contains("Give one of content, contentBase64"));
    assert!(call_tool("reference", json!({ "topic": "groovy" })).contains("must be one of"));
    assert!(call_tool("read_file", json!({ "path": "a.xml" })).contains("No project is open"));
}

#[test]
fn prompts_list_and_get() {
    let msgs = session(bare(), &[
        json!({ "jsonrpc": "2.0", "id": 1, "method": "prompts/list" }),
        modern(2, "prompts/list", json!({})),
        json!({ "jsonrpc": "2.0", "id": 3, "method": "prompts/get", "params": { "name": "develop_step",
            "arguments": { "task": "Map orders to S/4", "input": "inputs/order.xml" } } }),
        json!({ "jsonrpc": "2.0", "id": 4, "method": "prompts/get", "params": { "name": "nope" } }),
        json!({ "jsonrpc": "2.0", "id": 5, "method": "prompts/get", "params": { "name": "develop_step", "arguments": {} } }),
    ]);
    let p = &by_id(&msgs, 1)["result"]["prompts"][0];
    assert_eq!(p["name"], "develop_step");
    assert_eq!(p["arguments"][0], json!({ "name": "task", "description": p["arguments"][0]["description"], "required": true }));
    let modern_list = &by_id(&msgs, 2)["result"];
    assert_eq!(modern_list["resultType"], "complete");
    assert!(modern_list["ttlMs"].as_u64().is_some());
    let text = by_id(&msgs, 3)["result"]["messages"][0]["content"]["text"].as_str().unwrap().to_string();
    assert!(text.contains("Map orders to S/4") && text.contains("inputs/order.xml") && text.contains("project_info"), "{text}");
    assert_eq!(by_id(&msgs, 3)["result"]["messages"][0]["role"], "user");
    assert_eq!(by_id(&msgs, 4)["error"]["code"], INVALID_PARAMS);
    assert_eq!(by_id(&msgs, 5)["error"]["code"], INVALID_PARAMS);
}

/// The reference is generated from the sources; renaming a doc section or a Java class must not empty it.
#[test]
fn reference_topics_come_from_the_sources() {
    let api = reference::topic("script-api").unwrap();
    for member in ["`Object getBody()`", "`void setHeader(String name, Object value)`", "`void resetBodyStream()`",
        "`MessageLog getMessageLog(Object message)`", "`void addValue(Object value)`: Appends a value.",
        "`String getMappedValue(String sourceAgency, String sourceIdentifier, String sourceValue, String targetAgency, String targetIdentifier)`",
        "`UserCredential getUserCredential(String alias) throws SecureStoreException`",
        "`String getNextValuefromNumberRange(String name, String correlationId) throws NumberRangeConfigException`",
        "## interface com.sap.it.api.mapping.MappingContext", "## com.sap.it.api.keystore.KeystoreService"] {
        assert!(api.contains(member), "{member} missing from:
{api}");
    }
    assert!(!api.contains("Platform") && !api.contains("first("), "no constructors or private helpers: {api}");
    let platform = reference::topic("platform").unwrap();
    assert!(platform.starts_with("## Mocked platform data") && platform.contains("value-mappings.json"), "{platform}");
    assert!(!platform.contains("## Further"), "one section only: {platform}");
    let fidelity = reference::topic("fidelity").unwrap();
    assert!(fidelity.contains("## Groovy Script") && fidelity.contains("| Area | Applies to | Basis | Status |"), "{fidelity}");
    assert!(reference::topic("nope").is_err());
}

#[test]
fn cancelled_requests_get_no_answer() {
    // The cancel may arrive before or after the answer; either way at most one message for id 1, and the
    // id is free again afterwards.
    let msgs = session(bare(), &[
        json!({ "jsonrpc": "2.0", "id": 1, "method": "tools/list" }),
        json!({ "jsonrpc": "2.0", "method": "notifications/cancelled", "params": { "requestId": 1 } }),
        json!({ "jsonrpc": "2.0", "id": 2, "method": "tools/list" }),
    ]);
    assert!(msgs.iter().filter(|m| m["id"] == 1).count() <= 1);
    assert!(msgs.iter().any(|m| m["id"] == 2));
}

#[test]
fn function_simple_becomes_a_string_literal() {
    let r = flow::resolve_simple_functions(
        "/a[@id = function:simple('${property.id}')]/b[. = function:simple( \"${header.x}\" )]",
        |e| Ok(if e.contains("property") { "it's".into() } else { "x".into() })).unwrap();
    assert_eq!(r, "/a[@id = 'it''s']/b[. = 'x']");
    assert_eq!(flow::resolve_simple_functions("function:simple(oops)", |_| Ok("".into())).unwrap(), "function:simple(oops)");
}

/// The MCP server's side of the parity fixtures (`testdata/flow-parity`): each step run makes exactly the engine
/// calls the fixture lists, as the Flow Steps tab does (`app/tests/flowparity.test.mts`). No engine runs.
#[test]
fn flow_parity() {
    let dir = std::path::Path::new(env!("CARGO_MANIFEST_DIR")).join("../../testdata/flow-parity");
    let mut files: Vec<_> = std::fs::read_dir(&dir).unwrap().map(|e| e.unwrap().path())
        .filter(|p| p.extension().is_some_and(|x| x == "json")).collect();
    files.sort();
    assert!(files.len() >= 10, "{files:?}");
    for file in files {
        let f: Value = serde_json::from_str(&std::fs::read_to_string(&file).unwrap()).unwrap();
        let answers = std::cell::RefCell::new(f["answers"].as_object().cloned().unwrap_or_default());
        let calls = std::cell::RefCell::new(Vec::<Value>::new());
        let call = |engine: &str, method: &str, params: Value| -> Result<Value, String> {
            calls.borrow_mut().push(json!({ "engine": engine, "method": method, "params": params }));
            let mut a = answers.borrow_mut();
            Ok(match a.get_mut(method).and_then(Value::as_array_mut).filter(|l| !l.is_empty()) {
                Some(list) => list.remove(0),
                None => json!({ "ok": true }),
            })
        };
        let ctx = flow::Ctx { call: &call, xml: "xml", camel: "camel", namespaces: f["namespaces"].clone(), input: f["input"].clone() };
        let kind = f["step"]["type"].as_str().unwrap();
        let result = flow::run_step(&ctx, kind, &f["step"]["config"], f.get("messages").cloned());
        if let Some(error) = f["error"].as_str() {
            assert_eq!(result.unwrap_err(), error, "{}", file.display());
        } else {
            let result = result.unwrap_or_else(|e| panic!("{}: {e}", file.display()));
            if let Some(expected) = f.get("result") {
                assert_eq!(&result, expected, "{}", file.display());
            }
        }
        assert_eq!(Value::Array(calls.into_inner()), f["calls"], "{}", file.display());
    }
}

// ------------------------------------------------------------------ with engines

#[test]
fn content_modifier_uses_one_exchange_across_xpath_on_both_pairs() {
    let Some(engines) = engines() else { return };
    let call = |profile: &str, method: &str, params: Value| engines.call(profile, method, params);
    for (xml, camel) in [("saxon-he-9.9", "groovy-1.x"), ("saxon-he-12", "groovy-2.x")] {
        let ctx = flow::Ctx { call: &call, xml, camel, namespaces: json!({}),
            input: json!({ "body": "<a/>", "headers": { "removeMe": "original" }, "properties": {} }), };
        let r = flow::run_step(&ctx, "contentModifier", &json!({
            "headers": [
                { "name": "firstId", "type": "expression", "value": "${exchangeId}" },
                { "name": "copiedId", "type": "xpath", "value": "$firstId" },
                { "name": "removeMe", "action": "Delete" }
            ],
            "properties": [
                { "name": "lastId", "type": "expression", "value": "${exchangeId}" },
                { "name": "rootName", "type": "xpath", "value": "name(/*)" }
            ],
            "bodyType": "expression", "body": "${property.lastId}"
        }), None).unwrap();
        assert_eq!(r["ok"], true, "{xml}/{camel}: {r}");
        let id = r["output"]["body"].as_str().unwrap();
        assert!(!id.is_empty());
        let trace = r["trace"].as_array().unwrap();
        assert_eq!(trace.len(), 6, "{r}");
        for i in [0, 1, 3, 5] {
            assert_eq!(trace[i]["value"], id, "{r}");
        }
        assert_eq!(trace[4]["value"], "a", "{r}");
        assert_eq!(r["changes"]["headers"]["removed"], json!(["removeMe"]), "{r}");
    }
    engines.shutdown();
}

/// A copy of the regression project with the iFlow test fixture under iflows/.
fn project(tag: &str) -> PathBuf {
    let repo = Path::new(env!("CARGO_MANIFEST_DIR")).join("../..");
    let dir = std::env::temp_dir().join(format!("cpiwb-mcp-{tag}-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&dir);
    copy_dir(&repo.join("testdata/regression-project"), &dir);
    copy_dir(&repo.join("engine/core/src/test/resources/iflow"), &dir.join("iflows").join("Order_Replication"));
    dir
}

fn copy_dir(from: &Path, to: &Path) {
    std::fs::create_dir_all(to).unwrap();
    for e in std::fs::read_dir(from).unwrap() {
        let e = e.unwrap();
        let target = to.join(e.file_name());
        if e.file_type().unwrap().is_dir() { copy_dir(&e.path(), &target) } else { std::fs::copy(e.path(), target).unwrap(); }
    }
}

/// All four engines (a repository build), or None outside CI when they are missing.
fn engines() -> Option<Arc<Engines>> {
    let engines = Arc::new(Engines::default());
    let all = crate::engine::PROFILES.iter().all(|(p, _, _)| engines.locate(p).is_some());
    if !all {
        assert!(std::env::var_os("CPIWB_REQUIRE_ENGINES").is_none(), "engines missing: {:?}", engines.available());
        return None;
    }
    Some(engines)
}

struct Call<'a>(&'a Server);

impl Call<'_> {
    fn ok(&self, name: &str, args: Value) -> Value {
        match tools::call(self.0, name, args.as_object().unwrap()) {
            Ok(v) => v,
            Err(e) => panic!("{name}: {e}"),
        }
    }
    fn err(&self, name: &str, args: Value) -> String {
        tools::call(self.0, name, args.as_object().unwrap()).expect_err(name)
    }
}

#[test]
fn tools_run_on_the_engines() {
    let Some(engines) = engines() else { return };
    let dir = project("tools");
    let server = Server::new(engines.clone(), Some(dir.clone()));
    let c = Call(&server);

    let info = c.ok("project_info", json!({}));
    assert!(info["files"].as_array().unwrap().iter().any(|f| f == "mappings/order-to-salesorder.xsl"), "{info}");
    assert_eq!(info["runtimes"].as_array().unwrap().len(), 4);
    assert!(info["iflowSteps"].as_array().unwrap().iter().any(|s| s["name"] == "Gather items"));

    // XSLT: header as xsl:param, on both Saxon lines.
    let x = c.ok("run_xslt", json!({ "runtime": "both", "body": "<a/>", "headers": { "h": "hello" },
        "mapping": "<xsl:stylesheet version='2.0' xmlns:xsl='http://www.w3.org/1999/XSL/Transform'><xsl:param name='h'/>\
            <xsl:output method='text'/><xsl:template match='/'><xsl:value-of select='$h'/></xsl:template></xsl:stylesheet>" }));
    assert_eq!(x["runs"][0]["result"]["output"]["body"], "hello", "{x}");
    assert_eq!(x["runs"][1]["result"]["output"]["body"], "hello", "{x}");
    assert_eq!(x["runs"][1]["runtime"], "saxon-he-12");

    // Groovy: the runtime follows the script's imports; typed property.
    let g = c.ok("run_groovy", json!({ "body": "abc", "properties": { "n": { "value": "2", "type": "Integer" } },
        "script": "import com.sap.it.script.v2.api.Message\n\
            def Message processData(Message m) { m.setBody(m.getBody(String) * m.getProperty('n')); m }" }));
    assert_eq!(g["runtime"], "groovy-2.x");
    assert_eq!(g["output"]["body"], "abcabc", "{g}");
    let typed = c.ok("run_groovy", json!({ "body": "x", "bodyType": "InputStream",
        "script": "def processData(m) { m.setBody(m.getBody() instanceof InputStream ? 'stream' : 'other'); m }" }));
    assert_eq!(typed["output"]["body"], "stream", "the Groovy tab's Body type: {typed}");
    // A Message Mapping function with a queue argument, on 1.x (its imports are not v2).
    let mm = c.ok("run_groovy", json!({ "function": "join", "args": [[["A", "B"], ["C"]]],
        "script": "import com.sap.it.api.mapping.*\n\
            def void join(String[] a, Output out, MappingContext c) { out.addValue(a.join('+')) }" }));
    assert_eq!(mm["runtime"], "groovy-1.x");
    assert_eq!(mm["mapping"]["queue"], json!([["A+B"], ["C"]]), "{mm}");

    let v = c.ok("validate_xsd", json!({ "schemaPath": "schemas/salesorder.xsd", "body": "<nope/>" }));
    assert_eq!(v["valid"], false, "{v}");

    let xp = c.ok("evaluate_xpath", json!({ "expression": "concat(/a/@x, '2')", "body": "<a x='1'/>" }));
    assert_eq!(xp["items"][0]["value"], "12", "{xp}");

    // Converters: given directly, and from the iFlow (its {{RootName}} and namespace mapping).
    let x2j = c.ok("run_flow_step", json!({ "body": "<a><b>1</b><b>2</b></a>",
        "step": { "type": "converter", "config": { "conversion": "xmlToJson" } } }));
    assert_eq!(x2j["body"], "{\"a\":{\"b\":[\"1\",\"2\"]}}", "{x2j}");
    // Keys left out take the tab's defaults: XML based header for XML to CSV, header line for CSV to XML.
    let x2c = c.ok("run_flow_step", json!({ "body": "<Order><Item><Name>A</Name></Item></Order>",
        "step": { "type": "converter", "config": { "conversion": "xmlToCsv", "sourcePath": "/Order/Item" } } }));
    assert_eq!(x2c["body"], "Name\nA\n", "{x2c}");
    let j2x = c.ok("run_flow_step", json!({ "iflowStep": "JSON to XML", "body": "{\"x\":\"1\",\"y\":2}" }));
    assert_eq!(j2x["body"], "<?xml version='1.0' encoding='UTF-8'?><Orders xmlns:ord=\"urn:orders\"><x>1</x><y>2</y></Orders>", "{j2x}");

    // Flow steps from the iFlow.
    let order = "<Order id=\"7\"><Item>a</Item><Item>b</Item><Item>c</Item></Order>";
    let cm = c.ok("run_flow_step", json!({ "iflowStep": "Set headers", "body": order, "headers": { "X-Trace": "1" } }));
    let headers = cm["output"]["headers"].as_array().unwrap();
    assert!(headers.iter().any(|h| h["name"] == "OrderId" && h["value"] == "7"), "{cm}");
    assert!(!headers.iter().any(|h| h["name"] == "X-Trace"), "$include=X-* deletes it: {cm}");
    assert!(cm["output"]["body"].as_str().unwrap().starts_with("<Wrapped>"), "{cm}");

    let keep = c.ok("run_flow_step", json!({ "iflowStep": "Keep items", "body": order }));
    assert_eq!(keep["body"], "<Item>a</Item><Item>b</Item><Item>c</Item>", "{keep}");

    let route = c.ok("run_flow_step", json!({ "iflowStep": "Check sender", "headers": { "SenderId": "ERP" } }));
    assert_eq!(route["taken"], "ERP", "{route}");
    assert_eq!(route["diagnostics"], json!([]), "the tab's result shape: {route}");
    let same = c.ok("run_flow_step", json!({ "iflowStep": "Check sender", "iflow": "Order_Replication", "headers": { "SenderId": "ERP" } }));
    assert_eq!(same["taken"], "ERP");
    assert!(c.err("run_flow_step", json!({ "iflowStep": "Check sender", "iflow": "Other_Flow" })).contains("No iFlow step"));
    let other = c.ok("run_flow_step", json!({ "iflowStep": "Check sender", "headers": { "SenderId": "Shop" } }));
    assert_eq!(other["taken"], "Other (default route)", "{other}");

    let split = c.ok("run_flow_step", json!({ "iflowStep": "Split items", "body": order }));
    let parts: Vec<Value> = split["messages"].as_array().unwrap().iter().map(|m| m["body"].clone()).collect();
    assert_eq!(parts.len(), 2, "grouping 2: {split}");
    let gathered = c.ok("run_flow_step", json!({ "iflowStep": "Gather items", "messages": parts }));
    let body = gathered["body"].as_str().unwrap();
    assert_eq!(body.matches("<Item>").count(), 3, "{gathered}");

    // A step given directly, with function:simple in the Filter XPath.
    let f = c.ok("run_flow_step", json!({ "body": order, "properties": { "want": "b" },
        "step": { "type": "filter", "config": { "expression": "/Order/Item[. = function:simple('${property.want}')]", "valueType": "string" } } }));
    assert_eq!(f["body"], "b", "{f}");
    // As in the tab, only a missing value gets the default; an empty one goes to the engine as it is.
    let empty = c.ok("run_flow_step", json!({ "body": order, "step": { "type": "filter", "config": { "expression": "/Order", "valueType": "" } } }));
    assert_eq!(empty["ok"], false, "{empty}");
    // Encoder and Message Digest. The digest's header travels on with the message; keys left out take the tab's defaults.
    let enc = c.ok("run_flow_step", json!({ "body": "<a/>", "step": { "type": "encoder", "config": {} } }));
    assert_eq!(enc["body"], "PGEvPg==\r\n", "{enc}");
    assert!(c.ok("run_flow_step", json!({ "body": "<a/>", "step": { "type": "encoder", "config": { "scheme": "mimeMultipartEncode" } } }))
        .to_string().contains("UnsupportedInSimulator"));
    // A binary result travels on as bytes: by runId to a Decoder, into a file and from it as bodyPath.
    let gz = c.ok("run_flow_step", json!({ "body": "<a/>", "step": { "type": "encoder", "config": { "scheme": "gzipCompress" } } }));
    assert_eq!(gz["binary"], true, "{gz}");
    let gunzip = c.ok("run_flow_step", json!({ "messageFrom": gz["runId"], "step": { "type": "decoder", "config": { "scheme": "gzipDecompress" } } }));
    assert_eq!(gunzip["body"], "<a/>", "{gunzip}");
    let zip = c.ok("run_flow_step", json!({ "body": "<a/>", "headers": { "CamelFileName": "in/a.xml" },
        "step": { "type": "encoder", "config": { "scheme": "zipCompress" } } }));
    assert!(zip["output"]["headers"].to_string().contains("a.xml.zip"), "{zip}");
    c.ok("save_file", json!({ "path": "inputs/a.zip", "contentFrom": zip["runId"] }));
    let bytes = c.ok("read_file", json!({ "path": "inputs/a.zip", "binary": true }));
    assert!(bytes["contentBase64"].as_str().unwrap().starts_with("UEsDB"), "{bytes}");
    assert!(c.err("read_file", json!({ "path": "inputs/a.zip" })).contains("binary: true"));
    assert_eq!(c.ok("read_file", json!({ "runId": zip["runId"] }))["contentBase64"], bytes["contentBase64"]);
    let unzip = c.ok("run_flow_step", json!({ "bodyPath": "inputs/a.zip", "step": { "type": "decoder", "config": { "scheme": "zipDecompress" } } }));
    assert_eq!(unzip["body"], "<a/>", "{unzip}");
    assert!(unzip["output"]["headers"].to_string().contains("\"a.xml\""), "{unzip}");
    c.ok("save_file", json!({ "path": "inputs/b.gz", "contentBase64": "H4sIAAAAAAAC/7NJ0rfjAgCsbt2hBQAAAA==" }));
    let b = c.ok("run_flow_step", json!({ "bodyPath": "inputs/b.gz", "step": { "type": "decoder", "config": { "scheme": "gzipDecompress" } } }));
    assert_eq!(b["body"], "<b/>\n", "{b}");
    assert!(c.err("save_file", json!({ "path": "inputs/c.txt", "content": "x", "contentBase64": "eA==" })).contains("one of"));
    let dig = c.ok("run_flow_step", json!({ "body": "<a  y='2' x='1'/>", "headers": { "Keep": "1" },
        "step": { "type": "messageDigest", "config": { "algorithm": "MD5", "targetHeader": "D" } } }));
    assert_eq!(dig["canonical"], "<a x=\"1\" y=\"2\"></a>", "{dig}");
    let after = c.ok("run_groovy", json!({ "messageFrom": dig["runId"],
        "script": "def processData(m) { m.setBody(m.getHeaders()['Keep'] + '|' + m.getHeaders()['D'] + '|' + m.getBody(String)); m }" }));
    assert_eq!(after["output"]["body"], format!("1|{}|<a  y='2' x='1'/>", dig["header"]["value"].as_str().unwrap()), "{after}");
    assert!(c.err("run_flow_step", json!({ "body": "<a/>", "step": { "type": "messageDigest", "config": { "xpath": "/a" } } }))
        .contains("filter, canonicalization, algorithm, targetHeader"));
    // A key the tab does not have is named, rather than ignored (tried on 2026-11-06 from Claude Desktop).
    let typo = c.err("run_flow_step", json!({ "body": order, "step": { "type": "splitter", "config": { "xpath": "/Order/Item" } } }));
    assert!(typo.contains("Unknown splitter config key(s): xpath") && typo.contains("expression"), "{typo}");

    // Script, XSLT and XML Validator steps run in their own tools, as their file opens in its tab.
    assert!(c.err("run_flow_step", json!({ "iflowStep": "CallActivity_1", "body": order })).contains("run_groovy"));
    assert!(c.err("run_flow_step", json!({ "iflowStep": "No such step" })).contains("No iFlow step"));

    // Files outside the project are refused by the engine.
    assert!(c.err("evaluate_xpath", json!({ "expression": "/", "bodyPath": "../outside.xml" })).contains("outside the project"));

    engines.shutdown();
    let _ = std::fs::remove_dir_all(&dir);
}

/// Where-used search and the parameter overview over the two iFlows of the index fixture (engine/core, F69).
#[test]
fn where_used_searches_the_imported_iflows() {
    let Some(engines) = engines() else { return };
    let dir = project("whereused");
    let repo = Path::new(env!("CARGO_MANIFEST_DIR")).join("../..");
    copy_dir(&repo.join("engine/core/src/test/resources/iflow-index/iflows"), &dir.join("iflows"));
    let server = Server::new(engines.clone(), Some(dir.clone()));
    let c = Call(&server);

    let q = c.ok("iflow_where_used", json!({ "query": "ORDERS_IN" }));
    let places: Vec<String> = q["hits"].as_array().unwrap().iter()
        .map(|h| format!("{}/{}", h["iflow"].as_str().unwrap(), h["key"].as_str().unwrap())).collect();
    assert_eq!(places, ["Inbound_Files/QueueName_outbound", "Outbound_Orders/QueueName_inbound"], "{q}");
    assert_eq!(q["hits"][0]["kind"], "queue");

    let cred = c.ok("iflow_where_used", json!({ "kind": "credential", "iflow": "Outbound_Orders" }));
    assert_eq!(cred["hits"].as_array().unwrap().len(), 1, "{cred}");
    assert_eq!(cred["hits"][0]["key"], "credentialName");

    let p = c.ok("iflow_where_used", json!({ "parameters": true, "iflow": "Inbound_Files" }));
    assert_eq!(p["iflows"].as_array().unwrap().len(), 1, "{p}");
    let host = p["iflows"][0]["parameters"].as_array().unwrap().iter().find(|x| x["name"] == "SftpHost").unwrap().clone();
    assert_eq!(host["description"], "Partner SFTP server", "{host}");
    assert_eq!(host["required"], true);

    assert!(c.err("iflow_where_used", json!({ "parameters": true, "query": "x" })).contains("parameters lists"));
    // Without query or kind: the tab's overview, with each iFlow's parameters.
    let o = c.ok("iflow_where_used", json!({}));
    let queue = o["uses"].as_array().unwrap().iter().find(|g| g["kind"] == "queue").expect("queues");
    assert_eq!(queue["values"][0], json!({ "value": "ORDERS_IN", "count": 2, "resolved": [] }), "{o}");
    assert_eq!(o["iflows"].as_array().unwrap().len(), 3, "{o}");
    // A {{parameter}} resolves from parameters.prop; a table hit is its matching rows; parameters match by name.
    let sftp = c.ok("iflow_where_used", json!({ "query": "{{SftpHost}}" }));
    assert!(sftp["hits"].as_array().unwrap().iter().any(|h| h["resolved"].as_str().is_some_and(|r| r.contains("sftp.partner.example.com"))), "{sftp}");
    let auth = c.ok("iflow_where_used", json!({ "query": "Token" }));
    let table = auth["hits"].as_array().unwrap().iter().find(|h| h.get("rows").is_some()).expect("a table hit");
    assert!(table.get("value").is_none(), "{table}");
    let cell = table["rows"][0].as_array().unwrap().iter().find(|c| c["text"].as_str().unwrap().contains("{{Token 'Auth'}}")).expect("the cell");
    assert_eq!(cell["resolved"], "secret-token-alias", "{auth}");
    assert_eq!(auth["parameters"][0]["parameters"][0]["name"], "Token 'Auth'", "{auth}");
    assert!(c.ok("iflow_where_used", json!({ "query": "Token", "kind": "queue" })).get("parameters").is_none());
    let _ = std::fs::remove_dir_all(&dir);
}

#[test]
fn the_server_takes_a_project_or_a_workspace() {
    let dir = std::env::temp_dir().join(format!("cpiwb-mcp-args-{}", std::process::id()));
    std::fs::create_dir_all(&dir).unwrap();
    let d = dir.to_string_lossy().to_string();
    let args = |a: &[&str]| parse_args(&a.iter().map(|s| s.to_string()).collect::<Vec<_>>());
    let real = existing_folder(&dir, "x").unwrap();
    assert_eq!(args(&["--workspace", &d]), Ok(Folder::Workspace(real.clone())));
    assert_eq!(args(&["--project", &d]), Ok(Folder::Project(real)));
    assert!(args(&["--project", &d, "--workspace", &d]).unwrap_err().contains("not both"));
    assert!(args(&["--workspace"]).unwrap_err().contains("needs a folder"));
    let _ = std::fs::remove_dir_all(&dir);
}

/// open_project opens folders directly inside the workspace only; checked before any engine runs.
#[test]
fn open_project_stays_inside_the_workspace() {
    let c = Call(&bare());
    assert!(c.err("open_project", json!({ "name": "a" })).contains("needs a workspace"));
    let root = std::env::temp_dir().join(format!("cpiwb-mcp-ws-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&root);
    for f in ["Order", "billing", ".git"] {
        std::fs::create_dir_all(root.join(f)).unwrap();
    }
    std::fs::write(root.join("notes.txt"), "x").unwrap();
    let ws = existing_folder(&root, "workspace").unwrap();
    let server = Server::in_workspace(Arc::new(Engines::default()), ws.clone());
    let c = Call(&server);
    let info = c.ok("project_info", json!({}));
    assert_eq!(info["projects"], json!(["billing", "Order"]), "{info}");
    assert!(info["project"].is_null(), "{info}");
    assert!(c.err("read_file", json!({ "path": "a.xml" })).contains("open_project opens"));
    for bad in ["", "..", ".", "a/b", "a\\b", "C:x", "trailing.", "../Order"] {
        assert!(c.err("open_project", json!({ "name": bad })).contains("not a folder name"), "{bad}");
    }
    assert!(c.err("open_project", json!({ "name": "missing" })).contains("No folder 'missing'"));
    assert!(c.err("open_project", json!({ "name": "notes.txt" })).contains("No folder"));
    #[cfg(unix)]
    {
        let outside = std::env::temp_dir().join(format!("cpiwb-mcp-ws-out-{}", std::process::id()));
        std::fs::create_dir_all(&outside).unwrap();
        std::os::unix::fs::symlink(&outside, root.join("link")).unwrap();
        assert!(c.err("open_project", json!({ "name": "link" })).contains("outside the workspace"));
        let _ = std::fs::remove_dir_all(&outside);
    }
    assert!(server.project().is_none(), "a refused call opens nothing");
    files::open_project(&server, json!({ "name": "Order" }).as_object().unwrap()).unwrap();
    assert_eq!(server.project(), Some(ws.join("Order")));
    let _ = std::fs::remove_dir_all(&root);
}

/// A workspace's projects are opened and created on the engines; each call reads the project open at the time.
#[test]
fn a_workspace_switches_and_creates_projects() {
    let Some(engines) = engines() else { return };
    let root = std::env::temp_dir().join(format!("cpiwb-mcp-wsrun-{}", std::process::id()));
    let _ = std::fs::remove_dir_all(&root);
    std::fs::create_dir_all(&root).unwrap();
    std::fs::rename(project("ws"), root.join("Orders")).unwrap();
    let server = Server::in_workspace(engines.clone(), existing_folder(&root, "workspace").unwrap());
    let c = Call(&server);
    let info = c.ok("open_project", json!({ "name": "Orders" }));
    assert_eq!(info["created"], false, "{info}");
    assert!(info["iflowSteps"].as_array().is_some_and(|s| !s.is_empty()), "{info}");
    let r = c.ok("run_xslt", json!({ "mappingPath": "mappings/order-to-salesorder.xsl", "bodyPath": "tests/standard-order/input.xml" }));
    assert_eq!(r["ok"], true, "{r}");
    let run = r["runId"].as_str().unwrap().to_string();

    let info = c.ok("open_project", json!({ "name": "Billing", "create": true }));
    assert_eq!(info["created"], true, "{info}");
    assert_eq!(info["projects"], json!(["Billing", "Orders"]), "{info}");
    assert!(root.join("Billing/cpi-workbench.json").is_file() && root.join("Billing/platform/credentials.json").is_file());
    let gone = c.err("read_file", json!({ "path": "tests/standard-order/input.xml" }));
    assert!(gone.contains("Billing"), "the other project's files are gone: {gone}");
    // A run of the first project still passes its message on.
    c.ok("save_file", json!({ "path": "inputs/from-orders.xml", "contentFrom": run }));
    assert!(root.join("Billing/inputs/from-orders.xml").is_file());
    assert!(c.err("open_project", json!({ "name": "Billing", "create": true })).contains("exists already"));
    c.ok("open_project", json!({ "name": "Orders" }));
    assert!(c.ok("read_file", json!({ "path": "tests/standard-order/input.xml" }))["content"].is_string());
    engines.shutdown();
    let _ = std::fs::remove_dir_all(&root);
}

/// After a timeout the engine restarts; it must have the project open again.
#[test]
fn a_restarted_engine_reopens_the_project() {
    let Some(engines) = engines() else { return };
    let dir = project("restart");
    let server = Server::new(engines.clone(), Some(dir.clone()));
    let timeout = engines.call("groovy-1.x", "groovy.run", json!({ "timeoutMs": 300,
        "script": { "content": "def processData(m) { Thread.sleep(3000); m }" }, "input": { "body": "" } }));
    assert!(timeout.unwrap_err().starts_with("Timeout"));
    // A script given by path is read from the project by the restarted engine.
    let r = Call(&server).ok("run_groovy", json!({ "runtime": "groovy-1.x", "scriptPath": "scripts/enrich-order.groovy", "body": "<a/>" }));
    assert!(r["ok"].is_boolean(), "{r}");
    assert!(!r.to_string().contains("NoSuchFile") && !r.to_string().contains("No such file"), "{r}");
    engines.shutdown();
    let _ = std::fs::remove_dir_all(&dir);
}

/// Two iFlows with the same step: the name or id alone is ambiguous, `iflow` picks one.
#[test]
fn iflow_picks_among_equal_step_names() {
    let Some(engines) = engines() else { return };
    let dir = project("dup");
    let repo = Path::new(env!("CARGO_MANIFEST_DIR")).join("../..");
    copy_dir(&repo.join("engine/core/src/test/resources/iflow"), &dir.join("iflows").join("Order_Copy"));
    let server = Server::new(engines.clone(), Some(dir.clone()));
    let c = Call(&server);
    let e = c.err("run_flow_step", json!({ "iflowStep": "CallActivity_9", "body": "<Order/>" }));
    assert!(e.contains("Order_Copy") && e.contains("Order_Replication"), "{e}");
    let r = c.ok("run_flow_step", json!({ "iflowStep": "CallActivity_9", "iflow": "Order_Copy", "body": "<Order><Item>a</Item></Order>" }));
    assert_eq!(r["body"], "<Item>a</Item>", "{r}");
    engines.shutdown();
    let _ = std::fs::remove_dir_all(&dir);
}

/// A second request with a running request's id is refused and leaves the first one's cancellation alone.
#[test]
fn a_reused_id_keeps_the_cancellation() {
    let Some(engines) = engines() else { return };
    let server = Server::new(engines.clone(), None);
    let slow = json!({ "jsonrpc": "2.0", "id": 1, "method": "tools/call", "params": { "name": "run_groovy",
        "arguments": { "runtime": "groovy-1.x", "script": "def processData(m) { Thread.sleep(1500); m }" } } });
    let msgs = session(server, &[
        slow.clone(),
        json!({ "jsonrpc": "2.0", "method": "notifications/cancelled", "params": { "requestId": 1 } }),
        slow,
    ]);
    let ones: Vec<&Value> = msgs.iter().filter(|m| m["id"] == 1).collect();
    assert_eq!(ones.len(), 1, "only the refusal, no answer to the cancelled run: {msgs:?}");
    assert_eq!(ones[0]["error"]["code"], INVALID_REQUEST);
    engines.shutdown();
}


/// A zip with stored (uncompressed) entries, enough for an iFlow export in a test.
fn stored_zip(entries: &[(&str, &str)]) -> Vec<u8> {
    fn crc32(data: &[u8]) -> u32 {
        let mut c = !0u32;
        for b in data {
            c ^= *b as u32;
            for _ in 0..8 {
                c = if c & 1 != 0 { (c >> 1) ^ 0xEDB8_8320 } else { c >> 1 };
            }
        }
        !c
    }
    let (mut out, mut central) = (Vec::new(), Vec::new());
    for (name, text) in entries {
        let (n, d) = (name.as_bytes(), text.as_bytes());
        let (crc, offset) = (crc32(d), out.len() as u32);
        let fixed = |sig: u32, central: bool| {
            let mut h = sig.to_le_bytes().to_vec();
            if central { h.extend(20u16.to_le_bytes()); }
            for v in [20u16, 0, 0, 0, 0] { h.extend(v.to_le_bytes()); }
            h.extend(crc.to_le_bytes());
            h.extend((d.len() as u32).to_le_bytes());
            h.extend((d.len() as u32).to_le_bytes());
            h.extend((n.len() as u16).to_le_bytes());
            h.extend(0u16.to_le_bytes());
            h
        };
        out.extend(fixed(0x0403_4b50, false));
        out.extend(n);
        out.extend(d);
        central.extend(fixed(0x0201_4b50, true));
        for v in [0u16, 0, 0] { central.extend(v.to_le_bytes()); }
        central.extend(0u32.to_le_bytes());
        central.extend(offset.to_le_bytes());
        central.extend(n);
    }
    let (start, size) = (out.len() as u32, central.len() as u32);
    out.extend(central);
    out.extend(0x0605_4b50u32.to_le_bytes());
    for v in [0u16, 0, entries.len() as u16, entries.len() as u16] { out.extend(v.to_le_bytes()); }
    out.extend(size.to_le_bytes());
    out.extend(start.to_le_bytes());
    out.extend(0u16.to_le_bytes());
    out
}

/// Claude Desktop has no file access of its own: it reads, saves and imports through the server.
#[test]
fn files_are_read_saved_and_imported_inside_the_project() {
    let Some(engines) = engines() else { return };
    let dir = project("files");
    let server = Server::new(engines.clone(), Some(dir.clone()));
    let c = Call(&server);

    // Read, whole and in parts.
    std::fs::create_dir_all(dir.join("inputs")).unwrap();
    std::fs::write(dir.join("inputs/lines.txt"), "one\ntwo\nthree\n").unwrap();
    let all = c.ok("read_file", json!({ "path": "inputs/lines.txt" }));
    assert_eq!(all["content"], "one\ntwo\nthree\n");
    assert_eq!(all["totalLines"], 3);
    assert!(all.get("truncated").is_none(), "{all}");
    let part = c.ok("read_file", json!({ "path": "inputs/lines.txt", "startLine": 2, "maxLines": 1 }));
    assert_eq!(part["content"], "two\n");
    assert!(part["truncated"].as_str().unwrap().contains("startLine 3"), "{part}");
    assert!(c.err("read_file", json!({ "path": "inputs/lines.txt", "startLine": 9 })).contains("past its end"));
    // Minified XML: one line longer than the cap comes back cut, and says so.
    std::fs::write(dir.join("inputs/minified.xml"), format!("<a>{}</a>", "é".repeat(150_000))).unwrap();
    let long = c.ok("read_file", json!({ "path": "inputs/minified.xml" }));
    assert!(long["content"].as_str().unwrap().len() <= 200_000 && long["lineCut"].is_string(), "{}", long["lineCut"]);
    assert!(c.err("read_file", json!({ "path": "inputs" })).contains("folder"));
    for outside in ["../outside.xml", "inputs/../../outside.xml", "/etc/hosts", r"C:\Windows\win.ini"] {
        assert!(c.err("read_file", json!({ "path": outside })).contains("not a project-relative path"), "{outside}");
    }

    // Calls run in parallel: of two saves to the same new path without overwrite, one wins and one is refused (F02).
    let results: Vec<Result<Value, String>> = std::thread::scope(|scope| {
        let handles: Vec<_> = ["A", "B"].iter().map(|text| {
            let server = &server;
            scope.spawn(move || tools::call(server, "save_file", json!({ "path": "inputs/race.txt", "content": text }).as_object().unwrap()))
        }).collect();
        handles.into_iter().map(|h| h.join().unwrap()).collect()
    });
    assert_eq!(results.iter().filter(|r| r.is_ok()).count(), 1, "{results:?}");
    assert!(results.iter().any(|r| r.as_ref().is_err_and(|e| e.contains("exists already"))), "{results:?}");

    // Save: new files and folders; an existing file only with overwrite.
    let s = c.ok("save_file", json!({ "path": "mappings/new/agent.xsl", "content": "<a/>" }));
    assert_eq!(s, json!({ "path": "mappings/new/agent.xsl", "saved": true, "replaced": false }));
    assert_eq!(std::fs::read_to_string(dir.join("mappings/new/agent.xsl")).unwrap(), "<a/>");
    assert!(c.err("save_file", json!({ "path": "mappings/new/agent.xsl", "content": "<b/>" })).contains("overwrite: true"));
    assert_eq!(std::fs::read_to_string(dir.join("mappings/new/agent.xsl")).unwrap(), "<a/>", "not replaced");
    let r = c.ok("save_file", json!({ "path": "mappings/new/agent.xsl", "content": "<b/>", "overwrite": true }));
    assert_eq!(r["replaced"], true);
    assert_eq!(c.ok("read_file", json!({ "path": "mappings/new/agent.xsl" }))["content"], "<b/>");
    assert!(c.err("save_file", json!({ "path": "../escape.xsl", "content": "x" })).contains("not a project-relative path"));
    assert!(!dir.parent().unwrap().join("escape.xsl").exists());

    // Import an exported iFlow from outside the project; its steps come back; a second import needs overwrite.
    let iflow = std::fs::read_to_string(Path::new(env!("CARGO_MANIFEST_DIR"))
        .join("../../engine/core/src/test/resources/iflow/src/main/resources/scenarioflows/integrationflow/Order_Replication.iflw")).unwrap();
    let zip = std::env::temp_dir().join(format!("cpiwb-mcp-export-{}.zip", std::process::id()));
    std::fs::write(&zip, stored_zip(&[("src/main/resources/scenarioflows/integrationflow/Order Sync.iflw", &iflow)])).unwrap();
    let imp = c.ok("import_iflow", json!({ "zip": zip.to_string_lossy(), "name": "Order Sync" }));
    assert_eq!(imp["imported"], "iflows/Order_Sync", "{imp}");
    let steps = imp["steps"].as_array().unwrap();
    assert!(!steps.is_empty() && steps.iter().all(|s| s["iflow"] == "Order_Sync"), "only the imported iFlow's steps: {imp}");
    assert!(c.err("import_iflow", json!({ "zip": zip.to_string_lossy(), "name": "Order Sync" })).contains("exists already"));
    c.ok("import_iflow", json!({ "zip": zip.to_string_lossy(), "name": "Order Sync", "overwrite": true }));
    assert!(c.err("import_iflow", json!({ "zip": "missing.zip" })).contains("No file"));
    // A Value Mapping export becomes rows of the platform table, both ways (made-up values).
    let vm_zip = std::env::temp_dir().join(format!("cpiwb-mcp-vm-{}.zip", std::process::id()));
    std::fs::write(&vm_zip, stored_zip(&[("value_mapping.xml", "<vm version=\"2.0\"><group id=\"g\"><entry><agency>Red</agency>\
        <schema>Role</schema><value>R1</value></entry><entry><agency>Blue</agency><schema>Role</schema><value>B1</value></entry></group></vm>")])).unwrap();
    let vm = c.ok("import_iflow", json!({ "zip": vm_zip.to_string_lossy() }));
    assert_eq!(vm["kind"], "valueMapping", "{vm}");
    assert_eq!(vm["valueMappings"][0]["rows"], 2, "{vm}");
    assert!(vm["steps"].as_array().unwrap().is_empty());
    assert!(c.err("import_iflow", json!({ "zip": vm_zip.to_string_lossy() })).contains("exists already"));
    assert!(std::fs::read_to_string(dir.join("platform/value-mappings.json")).unwrap().contains("\"targetValue\":\"R1\""));
    let _ = std::fs::remove_file(&vm_zip);
    let info = c.ok("project_info", json!({}));
    assert!(info["iflowSteps"].as_array().unwrap().iter().any(|s| s["iflow"] == "Order_Sync"));

    engines.shutdown();
    let _ = std::fs::remove_file(&zip);
    let _ = std::fs::remove_dir_all(&dir);
}

/// Steps chained by runId, as the message flows through an iFlow, without copying bodies.
#[test]
fn runs_chain_by_id() {
    let Some(engines) = engines() else { return };
    let dir = project("chain");
    let server = Server::new(engines.clone(), Some(dir.clone()));
    let c = Call(&server);
    let id = |v: &Value| v["runId"].as_str().unwrap_or_else(|| panic!("no runId: {v}")).to_string();

    // XSLT with a header; its output carries the header on.
    let x = c.ok("run_xslt", json!({ "body": "<in/>", "headers": { "Sender": "ERP" },
        "mapping": "<xsl:stylesheet version='2.0' xmlns:xsl='http://www.w3.org/1999/XSL/Transform'>\
            <xsl:template match='/'><Order id='7'><Item>a</Item><Item>b</Item><Item>c</Item></Order></xsl:template></xsl:stylesheet>" }));
    let r1 = id(&x);
    // The XML Validator passes the message on.
    let v = c.ok("validate_xsd", json!({ "messageFrom": r1, "schema": "<xs:schema xmlns:xs='http://www.w3.org/2001/XMLSchema'>\
        <xs:element name='Order'><xs:complexType><xs:sequence><xs:element name='Item' type='xs:string' maxOccurs='unbounded'/>\
        </xs:sequence><xs:attribute name='id'/></xs:complexType></xs:element></xs:schema>" }));
    assert_eq!(v["valid"], true, "{v}");
    let r2 = id(&v);
    // Content Modifier on it: XPath from the body, the header from the XSLT run still there, one property given here.
    let cm = c.ok("run_flow_step", json!({ "messageFrom": r2, "properties": { "Channel": "WEB" },
        "step": { "type": "contentModifier", "config": { "headers": [{ "name": "OrderId", "type": "xpath", "value": "/Order/@id" }] } } }));
    let headers = cm["output"]["headers"].as_array().unwrap();
    assert!(headers.iter().any(|h| h["name"] == "OrderId" && h["value"] == "7"), "{cm}");
    assert!(headers.iter().any(|h| h["name"] == "Sender" && h["value"] == "ERP"), "headers flow on: {cm}");
    assert!(cm["output"]["properties"].as_array().unwrap().iter().any(|p| p["name"] == "Channel"), "{cm}");
    // A header given with the call replaces the one of the same name.
    let over = c.ok("run_groovy", json!({ "messageFrom": id(&cm), "headers": { "Sender": "Shop" },
        "script": "def processData(m) { m.setBody(m.getHeaders()['Sender'] + '/' + m.getHeaders()['OrderId']); m }" }));
    assert_eq!(over["output"]["body"], "Shop/7", "{over}");
    // Splitter, then Gather all its messages; one part on its own.
    let split = c.ok("run_flow_step", json!({ "messageFrom": id(&cm), "step": { "type": "splitter", "config": { "expression": "/Order/Item" } } }));
    let rs = id(&split);
    assert_eq!(split["messages"][1]["runId"], format!("{rs}/2"), "each part names its id: {split}");
    let part = c.ok("evaluate_xpath", json!({ "messageFrom": format!("{rs}/2"), "expression": "string(//Item)" }));
    assert_eq!(part["items"][0]["value"], "b", "{part}");
    assert!(part.get("runId").is_none(), "XPath produces no message");
    assert!(c.err("run_flow_step", json!({ "messageFrom": rs, "step": { "type": "filter", "config": { "expression": "/Item" } } }))
        .contains(&format!("{rs}/1, {rs}/2, {rs}/3")), "a split run names its parts");
    let g = c.ok("run_flow_step", json!({ "messagesFrom": rs, "step": { "type": "gather", "config": {} } }));
    assert_eq!(g["body"].as_str().unwrap().matches("<Item>").count(), 3, "{g}");
    // runtime: both keeps each runtime's output.
    let both = c.ok("run_xslt", json!({ "runtime": "both", "messageFrom": r1,
        "mapping": "<xsl:stylesheet version='2.0' xmlns:xsl='http://www.w3.org/1999/XSL/Transform'><xsl:output method='text'/>\
            <xsl:template match='/'><xsl:value-of select='system-property(\"xsl:product-version\")'/></xsl:template></xsl:stylesheet>" }));
    let rb = id(&both);
    assert_eq!(both["runs"][1]["runId"], format!("{rb}/{}", both["runs"][1]["runtime"].as_str().unwrap()), "{both}");
    assert_eq!(c.ok("read_file", json!({ "runId": format!("{rb}/saxon-he-12") }))["content"], "HE 12.9", "{both}");

    // A long body comes back shortened; read_file and save_file take it whole by id.
    let long = c.ok("run_groovy", json!({ "script": "def processData(m) { m.setBody('x' * 50000); m }" }));
    assert_eq!(long["output"]["body"].as_str().unwrap().len(), runs::PREVIEW_CHARS, "{}", long["output"]["bodyShortened"]);
    let rl = id(&long);
    assert_eq!(c.ok("read_file", json!({ "runId": rl }))["content"].as_str().unwrap().len(), 50000);
    c.ok("save_file", json!({ "path": "inputs/long.txt", "contentFrom": rl }));
    assert_eq!(std::fs::read_to_string(dir.join("inputs/long.txt")).unwrap().len(), 50000);

    // A failed run, an invalid message and an unknown id.
    let bad = c.ok("run_groovy", json!({ "script": "def processData(m) { throw new RuntimeException('no') }" }));
    assert!(bad["ok"] == false && bad.get("runId").is_none(), "{bad}");
    assert!(c.err("run_xslt", json!({ "messageFrom": "run-0000-9", "mappingPath": "mappings/order-to-salesorder.xsl" })).contains("Unknown run"));

    // A Router that raises an exception passes nothing on: ok false and no runId (review of 2026-11-14, F04).
    for config in [json!({ "routes": [], "throwException": true }),
        json!({ "routes": [{ "name": "a", "expressionType": "xml", "condition": "/a" }, { "name": "b", "expressionType": "nonxml", "condition": "1 = 1" }] }),
        json!({ "routes": [{ "name": "bad", "expressionType": "xml", "condition": "/a[" }] })] {
        let r = c.ok("run_flow_step", json!({ "body": "<a/>", "step": { "type": "router", "config": config } }));
        assert!(r["ok"] == false && r.get("runId").is_none(), "{config}: {r}");
    }
    let routed = c.ok("run_flow_step", json!({ "body": "<a/>", "step": { "type": "router", "config": {
        "routes": [{ "name": "yes", "expressionType": "xml", "condition": "/a" }] } } }));
    assert!(routed["ok"] == true && routed["runId"].is_string(), "a taken route passes the message on: {routed}");
    // A route's name may be empty or missing: it is taken all the same (re-review of 86beb74, N01).
    for route in [json!({ "name": "", "expressionType": "xml", "condition": "/a" }), json!({ "expressionType": "xml", "condition": "/a" })] {
        let r = c.ok("run_flow_step", json!({ "body": "<a/>", "step": { "type": "router", "config": {
            "routes": [route], "throwException": true } } }));
        assert!(r["ok"] == true && r["runId"].is_string() && r["taken"] == "Route 1", "{r}");
    }

    // The input is read once, before the run: a script that deletes its bodyPath file still gets its result
    // and runId, and the kept message is the one the step got (F03).
    std::fs::write(dir.join("inputs/gone.txt"), "before").unwrap();
    let gone = dir.join("inputs/gone.txt").to_string_lossy().replace('\\', "/");
    let deleted = c.ok("run_groovy", json!({ "bodyPath": "inputs/gone.txt",
        "script": format!("def processData(m) {{ new File('{gone}').delete(); m.setBody('done ' + m.getBody(String)); m }}") }));
    assert!(deleted["ok"] == true && deleted["output"]["body"] == "done before" && deleted["runId"].is_string(), "{deleted}");
    assert!(!dir.join("inputs/gone.txt").exists());

    // One long line (minified XML or JSON) is read whole in parts by characters (F10).
    let one = c.ok("run_groovy", json!({ "script": "def processData(m) { m.setBody('<a>' + ('x' * 300000) + '</a>'); m }" }));
    let first = c.ok("read_file", json!({ "runId": id(&one) }));
    let hint = first["lineCut"].as_str().unwrap().to_string();
    assert!(hint.contains("startChar 200000"), "{hint}");
    let rest = c.ok("read_file", json!({ "runId": id(&one), "startChar": 200000 }));
    assert_eq!(first["content"].as_str().unwrap().len() + rest["content"].as_str().unwrap().len(), 300007);
    assert!(rest["content"].as_str().unwrap().ends_with("x</a>") && rest.get("truncated").is_none(), "{}", rest["endChar"]);

    engines.shutdown();
    let _ = std::fs::remove_dir_all(&dir);
}

/// The choices taken as defaults are the same list on both sides, and only missing or null ones count.
#[test]
fn assumed_defaults_match_the_tab() {
    let ts = include_str!("../../../src/lib/flowcalls.ts");
    for (kind, keys) in flow::ASSUMED_DEFAULTS {
        let line = format!("  {kind}: [{}],", keys.iter().map(|k| format!("'{k}'")).collect::<Vec<_>>().join(", "));
        assert!(ts.contains(&line), "flowcalls.ts ASSUMED_DEFAULTS lacks: {line}");
    }
    let start = ts.find("export const ASSUMED_DEFAULTS").unwrap();
    let block = &ts[start..start + ts[start..].find("};").unwrap()];
    assert_eq!(block.lines().filter(|l| l.trim_end().ends_with("],")).count(), flow::ASSUMED_DEFAULTS.len(), "{block}");
    assert_eq!(flow::defaulted("messageDigest", &json!({ "algorithm": "SHA-256", "targetHeader": null })),
        ["canonicalization", "targetHeader"]);
    assert!(flow::defaulted("encoder", &json!({ "scheme": "gzipCompress" })).is_empty());
    assert!(flow::defaulted("router", &json!({})).is_empty());
}
