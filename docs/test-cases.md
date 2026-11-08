# Test cases and platform data

## Project layout

```
my-project/
  cpi-workbench.json      # marks the folder as a project (name)
  mappings/  scripts/  schemas/
  lib/                    # extra .jar files on the script class path
  platform/               # mocked platform data (see below)
  iflows/                 # imported iFlow exports
  tests/<name>/case.json  # one folder per test case, with its input and expected files
```

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
* Profiles: `saxon-he-9.9`, `saxon-he-12`, `groovy-1.x`, `groovy-2.x`.
* Header and property types: `String`, `Integer`, `Long`, `Boolean`, `Double`, `Float`,
  `BigDecimal`, `Date` (ISO-8601), `byte[]` (Base64), `null`.
* `body` is a string, `{ "file": "…" }` or `{ "text": "…" }`. Paths are relative to the case folder.
* `compare` is `xml` (ignores formatting and namespace declarations), `text` or `trimmed`.
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
  is a string, `{ "suppress": true }` or `{ "xsiNil": true }`. A flat list is one context.
* The signature decides how the function is called: with `String` arguments once (*Single values*),
  with `String[]` arguments once per context (*All values of a context*), which needs the same number
  of contexts in every `String[]` argument.
* `MappingContext` reads and sets the input's headers and properties; `expect.headers` and
  `expect.properties` check them as usual.
* `expect.returned` is the returned value (a string or `null`); `expect.queue` the output queue in
  the same form as the arguments.

### Further script inputs

* **Attachments**: `"attachments": [ { "file": "note.txt", "contentType": "text/plain" } ]` in
  `input`. An entry can hold `text` or `base64` instead of `file`, and an optional `name`
  (default: the file name). Files must lie inside the project.
* **SOAP headers** (Script API v1 only, as in SAP's interface):
  `"soapHeaders": [ { "name": "{urn:ns}Local", "value": "<Local xmlns=\"urn:ns\">…</Local>", "mustUnderstand": false } ]`.
  The result shows the headers the script sets.
* **Libraries**: jars in the project's `lib/` folder and in an imported iFlow's
  `src/main/resources/lib/` are on the script's class path. CPI's own extra libraries are not
  known, so none are bundled ([plan](plan.md) F31).
* **Java calls from XSLT**: `xmlns:x="java:java.lang.Math"` style calls (`x:max(1, 2)`,
  `x:new(…)`, instance methods with the object as first argument) work for JDK classes (the native
  engines: a fixed list of common ones). Classes from `lib/` jars are not available to XSLT.
  Hyphenated names (`random-UUID`) work too.

## Mocked platform data (`platform/`)

| File | Used by | Format |
|---|---|---|
| `credentials.json` | `SecureStoreService` | `{ "ALIAS": { "username": "…", "password": "…", "properties": {…} } }`. For OAuth2 credentials (`getAccesTokenForOauth2ClientCredential`, `…AuthorizationCodeCredential`) add `"accessToken": "…"`, optionally `"user"` and `"tokenResponse": {…}`. No token server is called. |
| `value-mappings.json` | `ValueMappingApi.getMappedValue` | `[ { "sourceAgency", "sourceIdentifier", "sourceValue", "targetAgency", "targetIdentifier", "targetValue" } ]` |
| `partner-directory.json` | `PartnerDirectoryService` | `{ "partners": { "PID": { "parameters": { "NAME": "string" or { "contentType": "…", "file": "x.bin" } or { "base64": "…" } }, "alternativePartners": [ { "agency", "scheme", "id" } ], "authorizedUsers": [ "user" ] } } }`. A binary parameter with content type `crt` is returned by `getCertificateParameter` as an X.509 certificate. |
| `keystore.json` | `KeystoreService` | `{ "file": "keystore.p12", "password": "…", "type": "PKCS12", "keyPasswords": { "alias": "…" } }`. The keystore file lies next to it; `type` is optional. Key and trust managers use the same file. |
| `number-ranges.json` | `NumberRangeConfigurationService` | `{ "NAME": { "next": 1, "min": 1, "max": 999999, "fieldLength": 6, "rotate": false } }`. Values count up within one run only and are not written back. The same correlation ID returns the same value. Zero padding to `fieldLength` is an assumption. |

A test case can override any of these with a `platform` object (`credentials`, `valueMappings`,
`partnerDirectory`, `keystore`, `numberRanges`). Keys it leaves out still come from the folder.

**Use test-only keys and passwords here, never real tenant secrets.**

Behaviour for missing entries follows SAP's published Javadoc:

* The Partner Directory and the Keystore return `null` for a missing entry or an unknown alias.
* The Secure Store throws `SecureStoreException` for an unknown alias.
* `ITApiFactory` throws `InvalidContextException` for a service the simulator does not offer.

Whether the deployed runtime matches its Javadoc is not verified yet ([plan](plan.md) F27, F33).
