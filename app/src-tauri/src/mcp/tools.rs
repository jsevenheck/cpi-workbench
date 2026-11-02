//! The MCP tools: one per tab of the app (XSLT, Groovy, XSD, XPath, Flow Steps) plus the project view,
//! making the same engine calls with the same inputs. The app's features lead: the tools add none of
//! their own. A wrong argument is an error message the model can act on.

use super::{flow, Server};
use serde_json::{json, Map, Value};

pub type Args = Map<String, Value>;

const XSLT_RUNTIMES: &[&str] = &["saxon-he-9.9", "saxon-he-12"];
const GROOVY_RUNTIMES: &[&str] = &["groovy-1.x", "groovy-2.x"];
/// Types a header or property value can have (SimExchange / TypedValue.TYPES).
const VALUE_TYPES: &[&str] = &["String", "Integer", "Long", "Boolean", "Double", "Float", "BigDecimal", "Date", "byte[]", "null"];

const RUNTIME_LABELS: &[(&str, &str)] = &[
    ("saxon-he-9.9", "XSLT, XSD, XPath and flow steps on Saxon-HE 9.9 (CPI's Saxon line; CPI runs Saxon-EE 9.9)"),
    ("saxon-he-12", "XSLT, XSD and XPath on Saxon-HE 12"),
    ("groovy-1.x", "Groovy Script 1.x: Groovy 2.4.21, Camel 2.24.3, Script API v1"),
    ("groovy-2.x", "Groovy Script 2.x: Groovy 4.0.29, Camel 3.14.7, Script API v2"),
];

// ------------------------------------------------------------------ definitions

fn values_schema(what: &str) -> Value {
    json!({
        "type": "object",
        "description": format!("{what} by name: a string value, or {{\"value\": \"...\", \"type\": \"Integer\"}} for another Java type."),
        "additionalProperties": {
            "anyOf": [
                { "type": "string" },
                { "type": "object", "properties": {
                    "value": { "type": "string" },
                    "type": { "type": "string", "enum": VALUE_TYPES } },
                  "required": ["value"], "additionalProperties": false }
            ]
        }
    })
}

/// Body, headers and properties of the incoming message.
fn message_properties(props: &mut Map<String, Value>, headers: bool) {
    props.insert("body".into(), json!({ "type": "string", "description": "The message body. Use body or bodyPath." }));
    props.insert("bodyPath".into(), json!({ "type": "string", "description": "Project-relative file whose content is the message body." }));
    if headers {
        props.insert("headers".into(), values_schema("Message headers"));
        props.insert("properties".into(), values_schema("Exchange properties"));
    }
}

fn runtime(choices: &[&str], both: bool, default: Option<&str>, description: &str) -> Value {
    let mut e: Vec<&str> = choices.to_vec();
    if both {
        e.push("both");
    }
    let mut s = json!({ "type": "string", "enum": e, "description": description });
    if let Some(d) = default {
        s["default"] = json!(d);
    }
    s
}

fn tool(name: &str, title: &str, description: &str, props: Map<String, Value>, required: &[&str], read_only: bool) -> Value {
    let mut t = json!({
        "name": name,
        "title": title,
        "description": description,
        "inputSchema": { "type": "object", "properties": props, "required": required, "additionalProperties": false },
    });
    // XSD and XPath read only project files and cannot write. XSLT is not among them: reflexive java: calls run
    // any public JDK method (JavaExtensions). Groovy and Camel Simple run code without a sandbox. Tools without
    // hints get the protocol's defaults, so clients assume the worst.
    if read_only {
        t["annotations"] = json!({ "readOnlyHint": true, "openWorldHint": false });
    }
    t
}

fn obj(pairs: Vec<(&str, Value)>) -> Map<String, Value> {
    pairs.into_iter().map(|(k, v)| (k.to_string(), v)).collect()
}

