//! The Where used tab's views of `iflow.index` for `iflow_where_used`, as `app/src/lib/whereused.ts` computes them:
//! the overview of what the iFlows use, the parameters a search finds, `{{parameters}}` resolved from
//! parameters.prop and a Content Modifier table's matching rows. Both sides replay testdata/whereused-parity.

use serde_json::{json, Value};
use std::collections::{BTreeSet, HashMap};

/// The overview's kinds, in the tab's order (whereused.ts KINDS without `parameter`).
const KINDS: &[(&str, &str)] = &[("address", "Address"), ("credential", "Credential"), ("queue", "Queue"), ("key", "Key alias"), ("file", "File")];

fn s(v: &Value) -> &str {
    v.as_str().unwrap_or_default()
}

fn list(v: &Value) -> &[Value] {
    v.as_array().map(Vec::as_slice).unwrap_or_default()
}

/// Ignoring case, then by code point (whereused.ts byText).
fn by_text(a: &str, b: &str) -> std::cmp::Ordering {
    a.to_lowercase().cmp(&b.to_lowercase()).then_with(|| a.cmp(b))
}

/// Each parameter's value from parameters.prop, by name.
pub fn parameter_values(iflow: &Value) -> HashMap<String, String> {
    list(&iflow["parameters"]).iter()
        .filter_map(|p| Some((s(&p["name"]).to_string(), p["value"].as_str()?.to_string())))
        .collect()
}

/// `&lt;` and the other four XML entities, in one pass (an `&amp;lt;` stays `&lt;`).
fn unescape(t: &str) -> String {
    let mut out = String::with_capacity(t.len());
    let mut rest = t;
    while let Some(i) = rest.find('&') {
        out.push_str(&rest[..i]);
        rest = &rest[i..];
        let hit = [("&lt;", '<'), ("&gt;", '>'), ("&amp;", '&'), ("&apos;", '\''), ("&quot;", '"')]
            .into_iter().find(|(e, _)| rest.starts_with(e));
        match hit {
            Some((e, c)) => { out.push(c); rest = &rest[e.len()..]; }
            None => { out.push('&'); rest = &rest[1..]; }
        }
    }
    out + rest
}

/// The value with each `{{parameter}}` that has a value replaced by it; None if none has one.
pub fn resolve(value: &str, params: &HashMap<String, String>) -> Option<String> {
    // A Content Modifier's table is XML text: a name in it is escaped once more.
    let table = value.trim_start().starts_with("<row");
    let (mut out, mut replaced, mut at) = (String::new(), false, 0);
    let mut i = 0;
    while let Some(start) = value[i..].find("{{").map(|k| i + k) {
        // `{{` then one or more characters other than braces, then `}}` (the tab's /\{\{([^{}]+)\}\}/).
        let inner = start + 2;
        let end = value[inner..].find(['{', '}']).map_or(value.len(), |k| inner + k);
        if end == inner || !value[end..].starts_with("}}") {
            i = start + 1;
            continue;
        }
        let raw = value[inner..end].trim();
        let name = if table { unescape(raw) } else { raw.to_string() };
        if let Some(v) = params.get(&name) {
            out.push_str(&value[at..start]);
            out.push_str(v);
            at = end + 2;
            replaced = true;
        }
        i = end + 2;
    }
    replaced.then(|| out + &value[at..])
}

/// The values of one kind: each value's text, number of places and what its parameters resolve to.
type Values = Vec<(String, usize, BTreeSet<String>)>;

/// The overview: each distinct address, credential, queue, key alias and file the iFlows use, with the number of
/// places, most used first, and what its parameters resolve to.
pub fn inventory(index: &[Value]) -> Value {
    let mut groups: Vec<(&str, &str, Values)> =
        KINDS.iter().map(|(k, l)| (*k, *l, Vec::new())).collect();
    for i in index {
        let params = parameter_values(i);
        for e in list(&i["entries"]) {
            let (kind, value) = (s(&e["kind"]), s(&e["value"]));
            let Some(g) = groups.iter_mut().find(|g| g.0 == kind) else { continue };
            if value.is_empty() { continue; }
            let pos = g.2.iter().position(|v| v.0 == value).unwrap_or_else(|| {
                g.2.push((value.to_string(), 0, BTreeSet::new()));
                g.2.len() - 1
            });
            g.2[pos].1 += 1;
            if let Some(r) = resolve(value, &params) {
                g.2[pos].2.insert(r);
            }
        }
    }
    Value::Array(groups.into_iter().filter(|g| !g.2.is_empty()).map(|(kind, label, mut values)| {
        values.sort_by(|a, b| b.1.cmp(&a.1).then_with(|| by_text(&a.0, &b.0)));
        json!({ "kind": kind, "label": label, "values": values.into_iter()
            .map(|(value, count, resolved)| json!({ "value": value, "count": count, "resolved": resolved }))
            .collect::<Vec<_>>() })
    }).collect())
}

