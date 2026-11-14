//! The `reference` tool: what the simulator offers, so that an agent writes code that runs here before
//! it learns it from a failed run. Every topic is generated from the repository's own sources, which
//! the rest of the build already tests, so the reference cannot drift from what the engines do:
//!
//! * `script-api`: the Script API members the app's completions list (`app/src/lib/completions.ts`,
//!   checked against the Java sources by `app/tests/completions.test.mts`) and the public methods of the
//!   clean-room platform classes the Groovy engines run (`engine/groovy/src/api-common`).
//! * `platform`: the formats of the mocked platform data, from `docs/test-cases.md`.
//! * `fidelity`: what matches CPI and what does not, `docs/fidelity.md`.

pub const TOPICS: &[&str] = &["script-api", "platform", "fidelity"];

const COMPLETIONS: &str = include_str!("../../../src/lib/completions.ts");
const TEST_CASES: &str = include_str!("../../../../docs/test-cases.md");
const FIDELITY: &str = include_str!("../../../../docs/fidelity.md");

/// The clean-room classes behind `ITApiFactory` and Message Mapping functions, in the order shown.
const PLATFORM_SOURCES: &[(&str, &str)] = &[
    ("mapping", include_str!("../../../../engine/groovy/src/api-common/java/com/sap/it/api/mapping/MappingContext.java")),
    ("mapping", include_str!("../../../../engine/groovy/src/api-common/java/com/sap/it/api/msg/ExchangePropertyProvider.java")),
    ("mapping", include_str!("../../../../engine/groovy/src/api-common/java/com/sap/it/api/mapping/Output.java")),
    ("service", include_str!("../../../../engine/groovy/src/api-common/java/com/sap/it/api/mapping/ValueMappingApi.java")),
    ("service", include_str!("../../../../engine/groovy/src/api-common/java/com/sap/it/api/securestore/SecureStoreService.java")),
    ("service", include_str!("../../../../engine/groovy/src/api-common/java/com/sap/it/api/securestore/UserCredential.java")),
    ("service", include_str!("../../../../engine/groovy/src/api-common/java/com/sap/it/api/securestore/AccessTokenAndUser.java")),
    ("service", include_str!("../../../../engine/groovy/src/api-common/java/com/sap/it/api/pd/PartnerDirectoryService.java")),
    ("service", include_str!("../../../../engine/groovy/src/api-common/java/com/sap/it/api/pd/BinaryData.java")),
    ("service", include_str!("../../../../engine/groovy/src/api-common/java/com/sap/it/api/keystore/KeystoreService.java")),
    ("service", include_str!("../../../../engine/groovy/src/api-common/java/com/sap/it/api/nrc/NumberRangeConfigurationService.java")),
];

/// The member lists of `completions.ts` and what they belong to.
const MEMBER_LISTS: &[(&str, &str)] = &[
    ("MESSAGE", "Message, both Script API versions"),
    ("MESSAGE_V1", "Message, Script API v1 only (com.sap.gateway.ip.core.customdev.util.Message)"),
    ("MESSAGE_V2", "Message, Script API v2 only (com.sap.it.script.v2.api.Message)"),
    ("MESSAGE_LOG_FACTORY", "messageLogFactory (bound in the script)"),
    ("MESSAGE_LOG", "MessageLog (com.sap.it.api.msglog.MessageLog)"),
    ("IT_API_FACTORY", "ITApiFactory (com.sap.it.api.ITApiFactory), static"),
];

pub fn topic(name: &str) -> Result<String, String> {
    match name {
        "script-api" => Ok(script_api()),
        "platform" => section(TEST_CASES, "## Mocked platform data")
            .map(|s| format!("{s}\nThe files lie in the project's platform/ folder; save_file writes them.\n"))
            .ok_or_else(|| "The platform section is missing from docs/test-cases.md.".into()),
        "fidelity" => Ok(FIDELITY.to_string()),
        other => Err(format!("Unknown topic '{other}'. Topics: {}.", TOPICS.join(", "))),
    }
}

