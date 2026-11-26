//! The functions a Groovy script declares, read from its text as the Groovy tab does (`app/src/lib/functions.ts`):
//! opening or reading a script must not run its code, and compiling Groovy can (AST transformations). A lexical
//! reading, not a parser; both sides replay testdata/functions-parity. JavaScript's `\w` and `\b` are ASCII, so the
//! patterns here spell the ASCII classes out.

use regex::Regex;
use serde_json::{json, Value};
use std::sync::OnceLock;

const KEYWORDS: &[&str] = &["if", "else", "for", "while", "switch", "catch", "return", "new", "throw", "try", "synchronized", "assert"];
const MODIFIERS: &[&str] = &["public", "protected", "final", "abstract", "synchronized"];

fn re(cell: &'static OnceLock<Regex>, pattern: &str) -> &'static Regex {
    cell.get_or_init(|| Regex::new(pattern).expect("a valid pattern"))
}

/// Blanks comments and string contents (keeping offsets in characters and line breaks), so their text matches nothing.
fn mask(src: &str) -> String {
    let s: Vec<char> = src.chars().collect();
    let mut out = s.clone();
    let starts = |at: usize, pat: &str| pat.chars().enumerate().all(|(k, p)| s.get(at + k) == Some(&p));
    let find = |from: usize, pat: &str| (from..s.len()).find(|&k| starts(k, pat));
    let mut blank = |from: usize, to: usize| {
        for c in out.iter_mut().take(to.min(s.len())).skip(from) {
            if *c != '\n' { *c = ' '; }
        }
    };
    let mut i = 0;
    while i < s.len() {
        let c = s[i];
        if c == '/' && s.get(i + 1) == Some(&'/') {
            let stop = find(i, "\n").unwrap_or(s.len());
            blank(i, stop);
            i = stop;
        } else if c == '/' && s.get(i + 1) == Some(&'*') {
            let stop = find(i + 2, "*/").map_or(s.len(), |e| e + 2);
            blank(i, stop);
            i = stop;
        } else if c == '"' || c == '\'' {
            let triple: String = std::iter::repeat_n(c, 3).collect();
            let quote = if starts(i, &triple) { triple } else { c.to_string() };
            let n = quote.chars().count();
            let mut j = i + n;
            while j < s.len() && !starts(j, &quote) {
                if s[j] == '\\' { j += 1; } else if n == 1 && s[j] == '\n' { break; }
                j += 1;
            }
            blank(i + n, j); // the quotes stay, so `"a"` is still a token
            i = (j + n).min(s.len());
        } else {
            i += 1;
        }
    }
    out.into_iter().collect()
}

/// Brace depth at each byte offset of masked code.
fn depths(code: &str) -> Vec<usize> {
    let mut d = vec![0; code.len() + 1];
    let mut depth = 0usize;
    for (b, c) in code.char_indices() {
        d[b..b + c.len_utf8()].fill(depth);
        if c == '{' { depth += 1; } else if c == '}' { depth = depth.saturating_sub(1); }
    }
    d[code.len()] = depth;
    d
}

/// Splits a parameter list at commas outside <...>, (...) and [...].
fn split_params(list: &str) -> Vec<String> {
    let (mut parts, mut cur, mut depth) = (Vec::new(), String::new(), 0i32);
    for c in list.chars() {
        if "<([".contains(c) { depth += 1; } else if ">)]".contains(c) { depth -= 1; }
        if c == ',' && depth == 0 { parts.push(std::mem::take(&mut cur)); } else { cur.push(c); }
    }
    if !cur.trim().is_empty() { parts.push(cur); }
    parts.iter().map(|p| p.trim().to_string()).filter(|p| !p.is_empty()).collect()
}

fn kind_of(t: &str) -> Option<&'static str> {
    static PREFIX: OnceLock<Regex> = OnceLock::new();
    let t: String = t.split_whitespace().collect();
    let t = re(&PREFIX, r"^(java\.lang\.|com\.sap\.it\.api\.mapping\.)").replace(&t, "");
    match t.as_ref() {
        "" | "def" | "String" | "Object" => Some("value"),
        "String[]" | "String..." | "Object[]" | "Object..." => Some("queue"),
        "Output" => Some("output"),
        "MappingContext" => Some("context"),
        _ => None,
    }
}

