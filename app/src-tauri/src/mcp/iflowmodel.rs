//! The Diagram tab's reading of an imported iFlow (`iflow.models`, IflowModel.java) for `iflow_diagram`, as
//! `app/src/lib/iflowmodel.ts` computes it: each process's elements in flow order with the routes of a gateway, the
//! adapters with their systems, and the element types named as in the editor. The geometry the tab draws is left
//! out. Both sides replay testdata/iflowmodel-parity.

use serde_json::{json, Map, Value};
use std::collections::{HashMap, HashSet};

fn s(v: &Value) -> &str {
    v.as_str().unwrap_or_default()
}

fn list(v: &Value) -> &[Value] {
    v.as_array().map(Vec::as_slice).unwrap_or_default()
}

/// Main processes first, each followed by its exception subprocesses, then the local processes likewise.
pub fn process_order(model: &Value) -> Vec<&Value> {
    let all = list(&model["processes"]);
    let mut out: Vec<&Value> = Vec::new();
    fn add<'a>(p: &'a Value, all: &'a [Value], out: &mut Vec<&'a Value>) {
        out.push(p);
        for c in all.iter().filter(|c| c["parent"] == p["id"] && !p["id"].is_null()) {
            add(c, all, out);
        }
    }
    for kind in ["main", "local"] {
        for p in all.iter().filter(|p| p["kind"] == kind && p.get("parent").is_none_or(Value::is_null)) {
            add(p, all, &mut out);
        }
    }
    for p in all {
        if !out.iter().any(|o| std::ptr::eq(*o, p)) {
            out.push(p);
        }
    }
    out
}

/// One element of the outline: the node, its depth, the route that leads to it, and whether it is listed again
/// (branches meet) or reached from no start event.
pub struct Entry<'a> {
    pub node: &'a Value,
    pub depth: usize,
    pub route: Option<Value>,
    pub again: bool,
    pub unreachable: bool,
}

fn route(f: &Value) -> Value {
    let mut r = json!({ "name": f["name"], "condition": f["condition"] });
    if f.get("default").is_some_and(|d| !d.is_null()) {
        r["default"] = f["default"].clone();
    }
    if f.get("condition").is_none() {
        r.as_object_mut().unwrap().remove("condition");
    }
    r
}

/// Each process's elements in flow order: from its start events along the sequence flows; the routes of a gateway
/// are indented under it, with their names and conditions. Elements that no start event reaches come last.
pub fn outline(model: &Value) -> Vec<(&Value, Vec<Entry<'_>>)> {
    let nodes = list(&model["nodes"]);
    let by_id: HashMap<&str, &Value> = nodes.iter().map(|n| (s(&n["id"]), n)).collect();
    let mut out: HashMap<&str, Vec<&Value>> = HashMap::new();
    for f in list(&model["flows"]).iter().filter(|f| f["kind"] == "sequence") {
        out.entry(s(&f["source"])).or_default().push(f);
    }
    let mut groups = Vec::new();
    for process in process_order(model) {
        let own: Vec<&Value> = nodes.iter().filter(|n| n["process"] == process["id"] && n["kind"] != "subprocess").collect();
        let mut entries: Vec<Entry> = Vec::new();
        let mut seen: HashSet<&str> = HashSet::new();
        fn walk<'a>(start: &'a str, depth: usize, route_in: Option<Value>, by_id: &HashMap<&str, &'a Value>,
                    out: &HashMap<&str, Vec<&'a Value>>, seen: &mut HashSet<&'a str>, entries: &mut Vec<Entry<'a>>) {
            let mut id = start;
            let mut r = route_in;
            // A chain of single flows stays at one depth; a loop here would only recurse at branches.
            loop {
                let Some(node) = by_id.get(id).copied() else { return };
                if seen.contains(id) {
                    entries.push(Entry { node, depth, route: r, again: true, unreachable: false });
                    return;
                }
                seen.insert(id);
                entries.push(Entry { node, depth, route: r, again: false, unreachable: false });
                let next = out.get(id).map(Vec::as_slice).unwrap_or_default();
                if next.len() == 1 {
                    // A Join, or a Router with one route: no branch, but a route keeps its label.
                    let f = next[0];
                    id = s(&f["target"]);
                    let labelled = !s(&f["name"]).is_empty() || f.get("condition").is_some_and(|c| !s(c).is_empty());
                    r = (node["kind"] == "gateway" && labelled).then(|| route(f));
                    continue;
                }
                for f in next {
                    walk(s(&f["target"]), depth + 1, Some(route(f)), by_id, out, seen, entries);
                }
                return;
            }
        }
        for n in own.iter().filter(|n| n["kind"] == "start") {
            walk(s(&n["id"]), 0, None, &by_id, &out, &mut seen, &mut entries);
        }
        for n in &own {
            if seen.insert(s(&n["id"])) {
                entries.push(Entry { node: n, depth: 0, route: None, again: false, unreachable: true });
            }
        }
        groups.push((process, entries));
    }
    groups
}