/// A Markdown section from its heading to the next heading of the same or a higher level.
pub fn section(md: &str, heading: &str) -> Option<String> {
    let level = heading.chars().take_while(|c| *c == '#').count();
    let start = md.lines().position(|l| l.starts_with(heading))?;
    let lines: Vec<&str> = md.lines().skip(start).collect();
    let end = lines.iter().skip(1)
        .position(|l| { let h = l.chars().take_while(|c| *c == '#').count(); h > 0 && h <= level && l[h..].starts_with(' ') })
        .map_or(lines.len(), |i| i + 1);
    Some(lines[..end].join("\n").trim_end().to_string() + "\n")
}

fn script_api() -> String {
    let mut out = String::from("# Script API in the simulator\n\n\
Generated from the classes the Groovy engines run; fidelity lists what is verified against CPI.\n\n\
* Script step: `def Message processData(Message message)`. Script API v1 imports \
`com.sap.gateway.ip.core.customdev.util.Message` (Groovy 2.4, runtime groovy-1.x); v2 imports \
`com.sap.it.script.v2.api.Message` (Groovy 4, groovy-2.x). run_groovy picks the runtime from the imports.\n\
* Services: `ITApiFactory.getService(ValueMappingApi.class, null)` (also SecureStoreService, PartnerDirectoryService, \
KeystoreService, NumberRangeConfigurationService) read the project's platform/ files (topic platform). \
Other services raise InvalidContextException.\n\
* Message Mapping functions (scripts of a graphical Message Mapping; the mapping itself is not simulated): \
`def String f(String a, MappingContext context)` is called with single values, \
`def void f(String[] values, Output output, MappingContext context)` once per context. \
run_groovy takes their arguments as `args`.\n");
    for (list, title) in MEMBER_LISTS {
        out.push_str(&format!("\n## {title}\n\n"));
        for (ret, name, params) in ts_members(COMPLETIONS, list) {
            out.push_str(&format!("* `{ret} {name}({params})`\n"));
        }
    }
    for (_, src) in PLATFORM_SOURCES {
        let t = java_type(src);
        out.push_str(&format!("\n## {}\n\n", t.name));
        if !t.doc.is_empty() {
            out.push_str(&format!("{}\n\n", t.doc));
        }
        for (sig, doc) in &t.members {
            if doc.is_empty() {
                out.push_str(&format!("* `{sig}`\n"));
            } else {
                out.push_str(&format!("* `{sig}`: {doc}\n"));
            }
        }
    }
    out
}

/// `m('ret', 'name', 'params')` entries of `const LIST: Member[] = [ ... ];` in completions.ts.
pub fn ts_members(ts: &str, list: &str) -> Vec<(String, String, String)> {
    let head = format!("const {list}: Member[] = [");
    let Some(start) = ts.find(&head) else { return Vec::new() };
    let body = &ts[start + head.len()..];
    let body = &body[..body.find("];").unwrap_or(body.len())];
    let mut out = Vec::new();
    let mut rest = body;
    while let Some(i) = rest.find("m(") {
        let before = rest[..i].chars().last();
        rest = &rest[i + 2..];
        if before.is_some_and(|c| c.is_alphanumeric() || c == '_') {
            continue;
        }
        let close = rest.find(')').unwrap_or(rest.len());
        let args: Vec<String> = rest[..close].split('\'').skip(1).step_by(2).map(str::to_string).collect();
        if args.len() >= 2 {
            out.push((args[0].clone(), args[1].clone(), args.get(2).cloned().unwrap_or_default()));
        }
        rest = &rest[close..];
    }
    out
}

pub struct JavaType {
    /// Fully qualified, with `interface` for interfaces.
    pub name: String,
    pub doc: String,
    /// Signature without modifiers, first sentence of its Javadoc.
    pub members: Vec<(String, String)>,
}

