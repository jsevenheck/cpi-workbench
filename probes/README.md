# Tenant probes

These artifacts close the open questions in [docs/plan.md](../docs/plan.md) (Appendix A) by observing a real
Cloud Integration tenant as a black box: no decompiling, just inputs and outputs. You run them once
on a test tenant; the results tell us whether the simulator matches CPI and where to adjust the
runtime profiles.

| File | Step | Answers |
|---|---|---|
| `fingerprint-v1.groovy` | Groovy Script **1.x** | Java / Groovy / Camel / Saxon versions, JAXP factories, charset, timezone, locale, script bindings (Q-G2, Q-G3, Q-G6, Q-X1) |
| `fingerprint-v2.groovy` | Groovy Script **2.x** | Same for 2.x plus the **Script API v2 method list** via reflection (Q-G4). Not yet verified on a tenant. |
| `fingerprint.xsl` | XSLT Mapping 1.2+ | Saxon edition/version, supported features, localisation of `format-date` (Q-X1) |
| `probe-platform-v1.groovy` | Groovy Script **1.x** | Partner Directory and Keystore behaviour for entries that do not exist, plus the real method lists (Q-P1) |
| `prepare-xslt-probe.groovy` + `probe-xslt-behaviour.xsl` | Groovy 1.x, then XSLT Mapping | Header vs. property precedence, Java→XDM types, missing params, `setHeader` return value (Q-X2 … Q-X5) |
| `converters/` | **JSON to XML, XML to JSON, XML to CSV, CSV to XML** converters | Output formatting and XML declaration, JSON escaping, CSV line ends and quoting, an unmapped JSON prefix without namespace mapping (Q-C1) |
| `probe-mapping-v1.groovy` | Custom function in a **Message Mapping** | Groovy and Java version of mapping scripts, the classes and methods behind `MappingContext` and `Output`, and how values reach a `String[]` function: one call per context, SUPPRESS in the input array (Q-M1) |

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

Send the response bodies (or attach them to an issue). Nothing in them is confidential beyond
version numbers and the tenant's default timezone/locale.

## Baseline from the simulator

`baseline-simulator/` holds what the simulator returns for the same probes, so differences are
easy to spot. Known, expected differences:

* `xsl:product-version` is `HE 9.9.1.8` locally and (reportedly) `EE 9.9.1.x` on CPI.
* `xsl:supports-higher-order-functions` / `xsl:supports-dynamic-evaluation` are `no` in Saxon-HE 9.9
  and should be `yes` on CPI (Saxon-EE). Use the *Modern HE (Saxon-HE 12)* profile for stylesheets that need them.
* `FormatDateGerman`: Saxon-HE only has English names (`[Language: en]5. March 2026`).
  Saxon-EE on CPI is expected to print `5. März 2026` — this is a real fidelity gap for
  `format-date`/`format-dateTime` with a language other than English.
* `ImplicitTimezone` depends on the machine running the simulator; CPI tenants usually run in UTC.

The converter baselines come from the Flow Steps tab's Converter (or `convert.run`) with the settings
in step 7. Regenerate the XSLT baselines with:

```sh
E=engine/native/out/cpiwb-xml-he99   # or: java -jar engine/xml-he99/target/cpiwb-xml-he99.jar
$E xslt --xsl probes/fingerprint.xsl --in any.xml
$E xslt --xsl probes/probe-xslt-behaviour.xsl --in any.xml \
  -H ProbeShared=fromHeader -P ProbeShared=fromProperty -H ProbeHeaderOnly=h -P ProbePropertyOnly=p \
  -H ProbeInteger=41:Integer -H ProbeBoolean=true:Boolean -H ProbeDate=1970-01-01T00:00:00Z:Date -H 'ProbeBytes=QUI=:byte[]'
```
