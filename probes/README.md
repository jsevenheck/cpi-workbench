# Tenant probes

These artifacts close the open questions in [docs/plan.md](../docs/plan.md) (Appendix A) by observing a real
Cloud Integration tenant as a black box: no decompiling, just inputs and outputs. The results tell us
whether the simulator matches CPI and where to adjust the runtime profiles. The first run on a tenant
(2026-11-09) is in `results-tenant/2026-11-09/`, with the fingerprints' second run merged in; the
Groovy engines follow it since 0.3.0.

| File | Step | Answers | On a tenant |
|---|---|---|---|
| `fingerprint-v1.groovy` | Groovy Script **1.x** | Java / Groovy / Camel / Saxon versions, JAXP factories, charset, timezone, locale, the message's methods, the libraries scripts can load and their versions, the script's variables and their types (Q-G2, Q-G3, Q-G6, Q-X1) | Ran three times (with `libraryVersions`, `bindingTypes` and `logMethods` the last time) |
| `fingerprint-v2.groovy` | Groovy Script **2.x** | Same for 2.x, with the message's interfaces (Q-G4) | Ran twice |
| `fingerprint.xsl` | XSLT Mapping 1.2+ | Saxon edition/version, supported features, localisation of `format-date` (Q-X1) | Ran 2026-11-09 |
| `probe-platform-v1.groovy` | Groovy Script **1.x** | Partner Directory, Keystore, Secure Store and number range behaviour for entries that do not exist, plus the real method lists (Q-P1) | Ran 2026-11-09 |
| `prepare-xslt-probe.groovy` + `probe-xslt-behaviour.xsl` | Groovy 1.x, then XSLT Mapping | Header vs. property precedence, Java→XDM types, missing params, `setHeader` return value (Q-X2 … Q-X5) | Ran 2026-11-09 |
| `converters/` | **JSON to XML, XML to JSON, XML to CSV, CSV to XML** converters | Output formatting and XML declaration, JSON escaping, CSV line ends and quoting, an unmapped JSON prefix without namespace mapping (Q-C1) | All four ran; the simulator gives the same bytes |
| `probe-mapping-v1.groovy` | Custom function in a **Message Mapping** | Groovy and Java version of mapping scripts, the classes and methods behind `MappingContext` and `Output`, and how values reach a `String[]` function: one call per context, SUPPRESS in the input array (Q-M1) | Not yet |

## How to run

The tenant's script editor checks scripts against SAP's *Upgrade Readiness* design guidelines. The
probes avoid what it flags as an error (`System.getProperty`); warnings about internal classes such as
Camel's are expected, because finding their versions is what the probes are for. If the editor reports
another error, note its text and line.

1. Create a test package and an iFlow **Probe_Fingerprint**:
   `HTTPS sender (address /cpiwb/fingerprint)` → `Groovy Script 1.x: fingerprint-v1.groovy` → end.
   Call it with any HTTP client; the response body is JSON.
2. Copy the iFlow, change the script step to **2.x** and use `fingerprint-v2.groovy`.
   If the script does not compile, the error message is valuable too.
3. iFlow **Probe_XSLT**:
   `HTTPS sender` → `Groovy Script 1.x: prepare-xslt-probe.groovy` → `XSLT Mapping (1.2+): probe-xslt-behaviour.xsl` → end.
   The response body is an XML document. If the mapping step fails, keep the full error text from
   the message processing log.
4. iFlow **Probe_XSLT_Fingerprint**: `HTTPS sender` → `XSLT Mapping: fingerprint.xsl` → end.
5. iFlow **Probe_Platform**: `HTTPS sender` → `Groovy Script 1.x: probe-platform-v1.groovy` → end.
   It only looks up names that cannot exist; no Partner Directory content or keys are needed.

6. iFlow **Probe_Mapping**: `HTTPS sender (address /cpiwb/mapping)` → `Message Mapping` → end. The files
   are in `mapping/`:
   * Create the message mapping with `mapping/source.xsd` as source (`Root/Group/Value`, both
     repeating) and `mapping/target.xsd` as target (`Result/Info`, repeating).
   * Add `probe-mapping-v1.groovy` to the mapping as a script (Groovy Script 1.x). Its function
     `describeQueue` then appears among the mapping's custom functions.
   * Map `Root` → `Result`, and `Value` → `describeQueue` → `Info` (keep `Value`'s default context,
     `Group`).
   * Post `mapping/request.xml` (two groups: `a`, `b` and `c`). Each `Info` holds the JSON of one call.
     Optionally post `mapping/request-empty-context.xml` too (an empty group in between).

   If the mapping does not deploy or the function is not offered, the error message is valuable too.
   The simulator's baseline used the argument `[["a","b"],["c",{"suppress":true}]]` (two contexts, so
   two calls).