/// The tool list, always in this order.
pub fn definitions() -> Vec<Value> {
    let ns = json!({ "type": "object", "additionalProperties": { "type": "string" },
        "description": "Namespace prefixes used in the XPath, prefix → URI." });

    let mut xslt = obj(vec![
        ("mappingPath", json!({ "type": "string", "description": "Project-relative .xsl file. Use mappingPath or mapping." })),
        ("mapping", json!({ "type": "string", "description": "The XSLT source, if it is not in a file. xsl:include/import resolve against the project folder." })),
    ]);
    message_properties(&mut xslt, true);
    xslt.insert("runtime".into(), runtime(XSLT_RUNTIMES, true, Some("saxon-he-9.9"),
        "Default saxon-he-9.9, CPI's Saxon line (12 if 9.9 is not installed); both runs 9.9 and 12 side by side (the app's runtime comparison)."));

    let mut groovy = obj(vec![
        ("scriptPath", json!({ "type": "string", "description": "Project-relative .groovy file. Use scriptPath or script." })),
        ("script", json!({ "type": "string", "description": "The script source, if it is not in a file." })),
        ("function", json!({ "type": "string", "default": "processData", "description": "The function the step calls (Script Function)." })),
    ]);
    message_properties(&mut groovy, true);
    groovy.insert("bodyType".into(), json!({ "type": "string", "enum": ["String", "byte[]", "InputStream"], "default": "String",
        "description": "The Java type of the incoming body, as the Groovy tab's Body type." }));
    groovy.insert("runtime".into(), runtime(GROOVY_RUNTIMES, true, None,
        "groovy-1.x (Script API v1) or groovy-2.x (v2). Default: from the script's imports (com.sap.it.script.v2.api → 2.x), else 1.x. both runs the two."));

    let mut xsd = obj(vec![
        ("schemaPath", json!({ "type": "string", "description": "Project-relative .xsd file. Use schemaPath or schema." })),
        ("schema", json!({ "type": "string", "description": "The XSD source, if it is not in a file." })),
    ]);
    message_properties(&mut xsd, false);
    xsd.insert("runtime".into(), runtime(XSLT_RUNTIMES, false, Some("saxon-he-9.9"), "The XML engine."));

    let mut xpath = obj(vec![
        ("expression", json!({ "type": "string", "description": "The XPath." })),
        ("version", json!({ "type": "string", "enum": ["1.0", "3.1"], "default": "3.1" })),
        ("namespaces", ns.clone()),
    ]);
    message_properties(&mut xpath, false);
    xpath.insert("runtime".into(), runtime(XSLT_RUNTIMES, false, Some("saxon-he-9.9"), "The XML engine."));

    let mut step = obj(vec![
        ("iflowStep", json!({ "type": "string", "description": "Name or id of a step that project_info lists under iflowSteps. Use iflowStep or step." })),
        ("iflow", json!({ "type": "string", "description": "The iFlow of iflowStep, when several iFlows have a step with that name or id." })),
        ("step", json!({ "type": "object", "description": "A step given directly (as edited in the Flow Steps tab): {\"type\": \"contentModifier\"|\"filter\"|\"router\"|\"splitter\"|\"gather\", \"config\": {...}}, config shaped as project_info shows it for iFlow steps.",
            "properties": {
                "type": { "type": "string", "enum": ["contentModifier", "filter", "router", "splitter", "gather"] },
                "config": { "type": "object" } },
            "required": ["type", "config"], "additionalProperties": false })),
        ("messages", json!({ "type": "array", "items": { "type": "string" },
            "description": "Gather only: the bodies to gather, e.g. the messages a splitter returned." })),
        ("namespaces", ns),
    ]);
    message_properties(&mut step, true);
    step.insert("camel".into(), runtime(GROOVY_RUNTIMES, false, Some("groovy-2.x"),
        "The Camel line for Camel Simple in the step."));

    vec![
        tool("project_info", "Project, runtimes and iFlow steps",
            "The open project: its files, the steps of the iFlows under iflows/ (with each flow step's configuration), \
             and the installed runtimes. Call this first.",
            Map::new(), &[], true),
        tool("run_xslt", "Run an XSLT mapping",
            "Runs an XSLT mapping like CPI's XSLT Mapping step: headers and properties are xsl:param values, the result \
             is the new body. Returns the output message, xsl:message output and diagnostics.",
            xslt, &[], false),
        tool("run_groovy", "Run a Groovy script",
            "Runs a Groovy script like CPI's Groovy Script step with the clean-room Script API (Message, MessageLog, \
             ITApiFactory with the project's platform/ files). Returns the output message, log entries and diagnostics. \
             The script runs without a sandbox.",
            groovy, &[], false),
        tool("validate_xsd", "Validate XML against an XSD",
            "Validates the body against an XML schema like CPI's XML Validator. Returns valid and the errors with lines.",
            xsd, &[], true),
        tool("evaluate_xpath", "Evaluate an XPath",
            "Evaluates an XPath on the body, like the XPath tab. Returns the value or the selected nodes.",
            xpath, &["expression"], true),
        tool("run_flow_step", "Run a flow step",
            "Runs a Content Modifier, Filter, Router, General or Iterating Splitter, or Gather step on a message, like the \
             Flow Steps tab, from an iFlow of the project or from a config given here. Returns the output message(s), or \
             for a Router each route's result and the route taken. For an iFlow's script, XSLT or XML Validator step, \
             use run_groovy, run_xslt or validate_xsd with the step's file, function and profile from project_info.",
            step, &[], false),
    ]
}

