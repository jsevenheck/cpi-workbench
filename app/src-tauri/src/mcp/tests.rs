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
    assert_eq!(init["capabilities"], json!({ "tools": {}, "prompts": {} }));
    assert_eq!(init["serverInfo"]["name"], "cpi-workbench");
    assert_eq!(by_id(&msgs, 2)["result"]["protocolVersion"], LEGACY[0], "unknown version: the newest legacy one");
    assert_eq!(by_id(&msgs, 3)["result"], json!({}));
    let list = &by_id(&msgs, 4)["result"];
    assert!(list.get("resultType").is_none() && list.get("ttlMs").is_none(), "legacy results stay legacy: {list}");
    assert_eq!(list["tools"].as_array().unwrap().len(), 10);
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
    assert_eq!(names, ["project_info", "read_file", "run_xslt", "run_groovy", "validate_xsd", "evaluate_xpath", "run_flow_step",
        "save_file", "import_iflow", "reference"]);
    // Only tools that cannot run code are read-only: XSLT's java: calls and Groovy can (JavaExtensions).
    let read_only: Vec<&str> = tools.iter().filter(|t| t["annotations"]["readOnlyHint"] == true)
        .map(|t| t["name"].as_str().unwrap()).collect();
    assert_eq!(read_only, ["project_info", "read_file", "validate_xsd", "evaluate_xpath", "reference"]);
    // Writing tools say so, so that a client can ask before they run; tools that run code keep the defaults.
    for name in ["save_file", "import_iflow"] {
        let a = &tools.iter().find(|t| t["name"] == name).unwrap()["annotations"];
        assert_eq!(*a, json!({ "readOnlyHint": false, "destructiveHint": true, "idempotentHint": true, "openWorldHint": false }), "{name}");
    }
    for name in ["run_xslt", "run_groovy", "run_flow_step"] {
        assert!(tools.iter().find(|t| t["name"] == name).unwrap().get("annotations").is_none(), "{name}");
    }
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
    assert!(call_tool("save_file", json!({ "path": "a.xml" })).contains("Missing argument 'content'"));
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
    assert!(reference::topic("fidelity").unwrap().contains("## Groovy Script"));
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
    *engines.on_start.lock().unwrap() = Some(("project.open".into(), json!({ "dir": dir.to_string_lossy() })));
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
    let info = c.ok("project_info", json!({}));
    assert!(info["iflowSteps"].as_array().unwrap().iter().any(|s| s["iflow"] == "Order_Sync"));

    engines.shutdown();
    let _ = std::fs::remove_file(&zip);
    let _ = std::fs::remove_dir_all(&dir);
}
