//! Flow steps for the MCP server. The Flow Steps tab leads (`app/src/lib/flowcalls.ts`); this follows it call for
//! call, so that both give the same result. The fixtures in `testdata/flow-parity` hold the calls both must make, and
//! `flow_parity` in `tests.rs` and `app/tests/flowparity.test.mts` replay them without engines.
//! * XPath (Content Modifier rows, XML Router conditions, Filter, Splitter, Gather), the converters, the Encoder,
//!   the Decoder and the Message Digest run on the XML engine, 9.9 when installed; headers and properties are XPath variables (F39).
//! * Camel Simple and the Content Modifier run on the Camel of a Groovy engine.

use super::tools::{self, engine_call, str_arg, Args};
use super::Server;
use serde_json::{json, Value};

/// An engine call: runtime, method, parameters.
pub(super) type EngineCall<'a> = &'a dyn Fn(&str, &str, Value) -> Result<Value, String>;

/// Everything a step reads, taken when it starts (the tab's `FlowRun`).
pub(super) struct Ctx<'a> {
    pub call: EngineCall<'a>,
    pub xml: &'a str,
    pub camel: &'a str,
    pub namespaces: Value,
    pub input: Value,
}

pub fn run(server: &Server, args: &Args) -> Result<Value, String> {
    let step = match (str_arg(args, "iflowStep"), args.get("step")) {
        (Some(_), Some(_)) => return Err("Give iflowStep or step, not both.".into()),
        (Some(name), None) => iflow_step(server, name, str_arg(args, "iflow"))?,
        (None, Some(_)) if args.contains_key("iflow") => return Err("iflow goes with iflowStep.".into()),
        (None, Some(s)) => s.clone(),
        (None, None) => return Err("Give iflowStep (from project_info) or step.".into()),
    };
    let kind = step["type"].as_str().unwrap_or_default().to_string();
    if step.get("file").is_some() {
        return Err(format!("'{}' is a {kind} step with a file: run it with run_groovy, run_xslt or validate_xsd \
            and the file, function and profile that project_info lists.", s(&step, "name")));
    }
    let config = step.get("config").cloned().unwrap_or(json!({}));
    if !config.is_object() {
        return Err("step.config must be an object.".into());
    }
    if args.contains_key("step") {
        if let Some(keys) = config_keys(&kind) {
            let unknown: Vec<&str> = config.as_object().unwrap().keys().map(String::as_str)
                .filter(|k| !keys.contains(k)).collect();
            if !unknown.is_empty() {
                return Err(format!("Unknown {kind} config key(s): {}. The keys are: {}.", unknown.join(", "), keys.join(", ")));
            }
        }
    }
    let call = |runtime: &str, method: &str, params: Value| engine_call(server, runtime, method, params);
    let ctx = Ctx {
        call: &call,
        xml: tools::xml_runtime(server),
        camel: tools::camel_runtime(server),
        namespaces: args.get("namespaces").cloned().unwrap_or(json!({})),
        input: tools::message(server, args)?,
    };
    if kind != "gather" && (args.contains_key("messages") || args.contains_key("messagesFrom")) {
        return Err("messages and messagesFrom are for a Gather step; the other steps take body, bodyPath or messageFrom.".into());
    }
    let messages = if kind == "gather" { Some(gather_messages(server, args)?) } else { None };
    let mut result = run_step(&ctx, &kind, &config, messages)?;
    result["step"] = json!({ "type": kind, "name": step.get("name"), "config": config });
    // What the import could not read from the iFlow (a Message Digest's filter, F62).
    if let Some(note) = step.get("note").filter(|n| n.is_string()) {
        result["step"]["note"] = note.clone();
    }
    // Choices the iFlow step leaves out ran with the default; whether CPI does the same is not known.
    if args.contains_key("iflowStep") {
        let missing = defaulted(&kind, &config);
        if !missing.is_empty() {
            result["step"]["defaulted"] = json!(missing);
            result["step"]["defaultedNote"] = json!(format!("Not in the iFlow, so the default was used (an assumption:                 whether CPI does the same is not known): {}.", missing.join(", ")));
        }
    }
    Ok(result)
}

