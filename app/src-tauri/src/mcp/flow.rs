//! Flow steps for the MCP server. The Flow Steps tab leads (`app/src/lib/store.svelte.ts`, section "flow steps");
//! this follows it call for call, so that both give the same result. Change the tab first, then this:
//! * XPath (Content Modifier rows, XML Router conditions, Filter, Splitter, Gather) and the converters run on the
//!   XML engine, 9.9 when installed; headers and properties are XPath variables (F39).
//! * Camel Simple and the Content Modifier run on the Camel of a Groovy engine.

use super::tools::{self, engine_call, str_arg, Args};
use super::Server;
use serde_json::{json, Value};

struct Ctx<'a> {
    server: &'a Server,
    xml: &'static str,
    camel: &'a str,
    namespaces: Value,
    input: Value,
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
    let ctx = Ctx {
        server,
        xml: tools::xml_runtime(server),
        camel: str_arg(args, "camel").unwrap_or(tools::camel_runtime(server)),
        namespaces: args.get("namespaces").cloned().unwrap_or(json!({})),
        input: tools::message(server, args)?,
    };
    if kind != "gather" && args.contains_key("messages") {
        return Err("messages is for a Gather step; the other steps take body (or bodyPath).".into());
    }
    let mut result = match kind.as_str() {
        "contentModifier" => content_modifier(&ctx, &config)?,
        "filter" => filter(&ctx, &config)?,
        "router" => router(&ctx, &config)?,
        "splitter" => splitter(&ctx, &config)?,
        "gather" => gather(&ctx, &config, args)?,
        "converter" => converter(&ctx, &config)?,
        "" => return Err("The step has no type.".into()),
        other => return Err(format!("UnsupportedInSimulator: the step type '{other}' (simulated: contentModifier, filter, \
            router, splitter, gather, converter).")),
    };
    result["step"] = json!({ "type": kind, "name": step.get("name"), "config": config });
    Ok(result)
}

/// The settings of each step type in the Flow Steps tab (`flow` in `store.svelte.ts`).
fn config_keys(kind: &str) -> Option<&'static [&'static str]> {
    Some(match kind {
        "contentModifier" => &["headers", "properties", "bodyType", "body"],
        "filter" => &["expression", "valueType"],
        "router" => &["routes", "throwException"],
        "splitter" => &["splitter", "expressionType", "expression", "grouping", "streaming"],
        "gather" => &["messageType", "algorithm", "sourceXPath", "targetXPath"],
        "converter" => &CONVERTER_KEYS,
        _ => return None,
    })
}

/// The converter's settings (`flow.converter` in `store.svelte.ts`); `ConverterStep.java` reads them.
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
        let r = engine_call(self.server, self.xml, "xpath.eval", json!({
            "expression": expression, "version": "3.1", "as": as_, "namespaces": self.namespaces, "input": input,
        }))?;
        if r["ok"] != true {
            return Err(first_message(&r, "XPath failed"));
        }
        Ok(r["value"].as_str().unwrap_or_default().to_string())
    }

    fn simple(&self, expression: &str, mode: &str) -> Result<Value, String> {
        let r = engine_call(self.server, self.camel, "simple.eval",
            json!({ "expression": expression, "mode": mode, "input": self.input }))?;
        if r["ok"] != true {
            return Err(first_message(&r, "Camel Simple failed"));
        }
        Ok(r)
    }
}

/// A row as the Flow Steps tab keeps it (`openFlowStep`).
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
    let input = &ctx.input;
    let mut state = input.clone();
    let mut done = json!({ "headers": [], "properties": [] });
    let mut pending = json!({ "headers": [], "properties": [] });
    for table in ["headers", "properties"] {
        for r in config[table].as_array().into_iter().flatten() {
            let mut row = cm_row(r);
            if row["action"] == "Create" && row["type"] == "xpath" {
                let value = s(&row, "value").to_string();
                let has_pending = pending.as_object().unwrap().values().any(|v| !v.as_array().unwrap().is_empty());
                if value.contains('$') && has_pending {
                    let mut p = pending.clone();
                    p["bodyType"] = json!("constant");
                    p["body"] = json!("");
                    p["input"] = state.clone();
                    let st = engine_call(ctx.server, ctx.camel, "contentModifier.run", p)?;
                    if st["ok"] == true {
                        state = json!({ "body": input["body"], "headers": st["output"]["headers"], "properties": st["output"]["properties"] });
                    }
                    pending = json!({ "headers": [], "properties": [] });
                }
                let as_ = if s(&row, "datatype").starts_with("org.w3c.dom.") { "nodes" } else { "string" };
                let v = ctx.xpath_value(&value, as_, &state).map_err(|e| format!("XPath of {}: {e}", s(&row, "name")))?;
                row["resolved"] = json!(v);
            }
            done[table].as_array_mut().unwrap().push(row.clone());
            pending[table].as_array_mut().unwrap().push(row);
        }
    }
    let body_type = if s(config, "bodyType") == "expression" { "expression" } else { "constant" };
    engine_call(ctx.server, ctx.camel, "contentModifier.run", json!({
        "headers": done["headers"], "properties": done["properties"],
        "bodyType": body_type, "body": s(config, "body"), "input": input,
    }))
}