pub fn exists(name: &str) -> bool {
    definitions().iter().any(|t| t["name"] == name)
}

// ------------------------------------------------------------------ calls

pub fn call(server: &Server, name: &str, args: &Args) -> Result<Value, String> {
    check_args(name, args)?;
    match name {
        "project_info" => project_info(server),
        "run_xslt" => run_xslt(server, args),
        "run_groovy" => run_groovy(server, args),
        "validate_xsd" => validate_xsd(server, args),
        "evaluate_xpath" => evaluate_xpath(server, args),
        "run_flow_step" => flow::run(server, args),
        _ => Err(format!("Unknown tool: {name}")),
    }
}

/// Unknown arguments, missing required ones, wrong JSON types and values outside an enum, checked
/// against the tool's own input schema (top level; nested values are checked where they are read).
fn check_args(name: &str, args: &Args) -> Result<(), String> {
    let defs = definitions();
    let def = defs.iter().find(|t| t["name"] == name).ok_or_else(|| format!("Unknown tool: {name}"))?;
    let schema = &def["inputSchema"];
    let props = schema["properties"].as_object().cloned().unwrap_or_default();
    for (k, v) in args {
        let Some(p) = props.get(k) else {
            let known: Vec<&String> = props.keys().collect();
            return Err(format!("Unknown argument '{k}'. {name} takes: {known:?}."));
        };
        let ok = match p["type"].as_str() {
            Some("string") => v.is_string(),
            Some("object") => v.is_object(),
            Some("array") => v.as_array().is_some_and(|a| a.iter().all(Value::is_string)),
            _ => true,
        };
        if !ok {
            return Err(format!("Argument '{k}' must be of type {}.", p["type"].as_str().unwrap_or("?")));
        }
        if let (Some(e), Some(s)) = (p["enum"].as_array(), v.as_str()) {
            if !e.iter().any(|x| x == s) {
                return Err(format!("Argument '{k}' must be one of {}.", Value::Array(e.clone())));
            }
        }
    }
    for r in schema["required"].as_array().into_iter().flatten() {
        let r = r.as_str().unwrap_or_default();
        if !args.contains_key(r) {
            return Err(format!("Missing argument '{r}'."));
        }
    }
    Ok(())
}

pub fn str_arg<'a>(args: &'a Args, key: &str) -> Option<&'a str> {
    args.get(key).and_then(Value::as_str)
}

/// The default runtime of a kind: `preferred` when installed, else the other one, as the app falls back
/// at start to the first installed runtime. The XML engine for files and flow steps is 9.9 (CPI's Saxon line)
/// when installed.
pub fn installed_or<'a>(server: &Server, preferred: &'a str, kind: &[&'a str]) -> &'a str {
    if server.engines().locate(preferred).is_some() {
        return preferred;
    }
    kind.iter().copied().find(|p| server.engines().locate(p).is_some()).unwrap_or(preferred)
}

pub fn xml_runtime(server: &Server) -> &'static str {
    installed_or(server, "saxon-he-9.9", XSLT_RUNTIMES)
}

