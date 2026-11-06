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
    assert_eq!(init["capabilities"], json!({ "tools": {} }));
    assert_eq!(init["serverInfo"]["name"], "cpi-workbench");
    assert_eq!(by_id(&msgs, 2)["result"]["protocolVersion"], LEGACY[0], "unknown version: the newest legacy one");
    assert_eq!(by_id(&msgs, 3)["result"], json!({}));
    let list = &by_id(&msgs, 4)["result"];
    assert!(list.get("resultType").is_none() && list.get("ttlMs").is_none(), "legacy results stay legacy: {list}");
    assert_eq!(list["tools"].as_array().unwrap().len(), 6);
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
    assert_eq!(names, ["project_info", "run_xslt", "run_groovy", "validate_xsd", "evaluate_xpath", "run_flow_step"]);
    // Only tools that cannot run code are read-only: XSLT's java: calls and Groovy can (JavaExtensions).
    let read_only: Vec<&str> = tools.iter().filter(|t| t["annotations"]["readOnlyHint"] == true)
        .map(|t| t["name"].as_str().unwrap()).collect();
    assert_eq!(read_only, ["project_info", "validate_xsd", "evaluate_xpath"]);
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

// ------------------------------------------------------------------ with engines

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
    *engines.on_start.lock().unwrap() = Some(("project.open".into(), json!({ "dir": dir.to_string_lossy() })));
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

    let v = c.ok("validate_xsd", json!({ "schemaPath": "schemas/salesorder.xsd", "body": "<nope/>" }));
    assert_eq!(v["valid"], false, "{v}");

    let xp = c.ok("evaluate_xpath", json!({ "expression": "concat(/a/@x, '2')", "body": "<a x='1'/>" }));
    assert_eq!(xp["items"][0]["value"], "12", "{xp}");

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

/// After a timeout the engine restarts; it must have the project open again.
#[test]
fn a_restarted_engine_reopens_the_project() {
    let Some(engines) = engines() else { return };
    let dir = project("restart");
    *engines.on_start.lock().unwrap() = Some(("project.open".into(), json!({ "dir": dir.to_string_lossy() })));
    let server = Server::new(engines.clone(), Some(dir.clone()));
    let timeout = engines.call("groovy-1.x", "groovy.run", json!({ "timeoutMs": 300,
        "script": { "content": "def processData(m) { Thread.sleep(3000); m }" }, "input": { "body": "" } }));
    assert!(timeout.unwrap_err().starts_with("Timeout"));
    // A script given by path is read from the project by the restarted engine.
    let r = Call(&server).ok("run_groovy", json!({ "runtime": "groovy-1.x", "scriptPath": "scripts/enrich-order.groovy", "body": "<a/>" }));
    assert!(r["ok"].is_boolean(), "{r}");
    assert!(!r.to_string().contains("NoSuchFile"), "{r}");
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
    *engines.on_start.lock().unwrap() = Some(("project.open".into(), json!({ "dir": dir.to_string_lossy() })));
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