/// Each iFlow's parameters whose name or value contains the query (ignoring case); iFlows without one left out.
pub fn matching_parameters(index: &[Value], query: &str) -> Vec<Value> {
    let q = query.trim().to_lowercase();
    if q.is_empty() {
        return Vec::new();
    }
    index.iter().filter_map(|i| {
        let ps: Vec<Value> = list(&i["parameters"]).iter()
            .filter(|p| s(&p["name"]).to_lowercase().contains(&q) || s(&p["value"]).to_lowercase().contains(&q))
            .cloned().collect();
        let mut i = i.clone();
        i["parameters"] = Value::Array(ps);
        (!list(&i["parameters"]).is_empty()).then_some(i)
    }).collect()
}

/// The cells of one `<row>`: `<cell id='…'>…</cell>`, non-empty ones, decoded.
fn cells(row: &str) -> Vec<Value> {
    let mut out = Vec::new();
    let mut i = 0;
    while let Some(k) = row[i..].find("<cell id=") {
        let start = i + k;
        i = start + 1;
        let rest = &row[start + 9..];
        if !rest.starts_with(['\'', '"']) { continue; }
        let id_end = rest[1..].find(['\'', '"']).map(|e| e + 1);
        let Some(id_end) = id_end.filter(|e| rest[*e + 1..].starts_with('>')) else { continue };
        let body = &rest[id_end + 2..];
        let Some(close) = body.find("</cell>") else { continue };
        let text = unescape(&body[..close]);
        if !text.is_empty() {
            out.push(json!({ "id": &rest[1..id_end], "text": text }));
        }
        i = start + 9 + id_end + 2 + close + 7;
    }
    out
}

/// A Content Modifier table as rows of cells, only those containing the query, or all when none does (a hit by key
/// name): `{rows, hidden}`. None for a value that is no table.
pub fn table_hit(value: &str, query: &str) -> Option<Value> {
    if !value.trim_start().starts_with("<row") {
        return None;
    }
    let mut rows = Vec::new();
    let mut i = 0;
    while let Some(k) = value[i..].find("<row>") {
        let inner = i + k + 5;
        let Some(end) = value[inner..].find("</row>") else { break };
        rows.push(cells(&value[inner..inner + end]));
        i = inner + end + 6;
    }
    if rows.is_empty() {
        return None;
    }
    let q = query.trim().to_lowercase();
    let hits: Vec<&Vec<Value>> = if q.is_empty() { Vec::new() } else {
        rows.iter().filter(|r| r.iter().any(|c| s(&c["text"]).to_lowercase().contains(&q))).collect()
    };
    Some(if hits.is_empty() {
        json!({ "rows": rows, "hidden": 0 })
    } else {
        json!({ "rows": hits, "hidden": rows.len() - hits.len() })
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn whereused_parity() {
        let path = concat!(env!("CARGO_MANIFEST_DIR"), "/../../testdata/whereused-parity/cases.json");
        let f: Value = serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap();
        let index = list(&f["index"]);
        assert_eq!(inventory(index), f["overview"]);
        for c in list(&f["parameters"]) {
            let got: Vec<Value> = matching_parameters(index, s(&c["query"])).iter()
                .map(|i| json!([i["iflow"], list(&i["parameters"]).iter().map(|p| p["name"].clone()).collect::<Vec<_>>()]))
                .collect();
            assert_eq!(Value::Array(got), c["expected"], "{}", c["query"]);
        }
        for c in list(&f["resolve"]) {
            let params: HashMap<String, String> = c["params"].as_object().unwrap().iter()
                .map(|(k, v)| (k.clone(), s(v).to_string())).collect();
            assert_eq!(resolve(s(&c["value"]), &params).map(Value::from).unwrap_or(Value::Null), c["expected"], "{}", c["value"]);
        }
        for c in list(&f["tables"]) {
            assert_eq!(table_hit(s(&c["value"]), s(&c["query"])).unwrap_or(Value::Null), c["expected"], "{}", c["value"]);
        }
    }
}