/// The choices an iFlow step may leave out, which then take the default (`ASSUMED_DEFAULTS` in `flowcalls.ts`).
pub(super) const ASSUMED_DEFAULTS: [(&str, &[&str]); 6] = [
    ("filter", &["valueType"]),
    ("splitter", &["splitter", "expressionType"]),
    ("gather", &["messageType", "algorithm"]),
    ("encoder", &["scheme"]),
    ("decoder", &["scheme"]),
    ("messageDigest", &["canonicalization", "algorithm", "targetHeader"]),
];

/// The settings of `ASSUMED_DEFAULTS` that the config leaves out or sets null.
pub(super) fn defaulted(kind: &str, config: &Value) -> Vec<&'static str> {
    ASSUMED_DEFAULTS.iter().find(|(k, _)| *k == kind).map(|(_, keys)| keys.iter().copied()
        .filter(|key| config.get(*key).is_none_or(Value::is_null)).collect()).unwrap_or_default()
}

/// Runs a step with its config; a Gather gets its messages. No engine is reached except through `ctx.call`.
pub(super) fn run_step(ctx: &Ctx, kind: &str, config: &Value, messages: Option<Value>) -> Result<Value, String> {
    match kind {
        "contentModifier" => content_modifier(ctx, config),
        "filter" => filter(ctx, config),
        "router" => router(ctx, config),
        "splitter" => splitter(ctx, config),
        "gather" => gather(ctx, config, messages.unwrap_or(json!([]))),
        "converter" => converter(ctx, config),
        "encoder" => (ctx.call)(ctx.xml, "encode.run", json!({
            "scheme": or(config, "scheme", "base64Encode"), "input": ctx.input })),
        "decoder" => (ctx.call)(ctx.xml, "decode.run", json!({
            "scheme": or(config, "scheme", "base64Decode"), "input": ctx.input })),
        "messageDigest" => message_digest(ctx, config),
        "" => Err("The step has no type.".into()),
        other => Err(format!("UnsupportedInSimulator: the step type '{other}' (simulated: contentModifier, filter, \
            router, splitter, gather, converter, encoder, decoder, messageDigest).")),
    }
}

/// The settings of each step type in the Flow Steps tab (`flowStepDefaults` in `flowcalls.ts`).
fn config_keys(kind: &str) -> Option<&'static [&'static str]> {
    Some(match kind {
        "contentModifier" => &["headers", "properties", "bodyType", "body"],
        "filter" => &["expression", "valueType"],
        "router" => &["routes", "throwException"],
        "splitter" => &["splitter", "expressionType", "expression", "grouping", "streaming"],
        "gather" => &["messageType", "algorithm", "sourceXPath", "targetXPath"],
        "converter" => &CONVERTER_KEYS,
        "encoder" | "decoder" => &["scheme"],
        "messageDigest" => &["filter", "canonicalization", "algorithm", "targetHeader"],
        _ => return None,
    })
}

/// The converter's settings (`converterDefaults` in `flowcalls.ts`); `ConverterStep.java` reads them.
const CONVERTER_KEYS: [&str; 23] = ["conversion", "useNamespaces", "separator", "namespaceMapping", "addRootElement",
    "rootElementName", "rootNamespace", "suppressRoot", "streaming", "streamingElements", "arrayPaths", "outputEncoding",
    "sourcePath", "fieldSeparator", "csvHeader", "includeParent", "parentPath", "includeAttributes", "schema", "targetPath",
    "recordMarker", "firstLineHeader", "headerMapping"];

/// A step of the project's iFlows, by name or id.
fn iflow_step(server: &Server, name: &str, iflow: Option<&str>) -> Result<Value, String> {
    let r = engine_call(server, tools::xml_runtime(server), "iflow.steps", json!({}))?;
    let steps: Vec<Value> = r["steps"].as_array().cloned().unwrap_or_default().into_iter()
        .filter(|s| iflow.is_none_or(|f| s["iflow"] == f)).collect();
    let found: Vec<&Value> = steps.iter().filter(|s| s["id"] == name).collect();
    let found = if found.is_empty() { steps.iter().filter(|s| s["name"] == name).collect() } else { found };
    match found.as_slice() {
        [one] => Ok((*one).clone()),
        [] => Err(format!("No iFlow step named '{name}'. project_info lists the steps under iflowSteps.")),
        _ => Err(format!("Several iFlow steps are named '{name}' ({}); give the step's iflow too.",
            found.iter().map(|s| s["iflow"].as_str().unwrap_or("?")).collect::<Vec<_>>().join(", "))),
    }
}