fn router(ctx: &Ctx, config: &Value) -> Result<Value, String> {
    let routes = config["routes"].as_array().cloned().unwrap_or_default();
    let mut out: Vec<Value> = routes.iter().map(|r| json!({ "name": r["name"],
        "status": if r["default"] == true { "default" } else { "not evaluated" } })).collect();
    let kinds: std::collections::BTreeSet<&str> = routes.iter().filter(|r| r["default"] != true)
        .map(|r| s(r, "expressionType")).collect();
    if kinds.len() > 1 {
        return Ok(json!({ "routes": out, "diagnostics": [],
            "taken": "Error: the routes mix XML and Non-XML conditions; SAP: \"the evaluation fails\" (Define Router)." }));
    }
    let mut taken = String::new();
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
                break;
            }
        }
        if out[i]["status"] == "true" {
            taken = s(r, "name").to_string();
            break;
        }
    }
    if taken.is_empty() {
        let d = routes.iter().find(|r| r["default"] == true);
        taken = if config["throwException"] == true {
            "No route matched: Throw Exception is set, so the Router raises an exception.".into()
        } else if let Some(d) = d {
            format!("{} (default route)", s(d, "name"))
        } else {
            "No route matched and there is no default route.".into()
        };
    }
    Ok(json!({ "routes": out, "taken": taken, "diagnostics": [] }))
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
    engine_call(ctx.server, ctx.xml, "split.run", json!({
        "splitter": or(config, "splitter", "general"), "expressionType": or(config, "expressionType", "xpath"),
        "expression": resolve(s(config, "expression").to_string())?, "grouping": resolve(grouping)?,
        "streaming": config["streaming"] != false,
        "namespaces": ctx.namespaces, "input": { "body": ctx.input["body"] },
    }))
}

fn filter(ctx: &Ctx, config: &Value) -> Result<Value, String> {
    let expression = resolve_simple_functions(s(config, "expression"), |e| Ok(s(&ctx.simple(e, "expression")?, "value").to_string()))?;
    engine_call(ctx.server, ctx.xml, "filter.run", json!({
        "expression": expression, "valueType": or(config, "valueType", "nodelist"),
        "namespaces": ctx.namespaces, "input": ctx.input,
    }))
}

fn gather(ctx: &Ctx, config: &Value, args: &Args) -> Result<Value, String> {
    let messages = args.get("messages").cloned().ok_or("A Gather step needs messages (the bodies to gather).")?;
    engine_call(ctx.server, ctx.xml, "gather.run", json!({
        "messageType": or(config, "messageType", "sameXml"), "algorithm": or(config, "algorithm", "combine"),
        "sourceXPath": s(config, "sourceXPath"), "targetXPath": s(config, "targetXPath"),
        "namespaces": ctx.namespaces, "messages": messages,
    }))
}

/// The Flow Steps tab's converter settings before any change (`converterDefaults()` in `store.svelte.ts`).
fn converter_defaults() -> Value {
    json!({
        "conversion": "xmlToJson",
        "useNamespaces": false, "separator": ":", "namespaceMapping": "",
        "addRootElement": false, "rootElementName": "root", "rootNamespace": "",
        "suppressRoot": false, "streaming": false, "streamingElements": "all", "arrayPaths": "", "outputEncoding": "",
        "sourcePath": "/Order/Item", "fieldSeparator": ",", "csvHeader": "xml", "includeParent": false, "parentPath": "",
        "includeAttributes": false,
        "schema": "", "targetPath": "", "recordMarker": "", "firstLineHeader": true, "headerMapping": "xsd",
    })
}

/// JSON ↔ XML, XML ↔ CSV. CSV to XML's schema, target path and record marker may name a header or property
/// (SAP, version 1.4); Camel Simple resolves them first, as in the tab's `runConverter`.
fn converter(ctx: &Ctx, config: &Value) -> Result<Value, String> {
    // Keys the config leaves out (or sets null) take the tab's defaults (`converterDefaults()` in the store).
    let mut params = converter_defaults();
    if let Some(given) = config.as_object() {
        for (k, v) in given {
            if !v.is_null() {
                params[k.as_str()] = v.clone();
            }
        }
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
    engine_call(ctx.server, ctx.xml, "convert.run", params)
}

/// SAP: `function:simple('${property.x}')` in a Filter XPath. Camel Simple gives the value, which goes into
/// the XPath as a string literal (the tab's `resolveSimpleFunctions`).
pub fn resolve_simple_functions(xpath: &str, mut eval: impl FnMut(&str) -> Result<String, String>) -> Result<String, String> {
    const CALL: &str = "function:simple(";
    let mut out = String::new();
    let mut rest = xpath;
    while let Some(at) = rest.find(CALL) {
        let after = &rest[at + CALL.len()..];
        let trimmed = after.trim_start();
        let quote = trimmed.chars().next().filter(|c| *c == '\'' || *c == '"');
        let parsed = quote.and_then(|q| {
            let inner = &trimmed[1..];
            let end = inner.find(q)?;
            let tail = inner[end + 1..].trim_start();
            tail.strip_prefix(')').map(|t| (&inner[..end], t))
        });
        match parsed {
            Some((expr, tail)) => {
                out.push_str(&rest[..at]);
                out.push('\'');
                out.push_str(&eval(expr)?.replace('\'', "''"));
                out.push('\'');
                rest = tail;
            }
            None => {
                out.push_str(&rest[..at + CALL.len()]);
                rest = after;
            }
        }
    }
    out.push_str(rest);
    Ok(out)
}
