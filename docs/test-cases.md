# Regression test cases

`case.json` test cases are this repository's regression tests: CI runs the cases in
`testdata/regression-project/tests` on all four engines ([Building](building.md)). They are not a feature
for users, who test a step by running it in the app. Project layout and the `platform/` formats:
[Project files](project-files.md).

A case lies in its own folder, `tests/<name>/case.json`, with its input and expected files.

## `case.json`

```json
{
  "name": "Standard order",
  "step": "xslt",
  "profile": "saxon-he-9.9",
  "xslt": "../../mappings/order-to-salesorder.xsl",
  "input": {
    "body": { "file": "input.xml" },
    "headers": [ { "name": "SenderSystem", "value": "WEBSHOP", "type": "String" } ],
    "properties": { "CustomerGroup": "02" }
  },
  "options": { "paramPrecedence": "header", "missingParameter": "default" },
  "expect": {
    "body": { "file": "expected-output.xml" },
    "compare": "xml",
    "headers": { "SAP_ApplicationID": "WEB-1001" },
    "properties": { "ItemCount": "2" }
  }
}
```

* `step` is one of:
  * `xslt` (`xslt` names the mapping),
  * `groovy` (`script` names the script; optional `function`; `options.bodyType` is `String`, `InputStream` or `byte[]`;
    for a Message Mapping function see below),
  * `xsd` (`schema` names the schema; expect `valid` and `errorContains`).
* Profiles: `saxon-he-9.9`, `saxon-he-12`, `groovy-1.x`, `groovy-2.x`. Each engine runs the cases of its
  profile and skips the others; any other profile name is an error, on every engine.
* `test <path>` exits with 1 when a case failed or when `<path>` holds no `case.json`. With
  `--fail-if-none` it also exits with 1 when this engine ran none of the cases (all were for other engines).
* `test <path> --junit <file>` also writes the results as a JUnit XML report, for a CI system that shows test
  results. Each engine writes its own report, so give each a file of its own:
  `java -jar cpiwb-groovy-v1.jar test tests --junit reports/groovy-1.x.xml`.
  * One `testsuite` named after the engine's profile, one `testcase` per case with the profile as `classname`.
    A failed case is a `failure`, a case that could not run an `error`, a case of another engine `skipped`,
    each with the case's messages.
  * The output and the exit code stay as they are. A report that cannot be written is an error (exit code 1);
    missing folders are created.
  * JUnit XML has no official schema. The layout is Maven Surefire's (`testsuite` as the root), and the
    reports validate against Surefire's published schema. They have not been tried with a CI reporter
    (GitHub Actions, Jenkins, GitLab).
* Files (bodies, mappings, expected output) are read as UTF-8, or in the encoding their XML declaration names.
* Header and property types: as in [Project files](project-files.md#header-and-property-types).
* `body` is a string, `{ "file": "…" }` or `{ "text": "…" }`. Paths are relative to the case folder.
* `compare` is `xml` (ignores formatting and namespace declarations), `json` (ignores formatting and the order of
  an object's members; `1` equals `1.0`; arrays keep their order), `text` or `trimmed`.
* `expect.ignore` leaves out what changes with every run (a timestamp, a generated id): a list of XPath
  expressions whose nodes (elements, attributes, text) are removed from the expected and from the actual body
  before they are compared as `xml`:
  `"expect": { "body": { "file": "expected.xml" }, "ignore": ["//CreatedAt", "//Item/@uuid"] }`.
  * XPath 1.0, on every engine. A name without a prefix matches only an element that is in no namespace; for the
    others, bind prefixes with `expect.namespaces` (`"namespaces": { "o": "urn:example:order" }`, then `//o:CreatedAt`)
    or write `//*[local-name()='CreatedAt']`. The prefixes are the case's own, not those of the documents.
  * An expression that matches nothing is not an error by itself (the node may be optional); when the bodies
    differ, the message names the expressions that matched nothing.
  * The case is an error when `ignore` is no list of XPath expressions, uses an unknown prefix, selects the
    whole document, or stands without `expect.body` or with another `compare` than `xml` or `json`.
  * With `"compare": "json"` the rules are JSON Pointers (RFC 6901) instead of XPaths, and a segment `*` stands
    for every member or element: `"ignore": ["/order/createdAt", "/order/items/*/uuid"]`. A rule that does not
    start with `/` is an error; one that matches nothing is named when the bodies differ, as above. Every rule
    addresses the bodies as written: `/items/0` and `/items/1` leave out the first two elements.
  * This is CPI Workbench's own rule for its test cases; it imitates nothing in CPI.
* `expect.ok: false` asserts that the mapping or script fails.

### Message Mapping functions

A custom function of a Message Mapping script, such as `def String f(String a, MappingContext c)`
or `def void f(String[] a, Output output, MappingContext c)`, runs with `args` instead of a body:

```json
{
  "step": "groovy",
  "profile": "groovy-1.x",
  "script": "../../scripts/mapping-functions.groovy",
  "function": "countPerContext",
  "args": [[["Multi Print", "Multi Color"], ["Power Scan", { "suppress": true }], []]],
  "input": { "headers": { "OrderId": "4711" } },
  "expect": { "queue": [["2"], ["1"], ["0"]] }
}
```

* `args` holds one entry per `String` or `String[]` parameter, in order; `Output` and
  `MappingContext` come from the simulator. A `String` argument may be `null` (the Groovy tab's
  **null** button). A `String[]` argument is a list of contexts; an entry
  is a string, `null`, `{ "suppress": true }` or `{ "xsiNil": true }`. A flat list is one context.
* The signature decides how the function is called: with `String` arguments once (*Single values*),
  with `String[]` arguments once per context (*All values of a context*), which needs the same number
  of contexts in every `String[]` argument.
* `MappingContext` reads and sets the input's headers and properties; `expect.headers` and
  `expect.properties` check them as usual.
* `expect.returned` is the returned value (a string or `null`); `expect.queue` the output queue in
  the same form as the arguments.

### Attachments and SOAP headers

* **Attachments**: `"attachments": [ { "file": "note.txt", "contentType": "text/plain" } ]` in
  `input`. An entry can hold `text` or `base64` instead of `file`, and an optional `name`
  (default: the file name). Files must lie inside the project.
* **SOAP headers** (documented in Script API v1; also offered by the probed v2 runtime):
  `"soapHeaders": [ { "name": "{urn:ns}Local", "value": "<Local xmlns=\"urn:ns\">…</Local>", "mustUnderstand": false } ]`.
  The result shows the headers the script sets.

## Platform data in a case

A case can override any `platform/` file with a `platform` object (`credentials`, `valueMappings`,
`partnerDirectory`, `keystore`, `numberRanges`), in the files' formats. Keys it leaves out still come from
the folder.