/// The public methods declared at the top level of a Java source's type (all methods of an interface),
/// without constructors and bodies. Strings, characters and comments are skipped when counting braces.
pub fn java_type(src: &str) -> JavaType {
    let package = src.lines().find_map(|l| l.trim().strip_prefix("package ").map(|p| p.trim_end_matches(';').trim().to_string()))
        .unwrap_or_default();
    let mut depth = 0usize;
    let mut stmt = String::new();
    let mut doc = String::new();
    let mut type_doc = String::new();
    let (mut name, mut simple, mut interface) = (String::new(), String::new(), false);
    let mut members = Vec::new();
    let chars: Vec<char> = src.chars().collect();
    let mut i = 0;
    while i < chars.len() {
        let c = chars[i];
        let next = chars.get(i + 1).copied();
        if c == '/' && next == Some('/') {
            while i < chars.len() && chars[i] != '\n' { i += 1; }
            continue;
        }
        if c == '/' && next == Some('*') {
            let end = (i + 2..chars.len().saturating_sub(1)).find(|&j| chars[j] == '*' && chars[j + 1] == '/').unwrap_or(chars.len() - 2);
            let text: String = chars[i..end + 2].iter().collect();
            if text.starts_with("/**") && depth <= 1 {
                doc = first_sentence(&text);
            }
            i = end + 2;
            continue;
        }
        if c == '"' || c == '\'' {
            let mut j = i + 1;
            while j < chars.len() && chars[j] != c {
                if chars[j] == '\\' { j += 1; }
                j += 1;
            }
            if depth <= 1 { stmt.extend(&chars[i..=j.min(chars.len() - 1)]); }
            i = j + 1;
            continue;
        }
        match c {
            '{' => {
                if depth == 0 && name.is_empty() {
                    let decl = squash(&stmt);
                    interface = decl.contains("interface ");
                    simple = decl.split_whitespace().skip_while(|w| *w != "class" && *w != "interface").nth(1)
                        .unwrap_or_default().split('<').next().unwrap_or_default().to_string();
                    name = format!("{}{package}.{simple}", if interface { "interface " } else { "" });
                    type_doc = std::mem::take(&mut doc);
                } else if depth == 1 {
                    member(&stmt, &simple, interface, &doc, &mut members);
                    doc.clear();
                }
                if depth <= 1 { stmt.clear(); }
                depth += 1;
            }
            '}' => {
                depth = depth.saturating_sub(1);
                if depth <= 1 { stmt.clear(); }
            }
            ';' if depth <= 1 => {
                if depth == 1 {
                    member(&stmt, &simple, interface, &doc, &mut members);
                    doc.clear();
                }
                stmt.clear();
            }
            _ if depth <= 1 => stmt.push(c),
            _ => {}
        }
        i += 1;
    }
    JavaType { name, doc: type_doc, members }
}

fn member(stmt: &str, class: &str, interface: bool, doc: &str, out: &mut Vec<(String, String)>) {
    let s = squash(stmt);
    let s: String = s.split_whitespace().filter(|w| !w.starts_with('@')).collect::<Vec<_>>().join(" ");
    let Some(paren) = s.find('(') else { return };
    let public = s.split_whitespace().any(|w| w == "public");
    if !(public || interface) || s.split_whitespace().any(|w| w == "private" || w == "protected") {
        return;
    }
    let method = s[..paren].split_whitespace().last().unwrap_or_default();
    if method == class || s.contains(" = ") {
        return; // a constructor or a field
    }
    let sig: String = s.split_whitespace()
        .filter(|w| !matches!(*w, "public" | "final" | "abstract" | "default" | "synchronized"))
        .collect::<Vec<_>>().join(" ");
    out.push((sig.replace("( ", "(").replace(" )", ")"), doc.to_string()));
}

fn squash(s: &str) -> String {
    s.split_whitespace().collect::<Vec<_>>().join(" ")
}

/// The first sentence of a Javadoc comment, with `{@code x}` and `{@link x}` as x and without tags.
fn first_sentence(comment: &str) -> String {
    let text: String = comment.trim_start_matches("/**").trim_end_matches("*/").lines()
        .map(|l| l.trim().trim_start_matches('*').trim())
        .take_while(|l| !l.starts_with('@'))
        .collect::<Vec<_>>().join(" ");
    let mut plain = String::new();
    let mut rest = text.as_str();
    while let Some(i) = rest.find("{@") {
        plain.push_str(&rest[..i]);
        let after = &rest[i + 2..];
        let end = after.find('}').unwrap_or(after.len());
        plain.push_str(after[..end].split_once(' ').map_or("", |(_, v)| v));
        rest = after.get(end + 1..).unwrap_or("");
    }
    plain.push_str(rest);
    let plain = squash(&plain.replace("<p>", " "));
    match plain.find(". ") {
        Some(i) => plain[..=i].to_string(),
        None => plain,
    }
}