7. iFlow **Probe_Converters**: four small iFlows (or one with four HTTPS addresses), each
   `HTTPS sender` → converter → end. The request bodies are in `converters/`; the settings are those of
   the baseline in `baseline-simulator/converters/`:

   | Address | Converter and settings | Body |
   |---|---|---|
   | `/cpiwb/xml-to-json` | XML to JSON: *Use Namespace Mapping* with the XML namespace `http://com.sap/abc` → JSON prefix `abc` and `http://com.sap/xyz` → `xyz`, JSON prefix separator `:`, no streaming | `converters/xml-to-json.xml` |
   | `/cpiwb/json-to-xml` | JSON to XML: no namespace mapping, separator `:`, no root element | `converters/json-to-xml.json` |
   | `/cpiwb/xml-to-csv` | XML to CSV: path `/root/Products/Product`, separator `,` (Comma), CSV header *XML based*, *Include Parent Element* with `/root`, no attribute values (baseline `xml-to-csv.csv`); a second run with separator `;` and *Include Attribute Values* (baseline `xml-to-csv-semicolon.csv`) | `converters/xml-to-csv.xml` |
   | `/cpiwb/csv-to-xml` | CSV to XML: schema `converters/products.xsd` (upload it to the iFlow), path `Products/Product`, separator `;`, *Exclude First Line Header*, headers to match *XSD Elements*, no record marker | `converters/csv-to-xml.csv` |

   Save each response body as a file, so line ends and whitespace stay as they are (below).

**Sending the requests.** With the runtime URL of the tenant (`https://<runtime host>/http`) and the
client ID and secret of a service key for the Process Integration Runtime, `curl` sends a file
unchanged and saves the answer unchanged. In Windows PowerShell write `curl.exe`. The credentials are
typed into the command only; keep them out of files in this repository.

```sh
U=https://<runtime host>/http
curl -s -u "<clientid>:<clientsecret>" --data-binary @probes/mapping/request.xml -H "Content-Type: application/xml" "$U/cpiwb/mapping" --output mapping.xml
curl -s -u "<clientid>:<clientsecret>" --data-binary @probes/converters/xml-to-json.xml -H "Content-Type: application/xml" "$U/cpiwb/xml-to-json" --output xml-to-json.json
curl -s -u "<clientid>:<clientsecret>" --data-binary @probes/converters/json-to-xml.json -H "Content-Type: application/json" "$U/cpiwb/json-to-xml" --output json-to-xml.xml
curl -s -u "<clientid>:<clientsecret>" --data-binary @probes/converters/xml-to-csv.xml -H "Content-Type: application/xml" "$U/cpiwb/xml-to-csv" --output xml-to-csv.csv
curl -s -u "<clientid>:<clientsecret>" --data-binary @probes/converters/csv-to-xml.csv -H "Content-Type: text/plain" "$U/cpiwb/csv-to-xml" --output csv-to-xml.xml
```

Send the response bodies (or attach them to an issue); they go into `results-tenant/<date>/`. Nothing
in them is confidential beyond version numbers, class names and the tenant's default timezone/locale.

## Baseline from the simulator

`baseline-simulator/` holds what the simulator returns for the same probes, so differences are
easy to spot. `results-tenant/2026-11-09/` holds what one tenant returned (findings F48–F52 in the
plan). The differences that remain:

* `xsl:product-version` is `HE 9.9.1.8` locally and `EE 9.9.1.6` on the tenant.
* `xsl:supports-higher-order-functions` / `xsl:supports-dynamic-evaluation` / streaming are `no` in
  Saxon-HE 9.9 and `yes` on the tenant. Use the *Modern HE (Saxon-HE 12)* profile for stylesheets that need them.
* `FormatDateGerman`: Saxon-HE only has English names (`[Language: en]5. March 2026`); the tenant
  prints `5. März 2026`. A real fidelity gap for `format-date`/`format-dateTime` in other languages.
* Groovy: Java, Camel, timezone, locale, charset, bindings, message methods and libraries follow the
  tenant since 0.3.0. What still differs: the Java build (`javaVmVersion`, `javaVendor`), Camel's
  SAP build (`3.14.7-sap-59`), Saxon-EE and IAIK visible to 1.x scripts, Camel 2's
  `org.apache.camel.Attachment`, SAP's v2 `Exchange` and `SimpleBuilder`, the classes behind `log` /
  `logger` (SAP's `ScriptLogger`; the simulator has its methods but no `LogMessage` overloads), `messageLogFactory` and the message, `getExtensionFunctions` (1.x) and
  `setProxyHandler` (2.x), and the body class behind an HTTPS sender (`InputStreamCache`).
* Platform: the simulator's messages add where it looked (e.g. `platform/credentials.json`).

The converter baselines come from the Flow Steps tab's Converter (or `convert.run`) with the settings
in step 7. In the baselines, IDs that change with each run are replaced by `x` (the random alias suffix
of the platform probe, the host part of the exchange ID), and the script API jar's path by its file
name. Regenerate the Groovy baselines with:

```sh
G=engine/groovy-v1/target/cpiwb-groovy-v1.jar   # groovy-v2 for fingerprint-v2.groovy
java -jar $G run --script probes/fingerprint-v1.groovy --in any.xml
java -jar $G run --script probes/probe-platform-v1.groovy --in any.xml
```

and the XSLT baselines with:

```sh
E=engine/native/out/cpiwb-xml-he99   # or: java -jar engine/xml-he99/target/cpiwb-xml-he99.jar
$E xslt --xsl probes/fingerprint.xsl --in any.xml
$E xslt --xsl probes/probe-xslt-behaviour.xsl --in any.xml \
  -H ProbeShared=fromHeader -P ProbeShared=fromProperty -H ProbeHeaderOnly=h -P ProbePropertyOnly=p \
  -H ProbeInteger=41:Integer -H ProbeBoolean=true:Boolean -H ProbeDate=1970-01-01T00:00:00Z:Date -H 'ProbeBytes=QUI=:byte[]'
```