/// The adapters: who sends to the iFlow, and whom it calls. (flow, system, inbound)
pub fn adapters(model: &Value) -> Vec<(&Value, Option<&Value>, bool)> {
    let by_id: HashMap<&str, &Value> = list(&model["nodes"]).iter().map(|n| (s(&n["id"]), n)).collect();
    list(&model["flows"]).iter().filter(|f| f["kind"] == "message").map(|flow| {
        let src = by_id.get(s(&flow["source"])).copied();
        let inbound = src.is_some_and(|n| n["kind"] == "sender") || flow["direction"] == "Sender";
        (flow, if inbound { src } else { by_id.get(s(&flow["target"])).copied() }, inbound)
    }).collect()
}

const ACTIVITY: &[(&str, &str)] = &[
    ("Enricher", "Content Modifier"), ("ExclusiveGateway", "Router"), ("Filter", "Filter"), ("Splitter", "Splitter"),
    ("Gather", "Gather"), ("XmlValidator", "XML Validator"), ("Encoder", "Encoder"), ("Decoder", "Decoder"),
    ("MessageDigest", "Message Digest"), ("ErrorEventSubProcessTemplate", "Exception Subprocess"),
    ("JsonToXmlConverter", "JSON to XML Converter"), ("XmlToJsonConverter", "XML to JSON Converter"),
    ("CsvToXmlConverter", "CSV to XML Converter"), ("XmlToCsvConverter", "XML to CSV Converter"),
    ("SequentialMulticast", "Sequential Multicast"), ("DBstorage", "Data Store"),
];
const SUB: &[(&str, &str)] = &[
    ("GroovyScript", "Groovy Script"), ("JavaScript", "JavaScript"), ("XSLTMapping", "XSLT Mapping"),
    ("MessageMapping", "Message Mapping"), ("NonLoopingProcess", "Process Call"), ("LoopingProcess", "Looping Process Call"),
];

