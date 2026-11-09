# Tenant probes

These artifacts close the open questions in [docs/plan.md](../docs/plan.md) (Appendix A) by observing a real
Cloud Integration tenant as a black box: no decompiling, just inputs and outputs. The results tell us
whether the simulator matches CPI and where to adjust the runtime profiles. The first run on a tenant
(2026-11-09) is in `results-tenant/2026-11-09/`; the Groovy engines follow it since 0.3.0.

| File | Step | Answers | On a tenant |
|---|---|---|---|
| `fingerprint-v1.groovy` | Groovy Script **1.x** | Java / Groovy / Camel / Saxon versions, JAXP factories, charset, timezone, locale, the message's methods, the libraries scripts can load and their versions, the script's variables and their types (Q-G2, Q-G3, Q-G6, Q-X1) | Ran 2026-11-09; run again for `libraryVersions` and `bindingTypes` (new) |
| `fingerprint-v2.groovy` | Groovy Script **2.x** | Same for 2.x, with the message's interfaces (Q-G4) | Ran 2026-11-09; run again as above |
| `fingerprint.xsl` | XSLT Mapping 1.2+ | Saxon edition/version, supported features, localisation of `format-date` (Q-X1) | Ran 2026-11-09 |
| `probe-platform-v1.groovy` | Groovy Script **1.x** | Partner Directory, Keystore, Secure Store and number range behaviour for entries that do not exist, plus the real method lists (Q-P1) | Ran 2026-11-09 |
| `prepare-xslt-probe.groovy` + `probe-xslt-behaviour.xsl` | Groovy 1.x, then XSLT Mapping | Header vs. property precedence, Java→XDM types, missing params, `setHeader` return value (Q-X2 … Q-X5) | Ran 2026-11-09 |
| `converters/` | **JSON to XML, XML to JSON, XML to CSV, CSV to XML** converters | Output formatting and XML declaration, JSON escaping, CSV line ends and quoting, an unmapped JSON prefix without namespace mapping (Q-C1) | Not yet |
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

6. iFlow **Probe_Mapping**: `HTTPS sender` → `Message Mapping` → end. Source and target can be any
   XML schemas with a repeating field in a repeating group and a repeating string field, e.g. source
   `<Root><Group><Value/>…</Group>…</Root>`, target `<Result><Info/>…</Result>` (`Info` 0..unbounded).
   Add `probe-mapping-v1.groovy` as the mapping's script (Groovy Script 1.x), map `Value` (its
   default context, `Group`) through `describeQueue` to `Info`, and post a body such as
   `<Root><Group><Value>a</Value><Value>b</Value></Group><Group><Value>c</Value></Group></Root>`.
   Each `Info` holds the JSON of one call. If the mapping does not deploy or the function is not
   offered, the error message is valuable too. The baseline used the argument
   `[["a","b"],["c",{"suppress":true}]]` (two contexts, so two calls).

7. iFlow **Probe_Converters**: four small iFlows (or one with four HTTPS addresses), each
   `HTTPS sender` → converter → end, with the files in `converters/` as request bodies. Save each
   response body as a file (e.g. `curl --data-binary @file … --output result`), so line ends and
   whitespace stay as they are. Settings as for the baseline in `baseline-simulator/converters/`:
   * XML to JSON, body `xml-to-json.xml`: *Use Namespace Mapping* with `http://com.sap/abc` → `abc`
     and `http://com.sap/xyz` → `xyz`, JSON prefix separator `:`, no streaming.
   * JSON to XML, body `json-to-xml.json`: no namespace mapping, separator `:`, no root element.
   * XML to CSV, body `xml-to-csv.xml`: path `/root/Products/Product`, separator `;`, CSV header
     *XML based*, *Include Parent Element* with `/root`, *Include Attribute Values*.
   * CSV to XML, body `csv-to-xml.csv`: schema `products.xsd` (upload it to the iFlow), path
     `Products/Product`, separator `;`, *Exclude First Line Header*, headers to match *XSD Elements*.

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
  `org.apache.camel.Attachment`, SAP's v2 `Exchange` and `SimpleBuilder`, the type of `log` /
  `logger` and of the message class, `getExtensionFunctions` (1.x) and `setProxyHandler` (2.x), and the
  body class behind an HTTPS sender (`InputStreamCache`). `libraryVersions` and `bindingTypes` are new
  in 0.3.0 and have not run on a tenant yet.
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