pub fn camel_runtime(server: &Server) -> &'static str {
    installed_or(server, "groovy-2.x", GROOVY_RUNTIMES)
}

/// Any installed engine that can read project files.
fn file_runtime(server: &Server) -> Result<&'static str, String> {
    ["saxon-he-9.9", "saxon-he-12", "groovy-1.x", "groovy-2.x"].into_iter()
        .find(|p| server.engines().locate(p).is_some())
        .ok_or_else(|| "No engine is installed.".to_string())
}

pub fn engine_call(server: &Server, runtime: &str, method: &str, params: Value) -> Result<Value, String> {
    server.engines().call(runtime, method, params)
}

fn require_project(server: &Server) -> Result<(), String> {
    match server.project() {
        Some(_) => Ok(()),
        None => Err("No project is open; start the server with --project <folder>.".into()),
    }
}

/// A project file, read by the engine, which refuses paths outside the project.
pub fn read_file(server: &Server, path: &str) -> Result<String, String> {
    require_project(server)?;
    let r = engine_call(server, file_runtime(server)?, "file.read", json!({ "path": path }))?;
    Ok(r["content"].as_str().unwrap_or_default().to_string())
}

/// Exactly one of `inline` and `path`, or neither when `optional`.
fn one_of<'a>(args: &'a Args, inline: &str, path: &str, optional: bool) -> Result<(Option<&'a str>, Option<&'a str>), String> {
    match (str_arg(args, inline), str_arg(args, path)) {
        (Some(_), Some(_)) => Err(format!("Give {inline} or {path}, not both.")),
        (None, None) if !optional => Err(format!("Give {inline} or {path}.")),
        (i, p) => Ok((i, p)),
    }
}

/// Headers or properties as the engines take them: [{name, value, type}].
fn value_rows(args: &Args, key: &str) -> Result<Value, String> {
    let mut rows = Vec::new();
    for (name, v) in args.get(key).and_then(Value::as_object).into_iter().flatten() {
        let (value, ty) = match v {
            Value::String(s) => (s.clone(), "String".to_string()),
            Value::Object(o) => {
                let value = o.get("value").and_then(Value::as_str)
                    .ok_or_else(|| format!("{key}.{name}: give a string, or an object with a string 'value'."))?;
                let ty = o.get("type").and_then(Value::as_str).unwrap_or("String");
                if !VALUE_TYPES.contains(&ty) {
                    return Err(format!("{key}.{name}: type must be one of {VALUE_TYPES:?}."));
                }
                (value.to_string(), ty.to_string())
            }
            _ => return Err(format!("{key}.{name}: give a string, or an object with a string 'value'.")),
        };
        rows.push(json!({ "name": name, "value": value, "type": ty }));
    }
    Ok(Value::Array(rows))
}

/// The incoming message: {body, headers, properties}.
pub fn message(server: &Server, args: &Args) -> Result<Value, String> {
    let body = match one_of(args, "body", "bodyPath", true)? {
        (Some(b), _) => b.to_string(),
        (_, Some(p)) => read_file(server, p)?,
        _ => String::new(),
    };
    Ok(json!({ "body": body, "headers": value_rows(args, "headers")?, "properties": value_rows(args, "properties")? }))
}

/// `{content, path}` for a mapping, script or schema: the engine loads a path itself (so includes and
/// imports resolve next to the file); inline sources get a default name in the project folder.
fn source(args: &Args, inline: &str, path: &str, default_name: &str) -> Result<Value, String> {
    Ok(match one_of(args, inline, path, false)? {
        (Some(c), _) => json!({ "content": c, "path": default_name }),
        (_, Some(p)) => json!({ "path": p }),
        _ => unreachable!(),
    })
}

/// Runs on one runtime, or on both (the app's runtime comparison).
fn on_runtimes(server: &Server, runtimes: &[&str], method: &str, params: Value) -> Result<Value, String> {
    if runtimes.len() == 1 {
        return engine_call(server, runtimes[0], method, params);
    }
    let runs: Vec<Value> = runtimes.iter().map(|rt| match engine_call(server, rt, method, params.clone()) {
        Ok(r) => json!({ "runtime": rt, "result": r }),
        Err(e) => json!({ "runtime": rt, "error": e }),
    }).collect();
    Ok(json!({ "runs": runs }))
}