/// A name for the element's type. Known ones read as in the editor; the others show the name the file gives.
pub fn type_label(n: &Value) -> String {
    let kind = s(&n["kind"]);
    match kind {
        "sender" => return "Sender".into(),
        "receiver" => return "Receiver".into(),
        "start" | "end" | "event" => {
            let what = match kind { "start" => "Start", "end" => "End", _ => "Intermediate" };
            return match n["event"].as_str().filter(|e| !e.is_empty()) {
                Some(e) => format!("{what} ({e})"),
                None => format!("{what} event"),
            };
        }
        _ => {}
    }
    let lookup = |table: &[(&str, &'static str)], key: &Value| key.as_str().and_then(|k| table.iter().find(|(a, _)| *a == k)).map(|(_, l)| *l);
    if let Some(sub) = lookup(SUB, &n["subActivityType"]) {
        return sub.into();
    }
    let variant = n["variant"].as_str().filter(|v| !v.is_empty());
    if let Some(act) = lookup(ACTIVITY, &n["activityType"]) {
        return match (act, variant) {
            ("Data Store", Some(v)) => format!("Data Store ({v})"),
            _ => act.into(),
        };
    }
    [variant, n["activityType"].as_str(), n["element"].as_str(), Some(kind)].into_iter().flatten()
        .find(|x| !x.is_empty()).unwrap_or(kind).to_string()
}

/// Keys the Where used index leaves out (whereused.ts SKIPPED_KEYS): bookkeeping of the editor, not settings.
const SKIPPED: &[&str] = &["cmdVariantUri", "componentVersion", "componentNS", "componentSWCVName", "componentSWCVId"];

fn settings(props: &Value) -> Value {
    Value::Object(props.as_object().into_iter().flatten()
        .filter(|(k, v)| !SKIPPED.contains(&k.as_str()) && !s(v).is_empty())
        .map(|(k, v)| (k.clone(), v.clone())).collect::<Map<_, _>>())
}

/// What `iflow_diagram` returns for one model: its processes with their elements in flow order (each with its
/// settings, once), and the adapters with their systems.
pub fn diagram(model: &Value) -> Value {
    let names: HashMap<&str, &str> = list(&model["processes"]).iter().map(|p| (s(&p["id"]), s(&p["name"]))).collect();
    let by_id: HashMap<&str, &Value> = list(&model["nodes"]).iter().map(|n| (s(&n["id"]), n)).collect();
    let processes: Vec<Value> = outline(model).into_iter().map(|(p, entries)| {
        let mut process = json!({ "id": p["id"], "name": p["name"], "kind": p["kind"] });
        if let Some(parent) = p.get("parent").filter(|x| !x.is_null()) {
            process["parent"] = parent.clone();
        }
        process["elements"] = Value::Array(entries.into_iter().map(|e| {
            let n = e.node;
            let mut el = json!({ "id": n["id"], "name": n["name"], "type": type_label(n), "depth": e.depth });
            if let Some(r) = e.route { el["route"] = r; }
            if e.again {
                el["again"] = json!(true); // listed once more where branches meet; its settings are above
                return el;
            }
            if e.unreachable { el["unreachable"] = json!(true); }
            if let Some(c) = n["calls"].as_str() {
                el["calls"] = json!(names.get(c).copied().unwrap_or(c));
            }
            if n["loop"] == true { el["loop"] = json!(true); }
            let props = settings(&n["properties"]);
            if props.as_object().is_some_and(|o| !o.is_empty()) { el["settings"] = props; }
            el
        }).collect());
        process
    }).collect();
    let adapters: Vec<Value> = adapters(model).into_iter().map(|(f, system, inbound)| {
        // The element the adapter connects to inside the iFlow: the start event it starts, or the step that calls.
        let inner = by_id.get(s(if inbound { &f["target"] } else { &f["source"] })).map(|n| n["name"].clone());
        let mut a = json!({ "id": f["id"], "name": f["name"], "adapter": f["adapter"],
            "direction": if inbound { "inbound" } else { "outbound" },
            "system": system.map(|n| n["name"].clone()), "element": inner });
        let props = settings(&f["properties"]);
        if props.as_object().is_some_and(|o| !o.is_empty()) { a["settings"] = props; }
        a
    }).collect();
    json!({ "iflow": model["iflow"], "name": model["name"], "file": model["file"], "processes": processes, "adapters": adapters })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn iflowmodel_parity() {
        let path = concat!(env!("CARGO_MANIFEST_DIR"), "/../../testdata/iflowmodel-parity/cases.json");
        let f: Value = serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap();
        for c in list(&f["cases"]) {
            let m = &c["model"];
            let got: Vec<Value> = outline(m).into_iter().map(|(p, es)| json!({ "process": p["id"], "entries": es.into_iter()
                .map(|e| json!({ "node": e.node["id"], "depth": e.depth, "route": e.route, "again": e.again, "unreachable": e.unreachable }))
                .collect::<Vec<_>>() })).collect();
            assert_eq!(Value::Array(got), c["outline"], "outline of {}", m["iflow"]);
            let got: Vec<Value> = adapters(m).into_iter()
                .map(|(f, sys, inbound)| json!({ "flow": f["id"], "system": sys.map(|n| n["id"].clone()), "inbound": inbound })).collect();
            assert_eq!(Value::Array(got), c["adapters"], "adapters of {}", m["iflow"]);
            let got: Map<String, Value> = list(&m["nodes"]).iter().map(|n| (s(&n["id"]).to_string(), json!(type_label(n)))).collect();
            assert_eq!(Value::Object(got), c["labels"], "labels of {}", m["iflow"]);
        }
    }
}
