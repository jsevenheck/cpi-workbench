//! The MCP tools: one per tab of the app (XSLT, Groovy, XSD, XPath, Flow Steps) plus the project view,
//! making the same engine calls with the same inputs; the app's features lead and the run tools add no
//! simulation of their own. For agents without file access, read_file, save_file, import_iflow, results by
//! runId and the reference come on top (`files.rs`, `runs.rs`, `reference.rs`). A wrong argument is an
//! error message the model can act on.

use super::{files, flow, iflowmodel, reference, runs, whereused, Server};
use std::collections::HashMap;
use serde_json::{json, Map, Value};

pub type Args = Map<String, Value>;

const XSLT_RUNTIMES: &[&str] = &["saxon-he-9.9", "saxon-he-12"];
const GROOVY_RUNTIMES: &[&str] = &["groovy-1.x", "groovy-2.x"];
/// Types a header or property value can have (SimExchange / TypedValue.TYPES).
const VALUE_TYPES: &[&str] = &["String", "Integer", "Long", "Boolean", "Double", "Float", "BigDecimal", "Date", "byte[]", "null"];

const RUNTIME_LABELS: &[(&str, &str)] = &[
    ("saxon-he-9.9", "XSLT, XSD, XPath and flow steps on Saxon-HE 9.9 (CPI's Saxon line; CPI runs Saxon-EE 9.9)"),
    ("saxon-he-12", "XSLT, XSD and XPath on Saxon-HE 12"),
    ("groovy-1.x", "Groovy Script 1.x: Groovy 2.4.21, Camel 3.14.7, Script API v1"),
    ("groovy-2.x", "Groovy Script 2.x: Groovy 4.0.29, Camel 3.14.7, Script API v2"),
];

// ------------------------------------------------------------------ definitions