fn s<'a>(v: &'a Value, key: &str) -> &'a str {
    v.get(key).and_then(Value::as_str).unwrap_or("")
}

/// The value, or the tab's default when the config leaves it out or sets null (`??` in the tab); an empty
/// string stays empty, as in the tab.
fn or<'a>(v: &'a Value, key: &str, default: &'a str) -> &'a str {
    match v.get(key) {
        None | Some(Value::Null) => default,
        Some(x) => x.as_str().unwrap_or(""),
    }
}

fn first_message(r: &Value, fallback: &str) -> String {
    r["diagnostics"][0]["message"].as_str().unwrap_or(fallback).to_string()
}

impl Ctx<'_> {
    fn xpath_value(&self, expression: &str, as_: &str, input: &Value) -> Result<String, String> {
        let r = (self.call)(self.xml, "xpath.eval", json!({
            "expression": expression, "version": "3.1", "as": as_, "namespaces": self.namespaces, "input": input,
        }))?;
        if r["ok"] != true {
            return Err(first_message(&r, "XPath failed"));
        }
        Ok(r["value"].as_str().unwrap_or_default().to_string())
    }

    fn simple(&self, expression: &str, mode: &str) -> Result<Value, String> {
        let r = (self.call)(self.camel, "simple.eval",
            json!({ "expression": expression, "mode": mode, "input": self.input }))?;
        if r["ok"] != true {
            return Err(first_message(&r, "Camel Simple failed"));
        }
        Ok(r)
    }
}

/// A row as the Flow Steps tab keeps it (`settingsFromIflowStep`).
fn cm_row(r: &Value) -> Value {
    json!({
        "action": if s(r, "action") == "Delete" { "Delete" } else { "Create" },
        "name": s(r, "name"),
        "type": or(r, "type", "constant"),
        "value": s(r, "value"),
        "datatype": s(r, "datatype"),
        "default": s(r, "default"),
    })
}

/// XPath rows read the incoming body, which the step changes only after all header and property rows.
/// Rows run top to bottom (SAP), so an XPath that uses $name sees the headers and properties above it.
fn content_modifier(ctx: &Ctx, config: &Value) -> Result<Value, String> {
    let mut continuation: Option<String> = None;
    let start_from = |p: &mut Value, token: &Option<String>| {
        if let Some(token) = token {
            p["continuation"] = json!(token);
        } else {
            p["input"] = ctx.input.clone();
        }
    };
    let result = (|| {
        let mut pending = json!({ "headers": [], "properties": [] });
        for table in ["headers", "properties"] {
            for r in config[table].as_array().into_iter().flatten() {
                let mut row = cm_row(r);
                if row["action"] == "Create" && row["type"] == "xpath" {
                    let value = s(&row, "value");
                    let mut input = ctx.input.clone();
                    let has_pending = pending.as_object().unwrap().values().any(|v| !v.as_array().unwrap().is_empty());
                    if has_pending {
                        let variables = value.contains('$');
                        let mut p = std::mem::replace(&mut pending, json!({ "headers": [], "properties": [] }));
                        start_from(&mut p, &continuation);
                        p["variables"] = json!(variables);
                        let st = (ctx.call)(ctx.camel, "contentModifier.advance", p)?;
                        continuation = None; // the engine consumed the previous token
                        if st["ok"] != true {
                            return Ok(st);
                        }
                        continuation = Some(st["continuation"].as_str()
                            .ok_or("Content Modifier did not return a continuation.")?.to_string());
                        if variables {
                            input["headers"] = st["variables"]["headers"].clone();
                            input["properties"] = st["variables"]["properties"].clone();
                        }
                    }
                    let as_ = if s(&row, "datatype").starts_with("org.w3c.dom.") { "nodes" } else { "string" };
                    let v = ctx.xpath_value(value, as_, &input).map_err(|e| format!("XPath of {}: {e}", s(&row, "name")))?;
                    row["resolved"] = json!(v);
                }
                pending[table].as_array_mut().unwrap().push(row);
            }
        }
        let body_type = if s(config, "bodyType") == "expression" { "expression" } else { "constant" };
        start_from(&mut pending, &continuation);
        pending["bodyType"] = json!(body_type);
        pending["body"] = json!(s(config, "body"));
        let r = (ctx.call)(ctx.camel, "contentModifier.run", pending)?;
        continuation = None;
        Ok(r)
    })();
    // A failed XPath must release the paused exchange without hiding the original failure.
    if let Some(token) = continuation {
        let _ = (ctx.call)(ctx.camel, "contentModifier.discard", json!({ "continuation": token }));
    }
    result
}