/// (name, type as written, kind)
fn parse_param(text: &str) -> (String, String, Option<&'static str>) {
    static ANN: OnceLock<Regex> = OnceLock::new();
    static DEFAULT: OnceLock<Regex> = OnceLock::new();
    static FINAL: OnceLock<Regex> = OnceLock::new();
    static PARAM: OnceLock<Regex> = OnceLock::new();
    // Annotations, `final` and a default value say nothing about the type.
    let p = re(&ANN, r"@[0-9A-Za-z_]+(\([^)]*\))?").replace_all(text, " ");
    let p = re(&DEFAULT, r"(?s)=.*$").replace(&p, "");
    let p = re(&FINAL, r"(?-u:\b)final(?-u:\b)").replace_all(&p, " ");
    let p = p.trim();
    match re(&PARAM, r"(?s)^(.*?)([A-Za-z_$][0-9A-Za-z_$]*)$").captures(p) {
        Some(m) => {
            let ty = m[1].trim().to_string();
            let kind = kind_of(&ty);
            (m[2].to_string(), ty, kind)
        }
        None => (p.to_string(), String::new(), kind_of("")),
    }
}

fn classify(params: &[(String, String, Option<&str>)]) -> Option<&'static str> {
    static MESSAGE: OnceLock<Regex> = OnceLock::new();
    if params.is_empty() {
        return None;
    }
    if params.len() == 1 {
        let t: String = params[0].1.split_whitespace().collect();
        // As in the engine: one String or String[] parameter is a mapping function; one Object (or untyped) or a
        // Message is the script step's message.
        if params[0].2 == Some("value") && t != "String" && t != "java.lang.String" { return Some("step"); }
        if params[0].2.is_none() { return re(&MESSAGE, r"(^|\.)Message$").is_match(&t).then_some("step"); }
    }
    params.iter().all(|p| p.2.is_some()).then_some("mapping")
}

/// The top-level functions the script declares, in source order (first declaration of each name and signature),
/// in the shape of functions.ts' ScriptFunction.
pub fn script_functions(src: &str) -> Vec<Value> {
    static DECL: OnceLock<Regex> = OnceLock::new();
    let code = mask(src);
    let depth = depths(&code);
    let mut out: Vec<Value> = Vec::new();
    // [modifiers] (def | Type) name ( params ) [throws ...] {
    // JavaScript's \s also matches U+FEFF (a byte order mark), Rust's does not.
    let decl = re(&DECL, r"(^|[\s\x{FEFF};}])((?:[A-Za-z_$][0-9A-Za-z_$.]*(?:<[^>{};]*>)?(?:\[\])*[ \t]+)+)([A-Za-z_$][0-9A-Za-z_$]*)[\s\x{FEFF}]*\(([^()]*)\)[\s\x{FEFF}]*(?:throws[\s\x{FEFF}]+[0-9A-Za-z_$.,\s\x{FEFF}]+)?\{");
    for m in decl.captures_iter(&code) {
        if depth[m.get(2).unwrap().start()] != 0 { continue; }
        let words: Vec<&str> = m[2].split_whitespace().collect();
        let name = &m[3];
        if KEYWORDS.contains(&name) || words.iter().any(|w| KEYWORDS.contains(w))
            || words.iter().any(|w| ["static", "private", "class", "interface", "enum"].contains(w)) { continue; }
        if words.iter().all(|w| MODIFIERS.contains(w)) { continue; } // a call such as `foo(x) {` after a modifier
        if name == "run" { continue; }
        let params: Vec<_> = split_params(&m[4]).iter().map(|p| parse_param(p)).collect();
        let parameters: Vec<String> = params.iter().map(|(_, t, k)| match k {
            Some("value") => "String".into(), Some("queue") => "String[]".into(), Some("output") => "Output".into(),
            Some(_) => "MappingContext".into(), None if t.is_empty() => "Object".into(), None => t.clone(),
        }).collect();
        let signature = format!("{name}({})", parameters.join(", "));
        if out.iter().any(|f| f["signature"] == signature.as_str()) { continue; }
        out.push(json!({
            "name": name,
            "params": params.iter().map(|(n, t, k)| json!({ "name": n, "type": t, "kind": k })).collect::<Vec<_>>(),
            "kind": classify(&params),
            "parameters": parameters,
            "signature": signature,
        }));
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn functions_parity() {
        let path = concat!(env!("CARGO_MANIFEST_DIR"), "/../../testdata/functions-parity/cases.json");
        let f: Value = serde_json::from_str(&std::fs::read_to_string(path).unwrap()).unwrap();
        for c in f["cases"].as_array().unwrap() {
            assert_eq!(Value::Array(script_functions(c["script"].as_str().unwrap())), c["expected"], "{}", c["name"]);
        }
    }
}