fn pick<'a>(args: &'a Args, key: &str, choices: &'a [&'a str], default: &'a str) -> Vec<&'a str> {
    match str_arg(args, key) {
        Some("both") => choices.to_vec(),
        Some(r) => vec![r],
        None => vec![default],
    }
}

fn project_info(server: &Server) -> Result<Value, String> {
    let runtimes: Vec<Value> = RUNTIME_LABELS.iter()
        .map(|(id, label)| json!({ "id": id, "label": label, "installed": server.engines().locate(id).is_some() }))
        .collect();
    let mut info = json!({ "project": server.project().map(|p| p.to_string_lossy().to_string()), "runtimes": runtimes });
    if server.project().is_none() {
        return Ok(info);
    }
    let rt = file_runtime(server)?;
    let listing = engine_call(server, rt, "project.list", json!({}))?;
    const MAX_FILES: usize = 1000;
    let files: Vec<Value> = listing["files"].as_array().into_iter().flatten()
        .filter(|f| f["dir"] != true).map(|f| f["path"].clone()).collect();
    if files.len() > MAX_FILES {
        info["filesTruncated"] = json!(files.len());
    }
    info["files"] = Value::Array(files.into_iter().take(MAX_FILES).collect());
    info["iflowSteps"] = engine_call(server, rt, "iflow.steps", json!({}))?["steps"].clone();
    Ok(info)
}

fn run_xslt(server: &Server, args: &Args) -> Result<Value, String> {
    let params = json!({
        "xslt": source(args, "mapping", "mappingPath", "mapping.xsl")?,
        "input": message(server, args)?,
    });
    on_runtimes(server, &pick(args, "runtime", XSLT_RUNTIMES, xml_runtime(server)), "xslt.run", params)
}

fn run_groovy(server: &Server, args: &Args) -> Result<Value, String> {
    let script = source(args, "script", "scriptPath", "script.groovy")?;
    // Without a runtime, the script's imports decide, as when the app opens a script.
    let detected = match (str_arg(args, "runtime"), str_arg(args, "script")) {
        (Some(_), _) => "groovy-1.x", // not used
        (None, Some(text)) => installed_or(server, api_runtime(text), GROOVY_RUNTIMES),
        (None, None) => installed_or(server, api_runtime(&read_file(server, str_arg(args, "scriptPath").unwrap_or_default())?), GROOVY_RUNTIMES),
    };
    let params = json!({
        "script": script,
        "function": str_arg(args, "function").unwrap_or("processData"),
        "input": message(server, args)?,
        "options": { "bodyType": str_arg(args, "bodyType").unwrap_or("String") },
    });
    let mut r = on_runtimes(server, &pick(args, "runtime", GROOVY_RUNTIMES, detected), "groovy.run", params)?;
    if str_arg(args, "runtime").is_none() {
        r["runtime"] = json!(detected);
    }
    Ok(r)
}

fn api_runtime(script: &str) -> &'static str {
    if script.contains("com.sap.it.script.v2.api") { "groovy-2.x" } else { "groovy-1.x" }
}

fn validate_xsd(server: &Server, args: &Args) -> Result<Value, String> {
    let m = message(server, args)?;
    let params = json!({ "schema": source(args, "schema", "schemaPath", "schema.xsd")?, "input": { "body": m["body"] } });
    engine_call(server, str_arg(args, "runtime").unwrap_or(xml_runtime(server)), "xsd.validate", params)
}

fn evaluate_xpath(server: &Server, args: &Args) -> Result<Value, String> {
    let params = json!({
        "expression": str_arg(args, "expression").unwrap_or_default(),
        "version": str_arg(args, "version").unwrap_or("3.1"),
        "namespaces": args.get("namespaces").cloned().unwrap_or(json!({})),
        "input": { "body": message(server, args)?["body"] },
    });
    engine_call(server, str_arg(args, "runtime").unwrap_or(xml_runtime(server)), "xpath.eval", params)
}