fn router(ctx: &Ctx, config: &Value) -> Result<Value, String> {
    let routes = config["routes"].as_array().cloned().unwrap_or_default();
    let mut out: Vec<Value> = routes.iter().map(|r| json!({ "name": r["name"],
        "status": if r["default"] == true { "default" } else { "not evaluated" } })).collect();
    let kinds: std::collections::BTreeSet<&str> = routes.iter().filter(|r| r["default"] != true)
        .map(|r| s(r, "expressionType")).collect();
    if kinds.len() > 1 {
        // ok: false wherever CPI raises an exception: the message goes no further, so it gets no runId either.
        return Ok(json!({ "ok": false, "routes": out, "diagnostics": [],
            "taken": "Error: the routes mix XML and Non-XML conditions; SAP: \"the evaluation fails\" (Define Router)." }));
    }
    // Whether a route was taken, apart from its name: a route's name may be empty.
    let mut taken = String::new();
    let mut decided = false;
    let mut raised = false;
    for (i, r) in routes.iter().enumerate() {
        if r["default"] == true {
            continue;
        }
        let evaluated = if s(r, "expressionType") == "xml" {
            ctx.xpath_value(s(r, "condition"), "boolean", &ctx.input).map(|v| (v, Value::Null))
        } else {
            ctx.simple(s(r, "condition"), "condition").map(|v| (s(&v, "value").to_string(), v["evaluated"].clone()))
        };
        match evaluated {
            Ok((v, camel)) => {
                out[i]["status"] = json!(if v == "true" { "true" } else { "false" });
                if !camel.is_null() {
                    out[i]["camel"] = camel;
                }
            }
            Err(e) => {
                out[i]["status"] = json!("error");
                out[i]["message"] = json!(e);
                taken = "Error: the route condition failed, so CPI raises an exception.".into();
                raised = true;
                decided = true;
                break;
            }
        }
        if out[i]["status"] == "true" {
            taken = match s(r, "name") { "" => format!("Route {}", i + 1), n => n.to_string() };
            decided = true;
            break;
        }
    }
    if !decided {
        let d = routes.iter().find(|r| r["default"] == true);
        raised = config["throwException"] == true;
        taken = if raised {
            "No route matched: Throw Exception is set, so the Router raises an exception.".into()
        } else if let Some(d) = d {
            format!("{} (default route)", s(d, "name"))
        } else {
            "No route matched and there is no default route.".into()
        };
    }
    Ok(json!({ "ok": !raised, "routes": out, "taken": taken, "diagnostics": [] }))
}

/// XPath and grouping may refer to a header or property (SAP); Camel Simple resolves the reference first.
fn splitter(ctx: &Ctx, config: &Value) -> Result<Value, String> {
    let resolve = |v: String| -> Result<String, String> {
        if v.contains("${") { Ok(s(&ctx.simple(&v, "expression")?, "value").to_string()) } else { Ok(v) }
    };
    let grouping = match &config["grouping"] {
        Value::Null => String::new(),
        Value::String(g) => g.clone(),
        other => other.to_string(),
    };
    (ctx.call)(ctx.xml, "split.run", json!({
        "splitter": or(config, "splitter", "general"), "expressionType": or(config, "expressionType", "xpath"),
        "expression": resolve(s(config, "expression").to_string())?, "grouping": resolve(grouping)?,
        "streaming": config["streaming"] != false,
        "namespaces": ctx.namespaces, "input": body_only(&ctx.input),
    }))
}