fn values_schema(what: &str) -> Value {
    json!({
        "type": "object",
        "description": format!("{what} by name: a string value, or {{\"value\": \"...\", \"type\": \"Integer\"}} for another Java type. \
A property of type null is left out with a warning: Camel keeps no property with a null value."),
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
    props.insert("bodyPath".into(), json!({ "type": "string", "description": "Project-relative file whose content is the message body; a file that is no text (a .zip or .gz for a Decoder) is passed as its bytes." }));
    props.insert("messageFrom".into(), json!({ "type": "string", "description": "The runId of an earlier run (or runId/part, e.g. a Splitter's run-…/2): its output message (a binary body as bytes) is the input, as the message flows to the next step in an iFlow. body, bodyPath, headers and properties given here replace the body or the rows of the same name." }));
    if headers {
        props.insert("headers".into(), values_schema("Message headers"));
        props.insert("properties".into(), values_schema("Exchange properties"));
        props.insert("brief".into(), json!({ "type": "boolean", "default": false, "description": "A shorter result for \
running steps one after another: of the output's headers and properties only those that differ from the input \
(added or changed; changes, where a step reports it, also names removed ones), and no engine, timing, trace, empty lists or the step's configuration; attachments (also the message log's) by name and size. The run keeps the whole message for \
messageFrom; run without brief to see all headers and properties." }));
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

/// What a tool does to its environment, as MCP tool annotations (hints a client may use to ask first).
#[derive(Clone, Copy, PartialEq)]
enum Effect {
    /// Reads project files only. XSLT is not among these: reflexive java: calls run any public JDK method
    /// (JavaExtensions). Groovy and Camel Simple run code without a sandbox.
    ReadOnly,
    /// Writes into the project and may replace a file (only with `overwrite`); the same call twice has the
    /// effect of one.
    WritesProject,
    /// Runs code: no hints, so clients take the protocol's defaults and assume the worst.
    RunsCode,
    /// Changes which project the other tools use and may create a folder; replaces nothing.
    OpensProject,
}

fn tool(name: &str, title: &str, description: &str, props: Map<String, Value>, required: &[&str], effect: Effect) -> Value {
    let mut t = json!({
        "name": name,
        "title": title,
        "description": description,
        "inputSchema": { "type": "object", "properties": props, "required": required, "additionalProperties": false },
    });
    match effect {
        Effect::ReadOnly => t["annotations"] = json!({ "readOnlyHint": true, "openWorldHint": false }),
        Effect::WritesProject => t["annotations"] = json!({ "readOnlyHint": false, "destructiveHint": true,
            "idempotentHint": true, "openWorldHint": false }),
        Effect::OpensProject => t["annotations"] = json!({ "readOnlyHint": false, "destructiveHint": false,
            "idempotentHint": true, "openWorldHint": false }),
        Effect::RunsCode => {}
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
        ("function", json!({ "type": "string", "default": "processData", "description": "The function the step calls (Script Function), \
or a custom function of a Message Mapping script: f(String, MappingContext) (single values) or f(String[], Output, MappingContext) \
(all values of a context: called once per context). read_file on the script lists its functions." })),
        ("args", json!({ "type": "array", "description": "Message Mapping functions only: the String and String[] arguments in order \
(Output and MappingContext are passed by the simulator). A String argument is a string; a String[] argument is a list of contexts, \
e.g. [[\"A\", \"B\"], [\"C\"]], whose entries are strings, {\"suppress\": true} or {\"xsiNil\": true}. The result's mapping \
holds the returned value or the output queue in the same form." })),
    ]);
    message_properties(&mut groovy, true);
    groovy.insert("bodyType".into(), json!({ "type": "string", "enum": ["String", "byte[]", "InputStream"], "default": "String",
        "description": "The Java type of the incoming body, what message.getBody() without a class returns: String (default), \
byte[] or InputStream. InputStream gives Camel's InputStreamCache, the type a tenant's HTTPS sender handed on (reference \
fidelity, Stream body; project_info with iflow lists an iFlow's senders); after the step it is reset, so the body passes on whole." }));
    groovy.insert("runtime".into(), runtime(GROOVY_RUNTIMES, true, None,
        "groovy-1.x (Script API v1) or groovy-2.x (v2). Default: from the script's imports (com.sap.it.script.v2.api → 2.x), else 1.x. both runs the two. \
For an iFlow's script step, give the runtime project_info lists for it."));

    let mut xsd = obj(vec![
        ("schemaPath", json!({ "type": "string", "description": "Project-relative .xsd file. Use schemaPath or schema." })),
        ("schema", json!({ "type": "string", "description": "The XSD source, if it is not in a file." })),
    ]);
    message_properties(&mut xsd, false);
    xsd.insert("runtime".into(), runtime(XSLT_RUNTIMES, false, Some("saxon-he-9.9"),
        "The engine process that runs the check. Both validate with the JDK's XML Schema 1.0 validator, not with Saxon."));

    let mut xpath = obj(vec![
        ("expression", json!({ "type": "string", "description": "The XPath." })),
        ("version", json!({ "type": "string", "enum": ["1.0", "3.1"], "default": "3.1", "description": "The XPath version: 3.1 \
(default) or 1.0, e.g. to check a Message Digest's filter, which is XPath 1.0." })),
        ("namespaces", ns.clone()),
    ]);
    message_properties(&mut xpath, false);
    xpath.insert("runtime".into(), runtime(XSLT_RUNTIMES, false, Some("saxon-he-9.9"), "The Saxon version that evaluates it."));

    let mut step = obj(vec![
        ("iflowStep", json!({ "type": "string", "description": "Name or id of a step that project_info lists under iflowSteps. Use iflowStep or step." })),
        ("iflow", json!({ "type": "string", "description": "The iFlow of iflowStep, when several iFlows have a step with that name or id." })),
        ("step", json!({ "type": "object", "description": "A step given directly: {\"type\": \"contentModifier\"|\"filter\"|\"router\"|\"splitter\"|\"gather\"|\"converter\"|\"encoder\"|\"decoder\"|\"messageDigest\", \"config\": {...}}, config shaped as project_info shows it for iFlow steps. To try another configuration of an iFlow's step (how a Content Modifier or Router should be set up), copy its type and config from project_info, change it, and run it here with the same input.",
            "properties": {
                "type": { "type": "string", "enum": ["contentModifier", "filter", "router", "splitter", "gather", "converter", "encoder", "decoder", "messageDigest"] },
                "config": { "type": "object", "description": "The step's settings (keys left out take the defaults of a new step). \
contentModifier: {headers: [row], properties: [row], bodyType: \"constant\"|\"expression\", body}, row = {action: \"Create\"|\"Delete\", name, \
type: \"constant\"|\"header\"|\"property\"|\"xpath\"|\"expression\", value, datatype (e.g. \"java.lang.String\"), default} (row keys left out are empty). \
filter: {expression (XPath), valueType: \"string\"|\"integer\"|\"boolean\"|\"node\"|\"nodelist\"}. \
router: {routes: [{name, expressionType: \"xml\" (condition is an XPath)|\"nonxml\" (condition is Camel Simple, e.g. ${header.SenderId} = 'ERP'), condition, default: bool}], throwException: bool}; SAP's =, and, or in a Simple condition run as Camel's ==, &&, || \
(the result's camel field; reference fidelity says what is assumed). \
splitter: {splitter: \"general\"|\"iterating\", expressionType: \"xpath\"|\"lineBreak\"|\"token\", expression, grouping, streaming: bool}. \
gather: {messageType: \"sameXml\"|\"differentXml\"|\"plainText\", algorithm: \"combine\"|\"combineAtXPath\"|\"concatenate\", sourceXPath, targetXPath}. \
converter: {conversion: \"jsonToXml\"|\"xmlToJson\"|\"xmlToCsv\"|\"csvToXml\"; JSON/XML: useNamespaces, separator, \
namespaceMapping ([{prefix, namespace}] or lines \"prefix = uri\"), addRootElement, rootElementName, rootNamespace (a prefix of namespaces), \
suppressRoot, streaming, streamingElements: \"all\"|\"specific\", arrayPaths, outputEncoding; XML to CSV: sourcePath, fieldSeparator, \
csvHeader: \"none\"|\"xml\", includeParent, parentPath, includeAttributes; CSV to XML: schema (project .xsd), targetPath, recordMarker, \
fieldSeparator, firstLineHeader, headerMapping: \"xsd\"|\"csv\"}. \
encoder: {scheme: \"base64Encode\"|\"gzipCompress\"|\"zipCompress\"} (MIME Multipart is not simulated; ZIP names its entry after the \
header CamelFileName). decoder: {scheme: \"base64Decode\"|\"gzipDecompress\"|\"zipDecompress\"}. A result that is no text has binary: true, \
bodyBase64 (left out when long), bodySize and a hex preview; messageFrom passes it on as bytes. \
messageDigest: {filter (XPath 1.0 that selects one element; empty: the whole message), canonicalization: \"c14n10\"|\"c14n10Comments\"|\
\"exclusive\"|\"exclusiveComments\"|\"c14n11\"|\"c14n11Comments\"|\"none\", algorithm: \"MD5\"|\"SHA1\"|\"SHA256\"|\"SHA384\"|\"SHA512\", \
targetHeader}; the result has header {name, value}, the canonical text and the output message with the header." } },
            "required": ["type", "config"], "additionalProperties": false })),
        ("messages", json!({ "type": "array", "items": { "type": "string" },
            "description": "Gather only: the bodies to gather, e.g. the messages a splitter returned. Use messages or messagesFrom." })),
        ("messagesFrom", json!({ "anyOf": [{ "type": "string" }, { "type": "array", "items": { "type": "string" } }],
            "description": "Gather only: the runId of a Splitter run (all its messages, in order), or a list of runIds or runId/part." })),
        ("namespaces", ns),
    ]);
    message_properties(&mut step, true);

    let path = |what: &str| json!({ "type": "string", "description": what });
    let read = obj(vec![
        ("path", path("Project-relative file, as project_info lists it (e.g. iflows/Order/src/main/resources/script/check.groovy). Use path or runId.")),
        ("runId", path("The runId (or runId/part) of an earlier run: its whole output body, e.g. when the result showed it shortened. A binary body comes back as contentBase64.")),
        ("binary", json!({ "type": "boolean", "default": false, "description": "With path: the file's bytes as contentBase64, for a file that is no text (an archive)." })),
        ("startLine", json!({ "type": "integer", "minimum": 1, "default": 1, "description": "First line to return (1-based)." })),
        ("maxLines", json!({ "type": "integer", "minimum": 1, "default": 2000, "description": "Lines to return at most; long files come in parts." })),
        ("startChar", json!({ "type": "integer", "minimum": 0, "description": "Read by characters instead of lines, from this 0-based offset (200,000 at most per call): for text in one long line, such as minified XML or JSON." })),
    ]);
    let save = obj(vec![
        ("path", path("Project-relative file to write, in the folder of its kind: mappings/*.xsl, scripts/*.groovy, \
schemas/*.xsd, inputs/*.xml|json|csv, platform/*.json. Folders are created.")),
        ("content", json!({ "type": "string", "description": "The whole file content (text). Use content, contentBase64 or contentFrom." })),
        ("contentBase64", json!({ "type": "string", "description": "The file's bytes as Base64, for a file that is no text." })),
        ("contentFrom", path("The runId (or runId/part) of an earlier run: saves its output body, e.g. a mapping's result as a new input; a binary body (GZIP, ZIP) is saved as its bytes.")),
        ("overwrite", json!({ "type": "boolean", "default": false, "description": "Replace an existing file. Without it, an existing file is an error." })),
    ]);
    let import = obj(vec![
        ("zip", path("The iFlow, Value Mapping or integration package exported from the tenant (.zip): an absolute path, or a path inside the project.")),
        ("name", path("Folder name under iflows/ for an iFlow; default: the zip's file name. A package's artifacts keep their ids.")),
        ("overwrite", json!({ "type": "boolean", "default": false, "description": "Import into existing iflows/ folders, replacing their files, and replace value mappings of the same agencies and identifiers." })),
    ]);
    let where_used = obj(vec![
        ("query", json!({ "type": "string", "description": "A value to find (a host, credential alias, queue, file, {{parameter}} or any text; ignoring case), or a property key (address) to list it everywhere." })),
        ("kind", json!({ "type": "string", "enum": ["address", "credential", "queue", "key", "file", "parameter"], "description": "Only properties that hold this, where the key says so (address, credential, queue, key: a keystore alias), the files steps use, or values with {{parameters}}." })),
        ("iflow", json!({ "type": "string", "description": "Only this iFlow (its folder under iflows/, as project_info lists it)." })),
        ("parameters", json!({ "type": "boolean", "default": false, "description": "Only each iFlow's externalized parameters with their values from parameters.prop, where they are used, and which are unused or have no value." })),
    ]);
    let open = obj(vec![
        ("name", json!({ "type": "string", "description": "A folder directly inside the workspace, as project_info lists it under projects (one folder name, no path)." })),
        ("create", json!({ "type": "boolean", "default": false, "description": "Create the folder as a new project (the standard layout with example platform/ data, as the app's Import iFlow without a project does). A folder that exists is an error." })),
    ]);
    let diagram = obj(vec![
        ("iflow", json!({ "type": "string", "description": "The iFlow: its folder under iflows/, as project_info lists it with each step. Needed when the project has more than one." })),
        ("settings", json!({ "type": "boolean", "default": true, "description": "false leaves out the elements' and adapters' settings, for the order of the steps and routes alone." })),
    ]);
    let reference = obj(vec![
        ("topic", json!({ "type": "string", "enum": reference::TOPICS, "description": "script-api: the Script API, \
Message Mapping and platform service methods the Groovy engines offer. platform: the formats of the mocked platform/ files \
(value mappings, secure store, partner directory, keystore, number ranges). fidelity: what is simulated and verified against \
CPI, per step type, and what is not." })),
        ("step", json!({ "type": "string", "description": "With topic fidelity: only the rows for this step (e.g. router, contentModifier, groovy, xslt, splitter), not the whole page." })),
    ]);

    vec![
        tool("project_info", "Project, runtimes and iFlow steps",
            "The open project: its files, the steps of the iFlows under iflows/ and the installed runtimes. A flow step \
             comes with its type and config (run_flow_step runs it by name, or a changed copy as step); a script, XSLT or \
             XML Validator step with its file, function and runtime for run_groovy, run_xslt or validate_xsd \
             (functionFound: false when its script does not declare the function). iflow_diagram shows the order of the \
             steps. With several iFlows (a package), iflows lists each iFlow's steps by name and type, without \
             configuration, and the iFlows' files are left out: give iflow for one iFlow's steps and files. With a workspace, also its project folders \
             (projects). Call this first.",
            obj(vec![("iflow", json!({ "type": "string", "description": "Only this iFlow (its folder under iflows/, as listed in iflows or with each step): its steps with configuration and functionFound, and only its own files plus the script collection files its steps use (the project's other files come without iflow), and its sender adapters (senders). The steps are in the order the export lists them; iflow_diagram gives the flow order." }))]),
            &[], Effect::ReadOnly),
        tool("open_project", "Open or create a project in the workspace",
            "Only when the server was started with --workspace: opens one of the workspace's folders as the project the \
             other tools work in (create: true makes a new one), and returns its project_info without the runtimes; \
             for several iFlows only each iFlow's number of steps (stepCount: the steps the simulator runs, i.e. flow \
             steps, scripts, mappings and validators; not events, Process Calls or adapter calls, so an iFlow of only \
             those has 0). \
             Earlier runIds stay usable.",
            open, &["name"], Effect::OpensProject),
        tool("read_file", "Read a project file",
            "Reads a file of the project (a file that is no text with binary: true): an input message, a mapping, a script, a schema, an iFlow's files or the \
             platform/ data. Long files come in parts (startLine, maxLines). A Groovy script also lists the functions it \
             declares (functions, read from its text; nothing runs).",
            read, &[], Effect::ReadOnly),
        tool("run_xslt", "Run an XSLT mapping",
            "Runs an XSLT mapping like CPI's XSLT Mapping step: headers and properties are xsl:param values, the result \
             is the new body. Returns the output message, xsl:message output and diagnostics. An unchanged body comes back \
             as bodyUnchanged, a long one shortened (bodyShortened); read_file with the runId gives it whole.",
            xslt, &[], Effect::RunsCode),
        tool("run_groovy", "Run a Groovy script",
            "Runs a Groovy script like CPI's Groovy Script step with the clean-room Script API (Message, MessageLog, \
             ITApiFactory with the project's platform/ files), or one custom function of a Message Mapping's script \
             (the graphical mapping itself is not simulated). Returns the output message, log entries and diagnostics. \
             An unchanged body comes back as bodyUnchanged, a long one shortened (bodyShortened). \
             The script runs without a sandbox. reference script-api lists the API.",
            groovy, &[], Effect::RunsCode),
        tool("validate_xsd", "Validate XML against an XSD",
            "Validates the body against an XML schema (XSD 1.0, the JDK's validator), as a stand-in for CPI's XML Validator, whose validator is not verified (reference fidelity). Returns valid and the errors with lines.",
            xsd, &[], Effect::ReadOnly),
        tool("evaluate_xpath", "Evaluate an XPath",
            "Evaluates an XPath on the body, like the XPath tab. Returns the value or the selected nodes.",
            xpath, &["expression"], Effect::ReadOnly),
        tool("run_flow_step", "Run a flow step",
            "Runs a Content Modifier, Filter, Router, General or Iterating Splitter, Gather, converter (JSON/XML/CSV), Encoder or Decoder \
             (Base64, GZIP, ZIP) or Message Digest step on a message, like the \
             Flow Steps tab, from an iFlow of the project or from a config given here. Returns the output message(s), or \
             for a Router each route's result and the route taken: its runId passes the input on unchanged, as the \
             message goes to that route's first step; run that route's steps next yourself, with messageFrom. \
             statusNote explains the route statuses (true, false, not evaluated, default). For an iFlow's script, XSLT or XML Validator step, \
             use run_groovy, run_xslt or validate_xsd with the step's file, function and profile from project_info. \
             An unchanged body comes back as bodyUnchanged (a Content Modifier that sets only headers, for instance), a \
             long one shortened (bodyShortened).",
            step, &[], Effect::RunsCode),
        tool("save_file", "Save a file to the project",
            "Writes a file into the project (text, or bytes with contentBase64 or a binary run result), e.g. a mapping, script, schema, input message or platform/ data the user \
             wants to keep; the desktop app shows it once its file tree is refreshed. Save only what the user asked for. An existing file \
             is replaced only with overwrite: true.",
            save, &["path"], Effect::WritesProject),
        tool("import_iflow", "Import an iFlow, Value Mapping or package",
            "Imports what the tenant exports as a .zip, as the app's Import does: an iFlow into iflows/<name>/; a Value \
             Mapping as rows of platform/value-mappings.json (both directions); an integration package with each of its \
             iFlows, script collections and value mappings (other artifact types are listed as skipped). Returns the \
             imported iFlows' steps; their files open with read_file and run with the tools above.",
            import, &["zip"], Effect::WritesProject),
        tool("iflow_where_used", "Where used in the imported iFlows",
            "The Where used tab for the iFlows under iflows/ (read-only, nothing connects to a tenant). Without query or \
             kind: an overview of the addresses, credentials, queues, key aliases and files they use, with the number of \
             places and what {{parameters}} resolve to, and each iFlow's externalized parameters. With query and/or kind: \
             which adapters, steps and routes use a value (iFlow, element, property key), a {{parameter}} resolved from \
             parameters.prop, a Content Modifier table as its matching rows, and the parameters whose name or value \
             contains the query. With parameters: true, only the parameters.",
            where_used, &[], Effect::ReadOnly),
        tool("iflow_diagram", "How an imported iFlow flows",
            "The Diagram tab as text (read-only): an imported iFlow's processes (the integration process, exception \
             subprocesses, local processes), each with its elements in flow order from the start event. A Router's \
             routes are indented under it (depth + 1): a route's first element carries route (name, condition, \
             default), and the route's further elements follow it until the next element with a route at that depth; \
             each element on a route also names it (onRoute: router and route). \
             Where branches meet, the element is listed again (again: true) without what follows it: the elements after \
             it are listed once, at its first occurrence. A Process Call names the local process it \
             calls (calls). Each element has its type as in the editor and its settings as written in the iFlow, a Content \
             Modifier table as one object per row (cell id to text) \
             ({{parameters}} unresolved; project_info's configs, which run_flow_step runs, and iflow_where_used resolve \
             them); adapters name \
             their system, direction and settings (settings: false leaves all settings out). Use it to follow \
             a message through the iFlow, then run the steps in that order with run_flow_step, run_groovy, run_xslt and \
             validate_xsd, passing each result on with messageFrom.",
            diagram, &[], Effect::ReadOnly),
        tool("reference", "Simulator reference",
            "What the simulator offers, generated from its sources: the Script API (script-api), the platform/ data formats \
             (platform) and what is verified against CPI (fidelity; with step only that step's rows). Read it before writing a script that uses \
             services or mapping functions.",
            reference, &["topic"], Effect::ReadOnly),
    ]
}

pub fn exists(name: &str) -> bool {
    definitions().iter().any(|t| t["name"] == name)
}

// ------------------------------------------------------------------ calls

/// The input message of a run, resolved once before it runs (`call`): the step and the kept result see the same
/// message, even if the script changes or deletes its bodyPath file. Set after `check_args`, so no client can.
const RESOLVED_INPUT: &str = "\u{0}input";
/// A Gather's `messagesFrom`, resolved once with the input (its first message is the input's base).
const RESOLVED_MESSAGES: &str = "\u{0}messages";

pub fn call(server: &Server, name: &str, args: &Args) -> Result<Value, String> {
    check_args(name, args)?;
    let runs_step = matches!(name, "run_xslt" | "run_groovy" | "validate_xsd" | "run_flow_step");
    let mut args = args.clone();
    if runs_step {
        if let Some(refs) = args.get("messagesFrom") {
            let ms: Vec<Value> = message_refs(server, refs)?.iter().map(runs::Message::to_json).collect();
            args.insert(RESOLVED_MESSAGES.into(), Value::Array(ms));
        }
        let m = message(server, &args)?;
        args.insert(RESOLVED_INPUT.into(), m);
    }
    let mut result = dispatch(server, name, &args)?;
    // A run that produced a message keeps it under a runId; long bodies come back shortened.
    if runs_step && result["ok"] != false && result["valid"] != false {
        let mut input = runs::Message::from_output(&args[RESOLVED_INPUT])
            .unwrap_or_else(|| runs::Message::text("", json!([]), json!([])));
        input.body_class = input_class(server, &args);
        keep_run(server, name, &input, &mut result);
    }
    // The class the run hands the body on as, where it kept the input's (a Content Modifier that left a stream body
    // as it was): the engine reports the text it worked on.
    if let Some(class) = result["runId"].as_str().and_then(|id| server.runs().message(id).ok()?.body_class) {
        if let Some(out) = result.get_mut("output").filter(|o| o.get("bodyType").is_some()) {
            out["bodyType"] = json!(class);
        }
    }
    if args.get("brief").and_then(Value::as_bool) == Some(true) {
        runs::brief(&mut result, args.get(RESOLVED_INPUT).unwrap_or(&Value::Null));
    }
    Ok(result)
}

/// Keeps the message a run produced under a runId and shortens the result's bodies. A run too large for the
/// cache still returns its (shortened) result, without a runId and with `runNotice` saying why.
pub(super) fn keep_run(server: &Server, name: &str, input: &runs::Message, result: &mut Value) {
    match server.runs().keep(runs::produced(name, input, result)) {
        Ok(Some(id)) => {
            runs::shorten(result, Some(&id), &input.body);
            result["runId"] = json!(id);
        }
        Ok(None) => {}
        Err(notice) => {
            runs::shorten(result, None, &input.body);
            result["runNotice"] = json!(notice);
        }
    }
}

fn dispatch(server: &Server, name: &str, args: &Args) -> Result<Value, String> {
    match name {
        "project_info" => project_info(server, args),
        "open_project" => files::open_project(server, args).and_then(|created| {
            let mut info = project_info(server, &Args::new())?;
            info.as_object_mut().unwrap().remove("runtimes"); // the server's, the same in every project
            // A package: only each iFlow's number of steps; project_info names them, with iflow in full.
            if let Some(iflows) = info.get_mut("iflows").and_then(Value::as_array_mut) {
                for i in iflows.iter_mut() {
                    let n = i["steps"].as_array().map_or(0, Vec::len);
                    i.as_object_mut().unwrap().remove("steps");
                    i["stepCount"] = json!(n);
                }
                info["summary"] = json!("Several iFlows, each with stepCount: the steps the simulator runs (flow steps, \
                    scripts, mappings, validators), not events, Process Calls or adapter calls, so an iFlow of only those \
                    has 0; iflow_diagram shows all its elements. project_info lists each iFlow's steps by name and type; \
                    project_info with iflow gives one iFlow's steps in full and its files.");
            }
            info["created"] = json!(created);
            Ok(info)
        }),
        "run_xslt" => run_xslt(server, args),
        "run_groovy" => run_groovy(server, args),
        "validate_xsd" => validate_xsd(server, args),
        "evaluate_xpath" => evaluate_xpath(server, args),
        "run_flow_step" => flow::run(server, args),
        "read_file" => files::read_file(server, args),
        "save_file" => files::save_file(server, args),
        "import_iflow" => files::import_iflow(server, args),
        "iflow_where_used" => where_used(server, args),
        "iflow_diagram" => iflow_diagram(server, args),
        "reference" => {
            let topic = str_arg(args, "topic").unwrap_or_default();
            let text = match str_arg(args, "step") {
                Some(step) if topic == "fidelity" => reference::fidelity_for(step)?,
                Some(_) => return Err("step goes with topic fidelity.".into()),
                None => reference::topic(topic)?,
            };
            Ok(json!({ "topic": topic, "text": text }))
        }
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
            Some("integer") => v.as_u64().is_some_and(|n| n >= p["minimum"].as_u64().unwrap_or(0)),
            Some("boolean") => v.is_boolean(),
            Some("array") => v.as_array().is_some_and(|a| p["items"]["type"] != "string" || a.iter().all(Value::is_string)),
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

/// Camel Simple and the Content Modifier: both Groovy engines carry Camel 3.14.7, as a tenant does (F48).
pub fn camel_runtime(server: &Server) -> &'static str {
    installed_or(server, "groovy-2.x", GROOVY_RUNTIMES)
}

/// Any installed engine that can read project files.
pub fn file_runtime(server: &Server) -> Result<&'static str, String> {
    ["saxon-he-9.9", "saxon-he-12", "groovy-1.x", "groovy-2.x"].into_iter()
        .find(|p| server.engines().locate(p).is_some())
        .ok_or_else(|| "No engine is installed.".to_string())
}

/// An engine call on the open project: every call carries it (`project.request`), as the desktop app's do, so a
/// restarted engine and a project opened with open_project need no separate `project.open`.
pub fn engine_call(server: &Server, runtime: &str, method: &str, params: Value) -> Result<Value, String> {
    let root = server.project().map(|p| p.to_string_lossy().into_owned());
    server.engines().call_desktop(runtime, method, params, root)
}

pub fn require_project(server: &Server) -> Result<(), String> {
    match (server.project(), server.workspace()) {
        (Some(_), _) => Ok(()),
        (None, Some(_)) => Err("No project is open: open_project opens one of the workspace's folders (project_info lists them under projects).".into()),
        (None, None) => Err("No project is open; start the server with --project <folder>.".into()),
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

/// The incoming message: {body, headers, properties}. With messageFrom (or, for a Gather, the first message of
/// messagesFrom) an earlier run's output is the base; what the call gives replaces its body or rows.
pub fn message(server: &Server, args: &Args) -> Result<Value, String> {
    if let Some(m) = args.get(RESOLVED_INPUT) {
        return Ok(m.clone());
    }
    let base = match (str_arg(args, "messageFrom"), args.get("messagesFrom")) {
        (Some(r), _) => Some(server.runs().message(r)?),
        (None, Some(_)) => messages_from(server, args)?.into_iter().next(),
        _ => None,
    };
    let (headers, properties) = (value_rows(args, "headers")?, value_rows(args, "properties")?);
    let (headers, properties) = match &base {
        Some(m) => (runs::merge_rows(&m.headers, &headers), runs::merge_rows(&m.properties, &properties)),
        None => (headers, properties),
    };
    let message = match one_of(args, "body", "bodyPath", true)? {
        (Some(b), _) => runs::Message::text(b, headers, properties),
        (_, Some(p)) => {
            let mut m = read_body(server, p)?;
            (m.headers, m.properties) = (headers, properties);
            m
        }
        _ => match base {
            Some(m) => runs::Message { headers, properties, ..m },
            None => runs::Message::text("", headers, properties),
        },
    };
    Ok(message.to_json())
}

/// A project file as a message body: its text, or its bytes when it is no text (an archive for a Decoder).
fn read_body(server: &Server, path: &str) -> Result<runs::Message, String> {
    match read_file(server, path) {
        Ok(t) if !t.contains('\0') => Ok(runs::Message::text(&t, json!([]), json!([]))),
        Ok(_) => read_bytes(server, path),
        Err(e) if e.contains("cannot be read as text") => read_bytes(server, path),
        Err(e) => Err(e),
    }
}

/// A project file's bytes, as Base64.
fn read_bytes(server: &Server, path: &str) -> Result<runs::Message, String> {
    let r = engine_call(server, file_runtime(server)?, "file.read", json!({ "path": path, "binary": true }))?;
    Ok(runs::Message { body: String::new(), body_base64: Some(r["contentBase64"].as_str().unwrap_or_default().to_string()),
        headers: json!([]), properties: json!([]), body_class: None })
}

/// The class the input's body has after the step `messageFrom` names, when this call keeps that body (no body or
/// bodyPath of its own): a Groovy step's InputStreamCache stays one through steps that leave the body as it is.
fn input_class(server: &Server, args: &Args) -> Option<String> {
    if args.contains_key("body") || args.contains_key("bodyPath") {
        return None;
    }
    server.runs().message(str_arg(args, "messageFrom")?).ok()?.body_class
}

/// The messages of `messagesFrom`, as the call resolved them before the step (`call`), or now.
pub fn messages_from(server: &Server, args: &Args) -> Result<Vec<runs::Message>, String> {
    match args.get(RESOLVED_MESSAGES) {
        Some(Value::Array(ms)) => Ok(ms.iter().filter_map(runs::Message::from_output).collect()),
        _ => message_refs(server, args.get("messagesFrom").unwrap_or(&Value::Null)),
    }
}

/// The messages of `messagesFrom`: one run id or runId/part, or a list of them, in order.
pub fn message_refs(server: &Server, refs: &Value) -> Result<Vec<runs::Message>, String> {
    let list: Vec<&str> = match refs {
        Value::String(s) => vec![s.as_str()],
        Value::Array(a) if a.iter().all(Value::is_string) => a.iter().filter_map(Value::as_str).collect(),
        _ => return Err("messagesFrom is a runId or a list of runIds.".into()),
    };
    let mut out = Vec::new();
    for r in list {
        out.extend(server.runs().messages(r)?);
    }
    Ok(out)
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

fn project_info(server: &Server, args: &Args) -> Result<Value, String> {
    let runtimes: Vec<Value> = RUNTIME_LABELS.iter()
        .map(|(id, label)| json!({ "id": id, "label": label, "installed": server.engines().locate(id).is_some() }))
        .collect();
    let mut info = json!({ "project": server.project().map(|p| p.to_string_lossy().to_string()), "runtimes": runtimes });
    let only = str_arg(args, "iflow");
    if let Some(w) = server.workspace() {
        info["workspace"] = json!(w.to_string_lossy());
        info["projects"] = json!(files::workspace_projects(w)?);
    }
    if server.project().is_none() {
        return Ok(info);
    }
    let rt = file_runtime(server)?;
    let listing = engine_call(server, rt, "project.list", json!({}))?;
    const MAX_FILES: usize = 1000;
    let mut files: Vec<Value> = listing["files"].as_array().into_iter().flatten()
        .filter(|f| f["dir"] != true).map(|f| f["path"].clone()).collect();
    if files.len() > MAX_FILES {
        info["filesTruncated"] = json!(files.len());
    }
    let mut steps: Vec<Value> = engine_call(server, rt, "iflow.steps", json!({}))?["steps"].as_array().cloned().unwrap_or_default();
    // Every folder with an iFlow model, also one without runnable steps (only adapter calls).
    let mut iflows: Vec<String> = Vec::new();
    let models = files.iter().filter_map(|f| f.as_str()?.strip_prefix("iflows/")?.split_once("/src/main/resources/scenarioflows/integrationflow/"));
    for i in models.map(|(i, _)| i).chain(steps.iter().filter_map(|s| s["iflow"].as_str())) {
        if !iflows.iter().any(|x| x == i) { iflows.push(i.to_string()); }
    }
    let under = |f: &Value, i: &str| f.as_str().is_some_and(|f| f.starts_with(&format!("iflows/{i}/")));
    if let Some(i) = only {
        if !iflows.iter().any(|x| x == i) {
            return Err(format!("No iFlow '{i}' under iflows/. The iFlows: {iflows:?}."));
        }
        steps.retain(|s| s["iflow"] == i);
        // Who sends to the iFlow, as the Diagram tab shows it: a script's body type follows from it (an HTTPS
        // sender hands on a stream, fidelity.md, Stream body).
        let models = engine_call(server, rt, "iflow.models", json!({}))?;
        if let Some(model) = models["models"].as_array().into_iter().flatten().find(|m| m["iflow"] == i) {
            info["senders"] = Value::Array(iflowmodel::adapters(model).into_iter().filter(|(_, _, inbound)| *inbound)
                .map(|(f, system, _)| json!({ "adapter": f["adapter"], "system": system.map(|n| n["name"].clone()) }))
                .collect());
        }
        // Only the iFlow's own files and those its steps use (a package's script collection lies beside it); the
        // project's other files are in project_info without iflow, which an agent has usually called already.
        let used: Vec<&Value> = steps.iter().map(|s| &s["file"]).filter(|f| f.is_string()).collect();
        files.retain(|f| under(f, i) || used.contains(&f));
    } else if iflows.len() > 1 {
        // A package: the steps of all its iFlows with their configuration overflow a model's context.
        // Grouped by iFlow, each step by name and type: ids such as CallActivity_1 repeat in every iFlow.
        files.retain(|f| !f.as_str().unwrap_or_default().starts_with("iflows/"));
        info["iflows"] = Value::Array(iflows.iter().map(|i| json!({ "iflow": i, "steps": steps.iter()
            .filter(|s| s["iflow"] == i.as_str()).map(|s| json!({ "name": s["name"], "type": s["type"] })).collect::<Vec<_>>() }))
            .collect());
        steps.clear();
        info["summary"] = json!("Several iFlows: each iFlow's steps by name and type, without configuration, and the \
            files under iflows/ left out. Call project_info with iflow for one iFlow's steps (config, file, function, \
            functionFound) and files.");
    }
    if files.len() > MAX_FILES {
        info["filesTruncated"] = json!(files.len());
    }
    info["files"] = Value::Array(files.into_iter().take(MAX_FILES).collect());
    if info.get("summary").is_none() {
        info["iflowSteps"] = Value::Array(steps);
        mark_functions(server, &mut info["iflowSteps"]);
    }
    Ok(info)
}

/// A script step whose function the script does not declare fails only when it runs: `functionFound` says so up
/// front, from the script's text as the Groovy tab reads it (nothing runs). For project_info and import_iflow.
pub fn mark_functions(server: &Server, steps: &mut Value) {
    for step in steps.as_array_mut().into_iter().flatten() {
        let (Some(file), Some(function)) = (step["file"].as_str(), step["function"].as_str()) else { continue };
        if step["fileFound"] != true || !(file.ends_with(".groovy") || file.ends_with(".gsh")) { continue; }
        if let Ok(script) = read_file(server, file) {
            let found = super::functions::script_functions(&script).iter().any(|f| f["name"] == function);
            step["functionFound"] = json!(found);
        }
    }
}

/// The Where used tab: without query or kind its overview (what the iFlows use, and their parameters); with them
/// `iflow.search`'s hits, each with its `{{parameters}}` resolved and a Content Modifier table's matching rows,
/// plus the parameters whose name or value contains the query; with `parameters` only the parameters. All from
/// `iflow.index` and `iflow.search` (IflowIndex.java), viewed as the tab does (whereused.rs).
fn where_used(server: &Server, args: &Args) -> Result<Value, String> {
    require_project(server)?;
    let rt = file_runtime(server)?;
    let iflow = str_arg(args, "iflow");
    let only_parameters = args.get("parameters").and_then(Value::as_bool) == Some(true);
    if only_parameters && (args.contains_key("query") || args.contains_key("kind")) {
        return Err("parameters lists the parameters; give query or kind without it.".into());
    }
    let index: Vec<Value> = engine_call(server, rt, "iflow.index", json!({}))?["iflows"].as_array().cloned().unwrap_or_default()
        .into_iter().filter(|i| iflow.is_none_or(|f| i["iflow"] == f)).collect();
    let parameters = |index: &[Value]| -> Vec<Value> { index.iter()
        .map(|i| json!({ "iflow": i["iflow"], "name": i["name"], "error": i["error"], "parameters": i["parameters"] })).collect() };
    if only_parameters {
        return Ok(json!({ "iflows": parameters(&index) }));
    }
    let query = str_arg(args, "query").unwrap_or_default().trim();
    let kind = str_arg(args, "kind");
    if query.is_empty() && kind.is_none() {
        return Ok(json!({ "uses": whereused::inventory(&index), "iflows": parameters(&index) }));
    }
    let mut params = json!({ "query": query });
    for key in ["kind", "iflow"] {
        if let Some(v) = args.get(key) {
            params[key] = v.clone();
        }
    }
    let mut r = engine_call(server, rt, "iflow.search", params)?;
    let values: HashMap<String, HashMap<String, String>> = index.iter()
        .map(|i| (i["iflow"].as_str().unwrap_or_default().to_string(), whereused::parameter_values(i))).collect();
    let none = HashMap::new();
    for h in r["hits"].as_array_mut().into_iter().flatten() {
        let p = values.get(h["iflow"].as_str().unwrap_or_default()).unwrap_or(&none);
        let value = h["value"].as_str().unwrap_or_default().to_string();
        if let Some(mut t) = whereused::table_hit(&value, query) {
            // The tab shows the matching rows as cells instead of the table's XML text.
            for c in t["rows"].as_array_mut().into_iter().flatten().flat_map(|row| row.as_array_mut().into_iter().flatten()) {
                if let Some(v) = whereused::resolve(c["text"].as_str().unwrap_or_default(), p) {
                    c["resolved"] = json!(v);
                }
            }
            h.as_object_mut().unwrap().remove("value");
            h["rows"] = t["rows"].take();
            h["hiddenRows"] = t["hidden"].take();
        } else if let Some(v) = whereused::resolve(&value, p) {
            h["resolved"] = json!(v);
        }
    }
    // Parameters by name or value, unless the kind asks for another kind of setting (as the tab does).
    if kind.is_none_or(|k| k == "parameter") {
        r["parameters"] = json!(parameters(&whereused::matching_parameters(&index, query)));
    }
    Ok(r)
}

/// `iflow.models` (IflowModel.java, which the Diagram tab draws), read as the tab's outline (iflowmodel.rs).
fn iflow_diagram(server: &Server, args: &Args) -> Result<Value, String> {
    require_project(server)?;
    let models = engine_call(server, file_runtime(server)?, "iflow.models", json!({}))?["models"].as_array().cloned().unwrap_or_default();
    let folders: Vec<&str> = models.iter().filter_map(|m| m["iflow"].as_str()).collect();
    let model = match str_arg(args, "iflow") {
        Some(f) => models.iter().find(|m| m["iflow"] == f)
            .ok_or_else(|| format!("No iFlow '{f}' under iflows/. The iFlows: {folders:?}."))?,
        None if models.len() == 1 => &models[0],
        None if models.is_empty() => return Err("The project has no iFlows under iflows/; import_iflow imports one.".into()),
        None => return Err(format!("The project has several iFlows; give iflow, one of {folders:?}.")),
    };
    if let Some(e) = model["error"].as_str() {
        return Err(format!("The iFlow {} could not be read: {e}", model["iflow"]));
    }
    Ok(iflowmodel::diagram(model, args.get("settings").and_then(Value::as_bool) != Some(false)))
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
    // Without bodyType, the body keeps the type the step before handed it on as (a stream after a script that read
    // one from a tenant's HTTPS sender, through a Content Modifier that left the body as it was).
    let inherited = input_class(server, args).and_then(|c| match c.as_str() {
        c if c.contains("InputStream") => Some("InputStream"),
        "[B" => Some("byte[]"),
        _ => None,
    });
    let body_type = str_arg(args, "bodyType").or(inherited).unwrap_or("String");
    let params = json!({
        "script": script,
        "function": str_arg(args, "function").unwrap_or("processData"),
        "input": message(server, args)?,
        "options": { "bodyType": body_type },
        "args": args.get("args").cloned().unwrap_or(json!([])),
    });
    let mut r = on_runtimes(server, &pick(args, "runtime", GROOVY_RUNTIMES, detected), "groovy.run", params)?;
    // The engine's `functions` lists only Message Mapping functions; by that name an agent took it for all of them.
    let runs = match r.get_mut("runs").and_then(Value::as_array_mut) {
        Some(rs) => rs.iter_mut().map(|x| &mut x["result"]).collect::<Vec<_>>(),
        None => vec![&mut r],
    };
    let as_string = body_type == "String";
    for one in runs {
        if let Some(f) = one.as_object_mut().and_then(|o| o.remove("functions")) {
            one["mappingFunctions"] = f;
        }
        // A script written for a stream body fails on a String one (JsonSlurper.parse(String), for instance).
        let string_call = one["diagnostics"].as_array().is_some_and(|ds| ds.iter().any(|d| d["message"].as_str()
            .is_some_and(|m| m.contains("No signature of method") && (m.contains("(java.lang.String") || m.contains("(String")))));
        if as_string && one["ok"] == false && string_call {
            if let Some(ds) = one["diagnostics"].as_array_mut() {
                ds.push(json!({ "severity": "info", "message": "The body reached the script as a String (bodyType String). \
If the step follows one that hands on a stream, run it with bodyType: InputStream: a tenant's HTTPS sender hands on an \
InputStreamCache (reference fidelity, Stream body)." }));
            }
        }
    }
    if str_arg(args, "runtime").is_none() {
        r["runtime"] = json!(detected);
    }
    if str_arg(args, "bodyType").is_none() && inherited.is_some() {
        r["bodyTypeFrom"] = json!(format!("bodyType {body_type}: the step that messageFrom names handed the body on as \
            a stream or bytes, and the steps since left it as it was. Give bodyType to choose another."));
    }
    Ok(r)
}

fn api_runtime(script: &str) -> &'static str {
    if script.contains("com.sap.it.script.v2.api") { "groovy-2.x" } else { "groovy-1.x" }
}

fn validate_xsd(server: &Server, args: &Args) -> Result<Value, String> {
    let m = message(server, args)?;
    let params = json!({ "schema": source(args, "schema", "schemaPath", "schema.xsd")?, "input": flow::body_only(&m) });
    engine_call(server, str_arg(args, "runtime").unwrap_or(xml_runtime(server)), "xsd.validate", params)
}

fn evaluate_xpath(server: &Server, args: &Args) -> Result<Value, String> {
    let params = json!({
        "expression": str_arg(args, "expression").unwrap_or_default(),
        "version": str_arg(args, "version").unwrap_or("3.1"),
        "namespaces": args.get("namespaces").cloned().unwrap_or(json!({})),
        "input": flow::body_only(&message(server, args)?),
    });
    engine_call(server, str_arg(args, "runtime").unwrap_or(xml_runtime(server)), "xpath.eval", params)
}
