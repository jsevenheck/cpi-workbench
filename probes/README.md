# Tenant probes

These artifacts close the open questions in [PLAN.md](../PLAN.md) (Appendix A) by observing a real
Cloud Integration tenant as a black box: no decompiling, just inputs and outputs. You run them once
on a test tenant; the results tell us whether the simulator matches CPI and where to adjust the
runtime profiles.

| File | Step | Answers |
|---|---|---|
| `fingerprint-v1.groovy` | Groovy Script **1.x** | Java / Groovy / Camel / Saxon versions, JAXP factories, charset, timezone, locale, script bindings (Q-G2, Q-G3, Q-G6, Q-X1) |
| `fingerprint-v2.groovy` | Groovy Script **2.x** | Same for 2.x plus the **Script API v2 method list** via reflection (Q-G4). Not yet verified on a tenant. |
| `fingerprint.xsl` | XSLT Mapping 1.2+ | Saxon edition/version, supported features, localisation of `format-date` (Q-X1) |
| `prepare-xslt-probe.groovy` + `probe-xslt-behaviour.xsl` | Groovy 1.x, then XSLT Mapping | Header vs. property precedence, Java→XDM types, missing params, `setHeader` return value (Q-X2 … Q-X5) |

## How to run

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

Send the four response bodies (or attach them to an issue). Nothing in them is confidential beyond
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

Regenerate the baseline with:

```sh
E=engine/native/out/cpiwb-xml-he99   # or: java -jar engine/xml-he99/target/cpiwb-xml-he99.jar
$E xslt --xsl probes/fingerprint.xsl --in any.xml
$E xslt --xsl probes/probe-xslt-behaviour.xsl --in any.xml \
  -H ProbeShared=fromHeader -P ProbeShared=fromProperty -H ProbeHeaderOnly=h -P ProbePropertyOnly=p \
  -H ProbeInteger=41:Integer -H ProbeBoolean=true:Boolean -H ProbeDate=1970-01-01T00:00:00Z:Date -H 'ProbeBytes=QUI=:byte[]'
```