/// The message's body alone, text or bytes, as the tab's splitter sends it.
pub(super) fn body_only(input: &Value) -> Value {
    match input.get("bodyBase64") {
        Some(b) => json!({ "bodyBase64": b }),
        None => json!({ "body": input["body"] }),
    }
}

fn filter(ctx: &Ctx, config: &Value) -> Result<Value, String> {
    let expression = resolve_simple_functions(s(config, "expression"), |e| Ok(s(&ctx.simple(e, "expression")?, "value").to_string()))?;
    (ctx.call)(ctx.xml, "filter.run", json!({
        "expression": expression, "valueType": or(config, "valueType", "nodelist"),
        "namespaces": ctx.namespaces, "input": ctx.input,
    }))
}

/// A Gather's messages: given, or a Splitter run's (`messagesFrom`).
fn gather_messages(server: &Server, args: &Args) -> Result<Value, String> {
    Ok(match (args.get("messages"), args.get("messagesFrom")) {
        (Some(_), Some(_)) => return Err("Give messages or messagesFrom, not both.".into()),
        (Some(m), None) => m.clone(),
        (None, Some(_)) => {
            let ms = tools::messages_from(server, args)?;
            if ms.iter().any(|m| m.body_base64.is_some()) {
                return Err("UnsupportedInSimulator: a Gather of binary messages (Zip / Tar Gather is not simulated).".into());
            }
            json!(ms.into_iter().map(|m| m.body).collect::<Vec<_>>())
        }
        (None, None) => return Err("A Gather step needs messages (the bodies to gather) or messagesFrom (a Splitter's runId).".into()),
    })
}

fn gather(ctx: &Ctx, config: &Value, messages: Value) -> Result<Value, String> {
    (ctx.call)(ctx.xml, "gather.run", json!({
        "messageType": or(config, "messageType", "sameXml"), "algorithm": or(config, "algorithm", "combine"),
        "sourceXPath": s(config, "sourceXPath"), "targetXPath": s(config, "targetXPath"),
        "namespaces": ctx.namespaces, "messages": messages,
    }))
}

/// Message Digest; keys the config leaves out take the tab's defaults (`flowStepDefaults` in `flowcalls.ts`).
fn message_digest(ctx: &Ctx, config: &Value) -> Result<Value, String> {
    (ctx.call)(ctx.xml, "digest.run", json!({
        "filter": s(config, "filter"), "canonicalization": or(config, "canonicalization", "c14n10"),
        "algorithm": or(config, "algorithm", "SHA512"), "targetHeader": or(config, "targetHeader", "SAPMessageDigest"),
        "namespaces": ctx.namespaces, "input": ctx.input,
    }))
}

/// The Flow Steps tab's converter settings before any change (`converterDefaults()` in `flowcalls.ts`).
fn converter_defaults() -> Value {
    json!({
        "conversion": "xmlToJson",
        "useNamespaces": false, "separator": ":", "namespaceMapping": "",
        "addRootElement": false, "rootElementName": "root", "rootNamespace": "",
        "suppressRoot": false, "streaming": false, "streamingElements": "all", "arrayPaths": "", "outputEncoding": "",
        "sourcePath": "", "fieldSeparator": ",", "csvHeader": "xml", "includeParent": false, "parentPath": "",
        "includeAttributes": false,
        "schema": "", "targetPath": "", "recordMarker": "", "firstLineHeader": true, "headerMapping": "xsd",
    })
}

/// JSON ↔ XML, XML ↔ CSV. CSV to XML's schema, target path and record marker may name a header or property
/// (SAP, version 1.4); Camel Simple resolves them first, as in the tab's `runConverter`.
fn converter(ctx: &Ctx, config: &Value) -> Result<Value, String> {
    // Keys the config leaves out (or sets null) take the tab's defaults (`converterDefaults()` in flowcalls.ts).
    let mut params = converter_defaults();
    if let Some(given) = config.as_object() {
        for (k, v) in given {
            if !v.is_null() {
                params[k.as_str()] = v.clone();
            }
        }
    }
    // An iFlow's lists become the tab's text, one `prefix = namespace` or path per line (`settingsFromIflowStep`).
    if let Some(list) = params["namespaceMapping"].as_array() {
        // A pair without prefix or namespace is left out, as the engine does with a list (`ConverterStep.mapping`).
        let text: Vec<String> = list.iter().map(|m| (s(m, "prefix").trim(), s(m, "namespace").trim()))
            .filter(|(p, n)| !p.is_empty() && !n.is_empty()).map(|(p, n)| format!("{p} = {n}")).collect();
        params["namespaceMapping"] = json!(text.join("\n"));
    }
    if let Some(list) = params["arrayPaths"].as_array() {
        let text: Vec<&str> = list.iter().map(|p| p.as_str().unwrap_or_default()).collect();
        params["arrayPaths"] = json!(text.join("\n"));
    }
    if s(&params, "conversion") == "csvToXml" {
        for key in ["schema", "targetPath", "recordMarker"] {
            let v = s(&params, key).to_string();
            if v.contains("${") {
                params[key] = json!(s(&ctx.simple(&v, "expression")?, "value"));
            }
        }
    }
    params["namespaces"] = ctx.namespaces.clone();
    params["input"] = ctx.input.clone();
    (ctx.call)(ctx.xml, "convert.run", params)
}

/// SAP: `function:simple('${property.x}')` in a Filter XPath. Camel Simple gives the value, which goes into
/// the XPath as a string literal (the tab's `resolveSimpleFunctions`).
pub fn resolve_simple_functions(xpath: &str, mut eval: impl FnMut(&str) -> Result<String, String>) -> Result<String, String> {
    let mut out = String::new();
    let mut offset = 0;
    for (start, end, expr) in simple_calls(xpath) {
        out.push_str(&xpath[offset..start]);
        out.push('\'');
        out.push_str(&eval(expr)?.replace('\'', "''"));
        out.push('\'');
        offset = end;
    }
    out.push_str(&xpath[offset..]);
    Ok(out)
}

const SIMPLE_CALL: &str = "function:simple(";

fn is_xpath_space(b: Option<&u8>) -> bool {
    matches!(b, Some(b' ' | b'\t' | b'\r' | b'\n'))
}

/// `function:simple('…')` or `("…")` at `at`, with XPath whitespace around the argument: (start, end, expression).
fn simple_call_at(xpath: &str, at: usize) -> Option<(usize, usize, &str)> {
    let b = xpath.as_bytes();
    let mut j = at + SIMPLE_CALL.len();
    while is_xpath_space(b.get(j)) {
        j += 1;
    }
    let quote = *b.get(j).filter(|c| **c == b'\'' || **c == b'"')?;
    let close = j + 1 + xpath[j + 1..].bytes().position(|c| c == quote)?;
    let mut k = close + 1;
    while is_xpath_space(b.get(k)) {
        k += 1;
    }
    (b.get(k) == Some(&b')')).then(|| (at, k + 1, &xpath[j + 1..close]))
}

/// The function:simple(...) calls of an XPath, outside its string literals and `(: :)` comments (the tab's
/// `simpleCalls`). Scanned like the engine's check (FilterStep.needsCamel): literals first (a doubled quote stays
/// inside), then nested comments. Byte offsets: every marker is ASCII, so they fall on character boundaries.
fn simple_calls(xpath: &str) -> Vec<(usize, usize, &str)> {
    let b = xpath.as_bytes();
    let mut calls = Vec::new();
    let mut quote = 0u8;
    let mut comment = 0usize;
    let mut i = 0;
    while i < b.len() {
        let c = b[i];
        let rest = &b[i..];
        if quote != 0 {
            if c == quote {
                if b.get(i + 1) == Some(&quote) {
                    i += 1;
                } else {
                    quote = 0;
                }
            }
        } else if rest.starts_with(b"(:") {
            comment += 1;
            i += 1;
        } else if comment > 0 {
            if rest.starts_with(b":)") {
                comment -= 1;
                i += 1;
            }
        } else if c == b'\'' || c == b'"' {
            quote = c;
        } else if rest.starts_with(SIMPLE_CALL.as_bytes()) {
            let call = simple_call_at(xpath, i);
            i = call.map_or(i + SIMPLE_CALL.len(), |(_, end, _)| end) - 1;
            calls.extend(call);
        }
        i += 1;
    }
    calls
}
