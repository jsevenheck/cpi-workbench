# CPI Workbench — Design, research and roadmap

The design, the research ledger (findings F…, sources S…, open questions Q…) and the roadmap. What is
available in the current checkout: [status](status.md). What matches CPI: [fidelity](fidelity.md).
This ledger preserves dated evidence; later findings may supersede earlier observations without
invalidating the original report. Usage and implementation details live in the linked guides;
the roadmap retains design decisions, exclusions and genuinely open work.

---

## 1. The core idea

Testing a Groovy script or an XSLT mapping in SAP Integration Suite today means: change → deploy → wait → trigger → enable trace → open the monitor → read the payload → repeat. Each round takes minutes.

CPI Workbench removes that loop. It is a local desktop app (distributed for Windows and macOS,
with CLI/browser mode also available on Linux) where you:

1. enter an **input message** — body, headers, exchange properties,
2. pick a **Groovy script** (1.x or 2.x), an **XSLT mapping** or a **flow step**,
3. press **Run** (or let it re-run on every change),
4. see the **output message** — body, headers, properties, MPL entries, logs and errors — immediately.

The test must behave **as close as possible to the real CPI step**: same Groovy version, same Saxon release line, same parameter binding, same API surface, same type conversions. That fidelity is the reason to build this instead of using a generic Groovy console or XSLT tester.

All simulation runs **locally**. The app never connects to a tenant; its manual update check contacts
GitHub, and its opt-in CPI Helper receiver accepts loopback traffic.

### In scope
- Groovy Script step, **Script API v1** (step 1.x, Groovy 2.4.21) and **Script API v2** (step 2.x, Groovy 4.0.29)
- XSLT Mapping step (XSLT 1.0 / 2.0 / 3.0)
- XML Validator-like **XSD validation**, an **XPath tester**
- Single **flow steps**: Content Modifier, Filter, Router, Splitter, Gather, Converter, Encoder / Decoder
  (Base64, GZIP, ZIP) and Message Digest (§10)
- Custom functions of **Message Mapping** scripts, run on their own (§10.3)
- Test cases saved as files, a runner and CLI for regression tests; an **MCP server** for coding agents (§10.1)

### Out of scope (for now)
- Whole iFlows, adapters, graphical Message Mapping (`.mmap`)
- Connecting to a tenant; the app stays offline
- Shipping SAP code or decompiling SAP runtime binaries

---

## 2. What CPI actually does (research ledger)

Labels follow the REA evidence rules (see §4): **Observed** = read in a source or measured; **Inferred** = concluded from observations; **Unknown** = not established. F1–F19 come from the first research round; later findings came up during the implementation.

| # | Finding | Label | Source |
|---|---|---|---|
| F1 | Groovy Script step 1.x → **Groovy 2.4.21** | Observed | S1 |
| F2 | Groovy Script step 2.x → **Groovy 4.0.29** | Observed | S1 |
| F3 | A diagnostic on a 2.x step reported **Java 17.0.18, Groovy 4.0.29, Camel 3.14.7** | Observed (one community report) | S2 |
| F4 | Java version behind **1.x** scripts today: Java 21 on one tenant (F48); SAP Help still mentions Java 8 | Observed (one tenant) | S1, S32 |
| F5 | v1 API `com.sap.gateway.ip.core.customdev.util.Message`, entry `def Message processData(Message message)` | Observed | S1, S5 |
| F6 | v2 API in package `com.sap.it.script.v2.api`; published signatures in F29, additional runtime methods in F49. The internal `Exchange` class remains unimplemented. | Observed (interfaces and one tenant) | S2, S3, S23, S32 |
| F7 | The public SAP Script API jar is under an SAP licence and is "bare-bones" | Observed (jar: SAP Developer License, interfaces only) → not shippable (Inferred) | S5, S6, S23 |
| F8 | CPI's XSLT engine is **Saxon-EE**, most recently reported as **9.9.1.6** (older report: 9.8.0.12) | Observed (community) | S8, S9 |
| F9 | XSLT 3.0 supported since XSLT Mapping step 1.2 | Observed | S7 |
| F10 | XSLT parameters: **every `xsl:param` is bound automatically** to a header or exchange property of the same name, if one exists | Observed (SAP sample docs) | S10 |
| F11 | Setting values from XSLT: namespace `http://sap.com/it/` (prefix free, SAP uses `hci:`), functions `setHeader($exchange, name, value)` and `setProperty($exchange, name, value)`, all strings; the stylesheet must declare `<xsl:param name="exchange"/>` | Observed (SAP sample docs) | S10 |
| F12 | Error "Header/Property value not found for the parameter" exists as an SAP KBA — parameter binding has edge cases | Observed (KBA title only) | S10 |
| F13 | Saxon-HE **9.9.1-x** is on Maven Central under **MPL-2.0** | Observed | S11 |
| F14 | Saxon **9.9 HE does not support higher-order functions** (PE/EE only); HE gained them (and `xsl:evaluate`) in Saxon 10 | Observed | S12 |
| F15 | Saxon-HE supports **integrated extension functions** (`ExtensionFunctionDefinition`); reflexive Java calls need PE/EE | Observed (Saxon docs as cited by PTC; Saxon docs to re-check) | S13, S8 |
| F16 | Saxon-HE can be compiled to a **native executable with GraalVM** (reported for 12.4, ~42 MB, small reflection config); SaxonC is Saxonica's own native build | Observed (anecdotal for 12.4) → feasible for 9.9 (Inferred) | S14, S15 |
| F17 | Dynamic Groovy (runtime-compiled scripts) **cannot run in a GraalVM native image**; no Groovy implementation exists outside the JVM | Observed | S16, S17 |
| F18 | CPI traces show headers, exchange properties and payload per step; MPL with trace content can be exported | Observed (blogs; export format Unknown) | S18 |
| F19 | Existing references: GrooveBox (online, Groovy 2.4/Java 8 and 4/Java 17, body/headers/properties, value mappings, secure store, partner directory, MPL, console, list of SAP API calls), Contiva Groovy IDE (online), go-xslt (pure-Go XSLT 3.0/XSD engine + desktop tester, pre-1.0, single maintainer) | Observed (descriptions, not inspected in depth) | S19, S20, S21 |
| F20 | Saxon-HE (9.9 and 12) only has English names for `format-date` (`'de'` → `[Language: en]5. March 2026`). A tenant's Saxon-EE localises them (F51). | Observed (simulator and one tenant) | `probes/baseline-simulator/fingerprint.saxon-he-9.9.xml`, S32 |
| F21 | Passing headers/properties as Java objects through JAXP `setParameter` makes Saxon convert them itself: `Integer` → `xs:integer`, `Boolean` → `xs:boolean`, `Date` → `xs:dateTime`, `byte[]` → sequence of integers. | Observed (simulator) | `probes/baseline-simulator/probe-xslt-behaviour.saxon-he-9.9.xml` |
| F22 | Header-over-property precedence is modelled on Camel's XsltBuilder (properties set first, headers override). A tenant gives the header precedence too (F51). | Inferred, confirmed on one tenant | `XsltStep.java`, S32 |
| F23 | Saxon instantiates built-in functions reflectively; native builds need reflection metadata for all of them (generated by `engine/native/GenReflect.java`), plus the JDK XPath 1.0 function classes. | Observed | native build failures fixed in this phase |
| F24 | Groovy 2.4.21 runs the Script API v1 corpus on Java 17 and 21 without changes (7 community scripts). | Observed (simulator) | `GroovyCorpusTest` |
| F25 | `XmlUtil.serialize` / XSLT pretty-printing differs between JDK versions (indentation), so the Java version behind each CPI step matters for byte-exact comparisons. | Observed (corpus expected files vs. JDK 17/21 output) | `GroovyCorpusTest` |
| F26 | A classic 1.x → 2.x break is reproduced: scripts using `XmlParser`/`XmlSlurper` without import (default `groovy.util` package) fail on Groovy 4. | Observed (simulator) | corpus `edi-segment-counter` |
| F27 | Partner Directory, Keystore, Secure Store and number ranges: SAP's Javadoc (`generic.api`, F33) documents null for a missing Partner Directory parameter, partner or certificate parameter; `IllegalArgumentException` for null/empty IDs and for types other than `String`/`BinaryData`; null for an unknown keystore alias or the wrong entry kind (`IllegalStateException` when a key cannot be recovered); `SecureStoreException` for an unknown credential. The simulator follows this. A tenant matches it for names that do not exist; its messages are in F52. | Observed (Javadoc, one tenant) | S24, S32 |
| F28 | CPI has no public script API for the Data Store; scripts that access it use internal classes looked up from the Camel registry. The simulator does not mock this. | Inferred (community examples) | — |
| F29 | SAP publishes its Script API interfaces on Maven Central (`com.sap.cloud.script:script.api` 2.14.0–2.16.4 for v1, `script.v2.api` 4.0.0–4.2.2 for v2; SAP Developer License, not bundled). The simulator's `Message`, `AttachmentWrapper`, `SoapHeader` and v2 attachment, extension-function and exception types follow these signatures. The v2 interface lacks SOAP/AttachmentWrapper methods but adds `getHeader(String)`, `getHeader(String, Object)`, `clearAttachments`, `resetBodyStream` and extension functions. Published interfaces are a lower bound; F49 records additional runtime methods from a tenant. | Observed (published interfaces; runtime follow-up F49) | Maven Central `com.sap.cloud.script`, S32 |
| F30 | The v1 API jar moved from Camel 2.24 (`script.api` 2.15.0, May 2024: imports `org.apache.camel` [2.24,3), `AttachmentWrapper` extends `org.apache.camel.impl.DefaultAttachment`) to Camel 3.14 (2.16.2, Dec 2025, and 2.16.4: [3.14,4), `org.apache.camel.attachment.DefaultAttachment`). So 1.x steps may now run on Camel 3.14, which the fingerprint confirmed (F48); since 0.3.0 the simulator's 1.x engine does too, and its `AttachmentWrapper` extends Camel 3's `DefaultAttachment`. The simulator's v1 Message has the overloads that take Camel's `Attachment` type, as a tenant's does (F49). | Observed (jar manifests, one tenant) | Maven Central `com.sap.cloud.script:script.api` |
| F31 | Extra script libraries were identified by fingerprints (F50, F53). The simulator bundles the reported open-source libraries and versions, excluding IAIK and Saxon-EE; user jars also load from `lib/` and an iFlow's `src/main/resources/lib/`. | Observed (one tenant); exclusions are simulator limits | SAP sample iFlows, S32 |
| F32 | SAP's sample XSLTs call Java static methods through `java:` namespaces (e.g. `java:java.util.Date`), so CPI's Saxon-EE allows reflexive extension functions. The simulator registers them as integrated functions for JDK classes (native engines: a fixed list of JDK classes). Classes from the iFlow's or project's jars are not available to XSLT (the native engines cannot load classes at runtime). Exact argument conversion rules of Saxon-EE are not reproduced in all cases. | Observed (SAP samples) / Inferred (conversion) | SAP sample iFlows, `JavaExtensions.java` |
| F33 | SAP also publishes the platform API interfaces as `com.sap.cloud.adk:generic.api` (4.10.2, with Javadoc; SAP Developer License, not bundled): `ITApiFactory`, `ITApiException`/`InvalidContextException`, Keystore (incl. key and trust managers), message log, number ranges (`NumberRangeConfigurationService.getNextValuefromNumberRange(name, correlationId)`), Partner Directory (incl. `getCertificateParameter(parameterId, partnerId)`, authorized users, deprecated LIKE-pattern lookups) and Secure Store (incl. OAuth2 tokens: `getAccesTokenForOauth2ClientCredential`, SAP's spelling). `ValueMappingApi` is not in it. The simulator's classes follow these signatures. | Observed (published jar + Javadoc) | S24 |
| F34 | The Camel line is a property of the tenant: SAP numbers Cloud Integration software 8.x (Cloud Foundry) and 7.x (Neo) for runtimes on Camel 3, 6.x and 5.x for Camel 2. Patch notes from 2024 to 2026 describe the migration of tenants to Camel 3 and back-ports to the Camel 3.14.7 branch. Whether any tenant still runs Camel 2 is not stated. | Observed (SAP docs) | S25 |
| F35 | SAP's Router table uses `=`, `and`, `or` and `${property.x}`, and its docs never use `${exchangeProperty.x}`. Plain Camel has no `=` operator ("Unexpected token =" on 2.24 and 3.14), and Camel 3.14 rejects `and`, `or` (only `&&`, `\|\|`) and `property`. SAP's patch notes (August 2024) confirm that CPI converts `property` to `exchangeProperty`; how it handles `=`, `and` and `or` is not documented. | Observed (SAP docs, simulator) / Inferred (`=`, `and`, `or`) | S25, `SimpleStepTest` |
| F36 | In exported iFlows a Content Modifier is `activityType=Enricher` with `headerTable` / `propertyTable` (rows of `<cell id='Action\|Type\|Value\|Default\|Name\|Datatype'>`), `bodyType` and `wrapContent`. Source types: constant, header, property, xpath, expression, persisted variables, global persisted variables, numberRange, external parameter. Delete rows name `$name=X` or `$include=…,$exclude=…`. A Router is an `exclusiveGateway` (`throwException`, `default` flow) whose outgoing flows carry `expressionType` XML or NonXML and a `conditionExpression`. A splitter has `splitType` GeneralSplitter or IteratingSplitter, `exprType` XPath, Line Break or Token, `splitExprValue` or `tokenValue`, `grouping`, `Streaming`. Values may hold `{{parameters}}` from `parameters.prop`. | Observed (1,570 Content Modifiers, 629 Routers, 77 splitters in 389 exported iFlows) | S26 |
| F37 | Router XML conditions in real iFlows use XPath 2.0+ syntax (`/node()/*:IDOC[1][1=last()]`, `/a/count(b) = 0`), so CPI's Router does not evaluate them as XPath 1.0. | Observed | S26 |
| F38 | Content Modifier versions up to 1.3 have no `bodyType`, and their bodies hold Camel Simple (`${header.x}`, `${exception.message}`; 39 such steps, 19 with `${…}`), as in SAP's sample "Externalizing Additional Parameter" (1.2), which shows header values in the body: these bodies are expressions. From 1.4 the type is stored; 1.6 mostly stores `constant` for an empty body. In the same samples, 1,565 of 1,568 Content Modifiers and 623 of 625 Non-XML Router conditions run on the simulator's Camel; the rest fail on input values, a misspelt class (`java.Lang.Integer`) or three-level nested `${…}` that Camel's parser rejects. | Observed | S26 |
| F39 | Filter steps store `xpathType` (String, Integer, Boolean, Node, Nodelist) and the XPath in `wrapContent`; Gather steps store `messageType` (SameXMLFormat, DiffXMLFormat, PlainText), `aggregationAlgorithm` (sap-identical-multi-mapping, sap-pi-multi-mapping, xpath-merge-strategy, concatenate-strategy), `sourceXPath` and `targetXPath` (64 Filters, 55 Gathers in the samples). Of the 64 Filter XPaths, 60 compile on Saxon without variables; the other 4, and one Router XML condition, use `$name` for a header or property. Camel's XPath variable resolver (`MessageVariableResolver`, Camel 2.24.3 and 3.14.7, read from the Apache jars) resolves an unprefixed `$name` to the header, else the exchange property. | Observed | S26 |
| F40 | SAP's Javadoc of the Cloud Integration API (`com.sap.it.public.api` 2.12.0) documents `com.sap.it.api.mapping`: `MappingContext` extends `com.sap.it.api.msg.ExchangePropertyProvider` with `getHeader(String)` / `setHeader(String, Object)` and `getProperty(String)` / `setProperty(String, Object)` (null for a missing name; set creates or overwrites). `Output` is "used in advanced user-defined functions (execution type 'All values of Context' or 'All values of a Queue') to return the result": `addValue(Object)`, `addContextChange()`, `addSuppress()` (the target field and its sub nodes are not generated), `addXSINIL()`, `clear()`, `isContextChange(Object)` (to find context changes in an input array), `isSuppress(Object)`, `isXSINIL(Object)`. None of these is in SAP's jars on Maven Central (`script.api` 2.16.4, `script.v2.api` 4.2.2, `generic.api` 4.10.2). The simulator follows these signatures. | Observed (Javadoc) | S27 |
| F41 | SAP's patch notes (software increment 2602 and later: 6.77.47, 7.50.10, 6.78.56, 7.51.11) say: "Filtered out Groovy Script Version 2 artifacts from Message Mappings, as this version is currently not supported in Message Mapping scenarios." Mapping-script versions were later measured as Groovy 2.4.21 / Java 21 (F59). | Observed (SAP docs; runtime follow-up F59) | S25, S32 |
| F42 | SAP's PI documentation of advanced user-defined functions: a queue holds values, an empty string per occurrence of a structure field, context changes (`ResultList.CC`), `ResultList.SUPPRESS` and empty contexts. "All Values of Context" passes one context, without an identifiable context change, and the function may insert context changes into its result; "All Values of Queue" passes whole queues, without the context changes at the start and end. That CPI's Groovy functions get queues this way is an inference (same execution type names in CPI's Javadoc, F40). How CPI joins the results of the per-context calls, and the marker values it uses, are not documented. | Observed (PI docs) / Inferred (CPI) → Q-M1 | S28 |
| F43 | SAP's samples declare mapping functions as `def String f(String name, MappingContext context)`; `import com.sap.it.api.mapping.MappingContext`. Community guides declare a function with an `Output` parameter `void` and use no return value (F44); that CPI rejects other return types ("No valid return type") was seen only in search excerpts of SAP Community posts. The `setProperty` sample in *Access Headers and Properties in Scripts* returns `header_value`, which it does not define (the simulator fails at runtime with `MissingPropertyException`). | Observed (SAP docs) / Reported (Community, not verified) | S25 |
| F44 | Two community guides (2022 and later) agree that CPI offers two execution types for Groovy mapping functions, chosen by the signature: *Single values* (`def String f(String a, …)`, values "one by one in the context") and *All values of context* (`def void f(String[] a, Output output, MappingContext context)`, no return value). Both say CPI has no *All values of queue*; `removeContexts` before the function is the usual workaround. `MappingContext` may be left out of a single-value function. SAP's own documentation does not describe the execution types. | Reported (community, two sources) → Q-M1 | S29 |
| F45 | In SAP's sample iFlow "Accessing Header Property and Externalized Parameter from Message Mapping", a Message Mapping step is `activityType=Mapping` with `mappinguri` `dir://mmap/src/main/resources/mapping/<name>.mmap` (no `subActivityType`). The `.mmap` lists each Groovy script it uses as `<lnkRole role="UsedFuncLib">` with a key whose `elem` values are the file name (`getLocation.gsh`) and its folder (`src/main/resources/script`). One sample only; the import reads these generically. | Observed (one SAP sample) | S26 |
| F46 | Converter steps in exported iFlows: `activityType` JsonToXmlConverter (`useNamespaces`, `jsonNamespaceSeparator`, `jsonNamespaceMapping` as rows of two unnamed cells, `addXMLRootElement`, `additionalRootElementName`), XmlToJsonConverter (`useNamespaces`, `jsonNamespaceSeparator`, `xmlJsonSuppressRootElement`, `xmlJsonUseStreaming`, `xmlJsonConvertAllElements` = `specific`, `jsonOutputEncoding`), XmlToCsvConverter (`XPath_Field_Location`, `Field_Separator_in_CSV`, `CSV_Header` none/xml or older `Include_Header`, `Include_Master`, `Master_XPath_Field_Location`, `Include_Attribute`), CsvToXmlConverter (`XML_Schema_File_Path` such as `/xsd/items.xsd`, `XPath_Field_Location`, `Field_Separator_in_CSV`, `ignoreFirstLineAsHeader`, `headerMapping` = `mapHeadersToXSD`). Not seen: the XML to JSON namespace table and array paths (no sample streams), the CSV record marker, the JSON root element's namespace. | Observed (37 + 35 + 7 + 2 steps in 50 public iFlows on GitHub, 2026-11-07) | S31 |
| F47 | SAP documents the JSON ↔ XML conversion rules, limitations, error texts and examples, XML to JSON streaming, and two CSV examples with input and output (S30); the converters follow them. SAP's own pages disagree in one place: the JSON to XML rule keeps an unmapped prefix in the element name (`"p.A"` → `<p.A>`), while the example "No Namespace Mapping" turns `"abc.A"` into `<A>` (with separator `:`). The examples also contain typos (`<E>valueE<E>`). Output formatting is shown compact for JSON ↔ XML, indented for CSV to XML. A tenant follows the rule, not the example, for the prefix, and writes compact output for all four (F54–F56). | Observed (SAP docs, one tenant) | S30, S32 |
| F48 | The probes on one tenant (2026-11-09): Groovy Script **1.x and 2.x both run on Java 21** (SAP SE JVM 21.0.13) and **Camel 3.14.7-sap-59**; Groovy 2.4.21 and 4.0.29. Default charset UTF-8, timezone `Etc/UTC`, locale `en_US`. The 1.x runtime also offers Saxon-EE 9.9.1.6 to scripts; 2.x does not. | Observed (one tenant) | S32 |
| F49 | Script context on that tenant: 1.x binds `log`, `logger`, `messageLogFactory` and `javax.script.filename`; 2.x only `messageLogFactory` and `javax.script.filename`. Neither binds `out`. The types of `log` and `logger` were not recorded. Runtime message classes: 1.x `com.sap.gateway.ip.core.customdev.processor.MessageImpl` with both the `AttachmentWrapper` and Camel `Attachment` overloads, `getAttachmentObjects`/`setAttachmentObjects` and `getExtensionFunctions`, but no `getHeader(String)`; 2.x `com.sap.it.script.v2.executor.impl.MessageImpl` with the v2 interface plus `getSoapHeaders`/`setSoapHeaders` and `setProxyHandler`. The body behind an HTTPS sender is an `InputStreamCache`. | Observed (one tenant) | S32 |
| F50 | Libraries a script can load on that tenant: 1.x has commons-lang3, Jackson databind, commons-codec, IAIK and `groovy.sql`; 2.x has commons-lang3 and Groovy's yaml and toml modules, but no Jackson, commons-codec, IAIK or `groovy.sql`. Neither has POI or `groovy.ant`. Versions were not recorded. | Observed (one tenant) | S32 |
| F51 | XSLT Mapping on that tenant: **Saxon-EE 9.9.1.6**, streaming, dynamic evaluation and higher-order functions supported, implicit timezone `PT0S`, `format-date` localises German month names (`5. März 2026`), `saxon:evaluate` available. Parameters: a header wins over a property of the same name; typed values arrive typed (Integer → `xs:integer`, Boolean, Date → `xs:dateTime`, `byte[]` → two items); a missing parameter takes the stylesheet's default; `hci:setHeader` returns the empty sequence; `$exchange` prints as `Exchange[<id>]`. All of this matches the simulator except the edition (HE); the `Exchange` text follows the tenant since 0.3.0. | Observed (one tenant) | S32 |
| F52 | Platform services on that tenant, for names that do not exist: Partner Directory lookups return null (also `getAuthorizedUsers` of an unknown partner); a type other than String/BinaryData gives "The type java.lang.Integer is invalid. Allowed types are java.lang.String or com.sap.it.api.pd.BinaryData"; an empty ID "parameterId is empty"; a `[` in a pattern "The pattern '[x]' contains the invalid character '['". Keystore lookups of an unknown alias return null. Secure Store: "Could not fetch the credential for alias X" (also for an empty alias); an unknown OAuth2 client credential "Problem during retrieving the access token for the OAuth2 client credential X: No artifact descriptor found for artifactName X". Number ranges: "Error has occurred while getting next value from number range object. Cannot find nro_metadata for sequence: X". `ITApiFactory.getService(ITApi)` throws `InvalidContextException`. | Observed (one tenant) | S32 |
| F53 | A second run of the fingerprints on that tenant (results merged into the files of the first run): scripts load commons-lang3 3.20.0 (1.x and 2.x), Jackson databind 2.21.7 and commons-codec 1.16.1 (1.x) and camel-attachments 3.14.7-sap-59 (both); groovy-yaml and groovy-toml carry no version. 1.x binds `log` and `logger` as `com.sap.it.script.logging.impl.ScriptLogger`; `messageLogFactory` is `com.sap.it.op.agent.mpl.factory.impl.MessageLogFactoryImpl` on both. All other values match the first run. | Observed (one tenant) | S32 |
| F54 | XML to JSON on that tenant with `probes/converters/xml-to-json.xml` and the settings of step 7 (namespace mapping `http://com.sap/abc` → `abc`, `http://com.sap/xyz` → `xyz`, separator `:`, no streaming): `{"abc:A":{"xyz:B":{"@xyz:attr1":"1","@attr2":"2","$":"valueB"},"C":["valueC1","valueC2"],"D":"","E":"valueE","F":" ","G":"a/b \"quoted\" ü"}}`, the same as the simulator's baseline: compact, `/` and non-ASCII unescaped, `"` escaped, `""` for an empty element, a whitespace value kept. Copied from a screenshot of the response, so a trailing line break and the bytes of `ü` are not verified. | Observed (one tenant) | S32 |
| F55 | XML to CSV on that tenant with `probes/converters/xml-to-csv.xml` (path `/root/Products/Product`, separator comma, header *XML based*, parent `/root`, no attribute values): `Type,Source` / `Source Data,ERP` / an empty line / `ProductId,Name,Note` / `HT-1080,Photo Scan,a;b` / `HT-1081,Power Scan,"quoted"`, and a line break after the last line. SAP's documented example has no empty line after the parent's lines. No value contains the comma, so none is quoted (F57). Seen in a viewer: `\n` or `\r\n` is not known. | Observed (one tenant) | S32 |
| F56 | JSON to XML and CSV to XML on that tenant with `probes/converters/` and the settings of step 7. JSON to XML (no namespace mapping, separator `:`): `<?xml version='1.0' encoding='UTF-8'?><abc.A><xyz.B xyz.attr1="1" attr2="2">valueB</xyz.B><C>valueC1</C><C>valueC2</C><D></D><E/><F>1.50</F></abc.A>`: the prefix stays in the name (SAP's rule; SAP's example drops it, which settles F47), `""` is `<D></D>` and `null` is `<E/>` (SAP's example writes `""` as `<D/>`), numbers keep their text, compact with SAP's declaration. CSV to XML (`products.xsd`, `Products/Product`, `;`, header line, *XSD Elements*): compact with SAP's declaration and no final line break (376 bytes, as the client reported), the quoted field `"Notebook; Basic 15"` without its quotes, the empty line skipped, the empty value as `<Name></Name>`, element order from the XSD. Copied from screenshots; the simulator gives the same bytes. | Observed (one tenant) | S32 |
| F57 | XML to CSV on that tenant again, with separator `;` and *Include Attribute Values*: `Category;Type;Source` / `Scanners;Source Data;ERP` / an empty line / `ProductId;Name;Note` / `HT-1080;Photo Scan;"a;b"` / `HT-1081;Power Scan;"quoted"`. A value with the separator is put in double quotes; the quotes inside `"quoted"` are not doubled (not RFC 4180). The comma run (F55) wrote `a;b` without quotes. Pasted as text. | Observed (one tenant) | S32 |
| F58 | `fingerprint-v1.groovy` with `logMethods` on that tenant: the `ScriptLogger` behind 1.x `log` / `logger` has `logInfo(Object[])`, `logWarnings(Object[])`, `logErrors(Object[])`, `logErrors(LogMessage)`, `logErrors(LogMessage, Object[])`, `getLogger(Class)` and `getCategoryLogger(Class)` (both return `ILogger`), and no `info`, `debug`, `warn` or `error`. Where its output goes is not known. | Observed (one tenant) | S32 |
| F59 | `probe-mapping-v1.groovy` in a Message Mapping on that tenant (`Root` → `Result`, `Value` → `describeQueue` → `Info`, input `a`, `b` / `c` in two `Group`s): mapping scripts run on **Groovy 2.4.21 and Java 21** (SAP SE 21.0.13). The `String[]` function received the first context only, `a` and `b`, as `String`s without a context change entry, so the signature `(String[], Output, MappingContext)` gave *All values of a context*. Only that call's document reached the output: `Result` occurs once, so a target field under it takes the first context (expected if the calls' results are joined by context changes; not shown directly). Behind the interfaces: `com.sap.xi.mapping.camel.impl.MappingContextImpl` (`getHeader`, `getProperty`, `setHeader`, `setProperty` and an undocumented `invalidateCache()`) and `com.sap.xi.mapping.camel.impl.OutputImpl` (SAP's documented methods and `getResultList()`, which returns `com.sap.aii.mappingtool.tf7.rt.ResultList`, PI's mapping runtime class). An unknown header is null; `CamelHttpMethod` was `GET`. | Observed (one tenant) | S32 |
| F60 | The same probe with the target `Result/Call/Info`, `Value` (`Group` → `Call`) and `request.xml`: one `Call` per source context, each with the document of one `describeQueue` call (`a`, `b`, then `c`), so the per-context results are **joined by context changes**. `describeValue(String, MappingContext)` returned `a`, `b` and `c` in their contexts: a single-value function is **called once per value**. The `ResultList` behind `Output` is `com.sap.aii.mappingtool.tf7.rt.ResultListImpl`; its public constants are the strings `CC` = `__cC_`, `SUPPRESS` = `_sUpPresSeD_` and `XSI_NIL` = `_!xSi#NiL$_`. SUPPRESS in a function's input was not tested. | Observed (one tenant) | S32 |
| F61 | The same mapping with `request-empty-context.xml` (an empty `Group` between `a`, `b` and `c`): three `Call`s; the middle one holds a `describeQueue` document with no entries and no `Value`. A `String[]` function is **called for an empty context, with an empty array**; a single-value function is not called there. | Observed (one tenant) | S32 |
| F62 | Encoder, Decoder and Message Digest steps in exported iFlows: `activityType` `Encoder` with `encoderType` = `Base64 Encode` or `ZIP Compress`; `activityType` `Decoder`, whose scheme is also in the key `encoderType` (`Base64 Decode`, `ZIP Decompress`); `activityType` `MessageDigest` with `digestAlgorithm` (`SHA-512`), `canonicalizationMethod` (`none`) and `targetHeader` (`SAPMessageDigest`). `cmdVariantUri` repeats the scheme (`cname::ZIP Compress`). Not seen: the GZIP schemes, the digest's filter XPath and its namespace mapping, any other value of `digestAlgorithm` and `canonicalizationMethod`, the MIME Multipart variants → Q-E1 | Observed (8 steps in 3 public iFlows on GitHub, 2026-11-16) | S50 |
| F63 | SAP's documentation of the three steps. Decoder: *Base64 Decode*, *GZIP Decompress*, *ZIP Decompress*, no settings. Encoder: the page's list of schemes is empty in the mirror; its example encodes `<message>`, CR LF, tab, `Input for encoder`, CR LF, `</message>`, CR LF to `PG1lc3NhZ2U+DQoJSW5wdXQgZm9yIGVuY29kZXINCjwvbWVzc2FnZT4NCg==`, plain Base64 of those bytes (60 characters, too short to show whether long output is wrapped). Message Digest: "can only process XML content"; the message is turned into canonical XML, the digest of that goes to a header and "the original message remains unchanged"; *Filter (XPath)* is optional, with a prefix namespace mapping; canonicalization is Canonical XML 1.0, Exclusive 1.0 or 1.1, each with or without comments, or *None*; algorithms MD5, SHA1, SHA256, SHA384, SHA512 (default SHA512); *Target Header Name* defaults to `SAPMessageDigest` and holds the Base64-encoded digest. The SAP blog on ZIP and GZIP that the pages link could not be opened (HTTP 403); nothing here rests on it. What the pages leave open → Q-E1 | Observed (SAP docs) | S49 |
| F64 | Camel 3.14.7's data formats for Base64, GZIP and ZIP with their defaults, read in their source. camel-base64: encoding writes lines of 76 characters, each ended by CR LF, the last one too (commons-codec's chunked Base64); decoding is lenient: characters outside the alphabet are skipped (`-` and `_` count as Base64), the text ends at the first `=`, an incomplete last group gives the bits it has, nothing is an error. camel-zip-deflater (GZIP): a GZIP stream without a file name, time 0 and operating system 255 in its header. camel-zipfile: compressing writes one entry named after the file name part of `CamelFileName`, else after the message id, and sets `CamelFileName` to that name with `.zip`; decompressing (without an iterator) takes the first entry as the body and its name as `CamelFileName`, and fails with "Zip file has more than 1 entry." when there is a second. A scratch run against commons-codec 1.16.1 and commons-compress 1.26.1 gave the same bytes as the simulator for 401 random inputs (Base64 Encode, GZIP) and 20,000 dirty Base64 texts. **Whether CPI's Encoder and Decoder are these data formats is not known** → Q-E1 | Read (Camel source) | S51 |
| F65 | An exported Value Mapping artifact (zip): `value_mapping.xml` with `<vm version="2.0">`, one `<group id>` per set of equivalent values, one `<entry>` per side with `<agency>`, `<schema>` and `<value>`; an entry may carry `isDefault="true"`. Next to it `META-INF/MANIFEST.MF` (`SAP-BundleType: ValueMapping`), `.project` and `metainfo.prop` (`source=`, `target=`). Seen: two agencies per artifact, one schema each, defaults on entries whose value repeats in other groups | Observed (one export, 2026-11-16) | — |
| F66 | An exported integration package (zip): `resources.cnt`, Base64 of JSON with `resources` (`id`, `resourceType`, `name`, `uniqueId`, …) and `relations`; one `<id>_content` per resource, itself the artifact's export zip; `contentmetadata.md` (Base64 properties), `hash`, `ExportInformation.info`. Types seen: `ContentPackage` (the package, no content to import), `IFlow`, `ScriptCollection`, `ValueMapping`, `RESTAPIProvider`. A Script step whose script lives in a script collection has the key `scriptBundleId` = the collection's `uniqueId`, and the iFlow's manifest a `Require-Capability: scriptcollection.<id>` | Observed (one export, 2026-11-16) | — |
| F67 | A Groovy script run by `cpiwb-groovy-v1.jar run` or `cpiwb-groovy-v2.jar run` under `-agentlib:jdwp` (SapMachine 25, Windows): jdb's `stop at <class>:<line>` binds in `processData` and stops there, with `message` and the local variables readable. The class is the file name made a Java name (`enrich-order.groovy` → `enrich_order`, source name `enrich_order.groovy`), so a debugger that maps by file name needs a file name that is a Java name. IntelliJ and VS Code not tried → Q-D1 | Observed (simulator, 2026-11-16) | — |
| F68 | The `.iflw` beyond the steps (S53): one `bpmn2:collaboration`; `bpmn2:participant` with `ifl:type` `EndpointSender`, `EndpointRecevier` (sic) or `IntegrationProcess`; main and local processes differ only in the process's `cmdVariantUri` (`cname::IntegrationProcess` / `cname::LocalIntegrationProcess`), not in name or participant type, and an iFlow can have several main processes. Adapters are `bpmn2:messageFlow` with `ComponentType` and `direction`. An Exception Subprocess is a `bpmn2:subProcess` inside its process with `activityType` `ErrorEventSubProcessTemplate`, connected by no flow. A Router is an `exclusiveGateway` whose `default` attribute names the default route; the other routes carry a `conditionExpression` and `expressionType`. A Sequential Multicast is a `parallelGateway` (`activityType` `SequentialMulticast`). Process Call and Looping Process Call: `callActivity` with `activityType` `ProcessCallElement`, `subActivityType` `NonLoopingProcess` / `LoopingProcess` and `processId` = the called process's id. `bpmndi:BPMNDiagram` has a shape (absolute `dc:Bounds`) for every element and an edge for every flow, with waypoints from centre to centre. Not seen: Join and parallel Multicast | Observed (public exports, 2026-11-16) | S53 |
| F69 | Adapter property keys and parameter files in exported iFlows (S55). In each `bpmn2:messageFlow`, `ComponentType` names the adapter and these keys hold an address, a credential alias, a queue or a keystore alias. SFTP / PollingSFTP / FTP: `host`, `path`; `credential_name`; `privateKeyAlias`. HTTPS (sender): `urlPath`. HTTP: `httpAddressWithoutQuery`; `credentialName`; `privateKeyAlias`. SOAP: `address`, `soapWsdlURL`; `credentialName`, `UserNameTokenCredentialName`, `WsdlUserNameTokenCredentialName`; `privateKeyAlias`, `PrivateKeyAliasSigning`, `PublicKeyAliasEncryption`, `PrivateKeyAliasResponseSigning`, `PublicKeyAliasResponseEncryption`. HCIOData (OData): `address`; `alias` (a credential); `odataCertAuthPrivateKeyAlias`. SuccessFactors: `address`; `alias`. JMS: `QueueName_inbound`, `QueueName_outbound`. ProcessDirect: `address`. Mail: `server`; `user`, `tokenCredential`. IDOC: `address`; `credentialName`; `privateKeyAlias`. AS2: `address`, `receipientURL` (sic), `mdnTargetURL`; `userCredentialAlias`, `mdnUserCredentialAlias`; several `…KeyAlias…` keys. AMQP: `host`, `path`; `credentialName`; `destinationName` (queue). RFC: `destination`. XI: `Address`, `Address_inbound`; `BasicAuthCredentialName`; `ClientCertificateAlias`. AdvancedEventMesh: `host`; `queueName`, `topicEndpointName`, `destinationName`. JDBC's `alias` names a data source, not a credential. The same key means different things in different adapters, so the table is per adapter. `parameters.prop` is a Java properties file (spaces and colons escaped); `parameters.propdef` is `<parameters><parameter><key/><name/><type>xsd:…</type><isRequired/><constraint/><description/><additionalMetadata/></parameter>…`, optionally followed by `<param_references>` | Observed (443 public iFlows, 2026-11-17) | S55 |

---

## 3. Build vs. reuse — the guiding decision

### 3.1 The principle
> **We build everything that makes it "CPI". We reuse only what CPI itself runs on.**

Why: the goal is to behave like CPI. CPI executes scripts with Groovy, converts types with Apache Camel, and transforms XML with Saxon. Using the same libraries is the most direct way to get the same behaviour. Rebuilding them ourselves would make the simulator *less* like CPI, not more: every self-written engine adds its own bugs and its own edge-case behaviour, which is exactly what we want to avoid.

### 3.2 What we build ourselves (our product)

| Component | What it does |
|---|---|
| **CPI Exchange model** | Message, headers, exchange properties, attachments with Java types; one-shot stream semantics |
| **Script API v1 + v2 implementations** (clean-room) | Same package/class names as SAP, so scripts run unchanged |
| **Groovy step runner** | Loads the script with the right Groovy version, calls the configured function, captures results, maps errors to script lines |
| **MPL emulation** | `messageLogFactory`, string properties, custom header properties, log attachments |
| **Platform API mocks** | `ITApiFactory`: Secure Store, Value Mapping, Keystore, Partner Directory, number ranges — data from local files (no Data Store: no public API, F28) |
| **XSLT step runner** | CPI parameter binding (F10), `$exchange` + `setHeader`/`setProperty` as Saxon integrated extension functions (F11, F15), output handling, `xsl:include`/`xsl:import` resolution |
| **XSD validator step**, **XPath tester** and **flow steps** | CPI-style configuration and error reporting; Content Modifier, Filter, Router, Splitter, Gather, Converter, Encoder / Decoder and Message Digest (§10) |
| **Runtime profiles + calibration probes** | One engine per CPI runtime (Saxon 9.9 / 12, Groovy Script 1.x / 2.x, both on Camel 3.14); probe iFlows to verify against a real tenant (§8) |
| **Compatibility linter** | v1→v2 migration warnings, Groovy 2→4 breaking changes, EE-only XSLT features |
| **Test-case format, runner, CLI** | Regression tests without a tenant |
| **Desktop app and UI** | The editor, the panels, the diff, the "live run" loop |

### 3.3 What we reuse (the foundation CPI runs on)

| Component | Version | Licence | Why |
|---|---|---|---|
| Groovy | 2.4.21 and 4.0.29 | Apache-2.0 | Exactly what CPI uses (F1, F2) |
| Apache Camel core (type converters, Simple) | 3.14.7 for 1.x and 2.x (F48) | Apache-2.0 | Same type conversion rules as CPI (F3) |
| Saxon-HE | **9.9.1-8** (CPI line) and 12.9 | MPL-2.0 | Same engine family and release line as CPI (F8, F13) |
| JDK (Eclipse Temurin) | 21 (Java sources target 17+) | GPLv2 + Classpath Exception | Matches the tenant's Java major version (F48); the vendor/build differs |
| Tauri, Monaco editor | current | MIT / Apache-2.0 | App shell and code editor |

All of them are long-lived projects backed by foundations or companies (Apache, Saxonica, Eclipse, the Tauri team, Microsoft) — not one-person projects.

### 3.4 What we deliberately don't do
- **Use go-xslt (or similar) as a component.** It is a reference for features and UX only (a desktop XSLT tester with live results, error markers, project folders). Fidelity-wise it would be a step away from CPI, because CPI runs Saxon, and it is a pre-1.0 one-person project.
- **Write our own XSLT 3.0 / XPath 3.1 / XSD engine.** This is years of work (the W3C test suites alone have ~127,000 cases) and would still behave differently from Saxon in implementation-defined details (serialization defaults, `format-number`/`format-dateTime` output, regex and collation edge cases, error codes). It would work against the core goal. If a reason appears later (e.g. a browser version), this can be re-evaluated.
- **Write our own Groovy implementation.** Not feasible (F17), and scripts call the Java class library directly.

---

## 4. Method: how REA is applied

*Reverse Engineer Anything* (REA, `morluto/rea`) is a workflow for studying the behaviour of a closed target and rebuilding it clean-room. Its tools (MCP server, disassemblers) are made for shipped binaries and are not installed here. Rebuilding an **open-source** project like go-xslt with it would make no sense: its source code is public, and copying it would not be our own work.

The relevant evidence method observes **CPI itself** as a black box:

1. **Question checklist** — what exactly does the step do? (parameter binding, type conversion, encoding, error handling, …) — Appendix A.
2. **Observe** — probe iFlows on a test tenant record inputs and outputs for each question (§8.3). No decompiling of SAP binaries.
3. **Ledger** — every finding labelled Observed / Inferred / Unknown with its evidence (§2).
4. **Rebuild** — our clean-room implementation of the observed behaviour.
5. **Differential test** — run the same probe cases locally; every difference is a bug or a documented limitation. Repeat.

Reference products (GrooveBox, Contiva, go-xslt's desktop app, CPI's own simulation mode) are studied for **features and UX** only — by using them, not by copying code or assets.

---

## 5. Architecture

### 5.1 Overview

```
┌────────────────────────────── Desktop app ──────────────────────────────┐
│  UI: TypeScript + Svelte 5, Monaco editor (webview)                     │
│  Shell: Rust (Tauri 2) — engine supervisor, MCP server (cpi-workbench mcp) │
└───────────┬───────────────────────────────────────────┬─────────────────┘
            │ JSON-RPC over stdio                       │ JSON-RPC over stdio
            ▼                                           ▼
┌───────────────────────────────┐        ┌────────────────────────────────┐
│ cpiwb-xml (NATIVE binary)     │        │ cpiwb-groovy (JVM, one per     │
│  CPI XSLT step, XSD, XPath,   │        │  script version)               │
│  XPath flow steps, converters │        │  Script API v1 / v2, MPL,      │
│  Saxon-HE 9.9 or 12           │        │  platform API mocks, Camel     │
│  compiled with GraalVM        │        │  Simple, Content Modifier      │
│  → no JVM at runtime          │        │  Groovy 2.4.21 | 4.0.29        │
└───────────────────────────────┘        └────────────────────────────────┘
          shared code base: engine/core (Java 17+) — JSON-RPC, project files,
          test runner, iFlow import
```

Details: [architecture](architecture.md).

### 5.2 Technology choices and why
- **Engine core in Java** (built for 17, run on 21 like a tenant; `engine/core`, `engine/xml`, `engine/groovy`): it must sit directly on Groovy, Camel and Saxon, which are Java libraries. Plain Java (rather than Kotlin) keeps the runtime small and is the safest choice for GraalVM native compilation.
- **XSLT/XSD/XPath as native binaries** (GraalVM Native Image): both Saxon profiles are built natively.
  The same sources run as jars for CLI use and development without GraalVM; native metadata is tested
  by a workload on each supported installer OS.
- **Groovy on a JVM**: unavoidable (F17). v1 and v2 run as separate worker processes so Groovy 2 and 4 never mix, and a runaway script only kills its own worker.
- **Shell in Rust (Tauri 2)**: small native binary, uses the operating system's webview (no bundled browser), good installer and update tooling. The UI needs a real code editor, which the webview + Monaco provides; a pure-Rust UI toolkit (egui, as in ArtCraft) has no comparable editor.

### 5.3 Footprint and performance

* One **all-in-one offline installer** includes both native XML engines, both Groovy engines and
  a shared, jlink-trimmed Java 21 runtime. No first-use download or separate Groovy pack.
* Windows NSIS installs per user without admin rights; MSI installs per machine. macOS uses a DMG.
* Native XML work needs no JVM. Groovy starts on demand, uses AppCDS and stops after 10 idle minutes.
* Compiled scripts/stylesheets are cached by content and profile. JVM heap is fixed at 512 MB;
  engine calls time out after 30 seconds. These are not project-configurable settings.
* Messages travel through JSON-RPC, not temporary files. MCP shortens returned bodies but retains
  bounded whole results for chaining ([MCP](mcp.md#passing-results-on-runid)).

Historical measurements are in [Architecture](architecture.md#measured-performance). They were
made on older runtimes and are not current guarantees. Remaining performance goals are a usable UI
within 1 second, under 200 MB without a Groovy worker, a cold Groovy run within 2 seconds, and cached
runs within 100 ms (1 MB input for XSLT). Re-measure before claiming those budgets are met.

---

## 6. Groovy Script Simulator

### 6.1 Input
- **Body**: XML / JSON / text (from a file or typed), as `String`, `InputStream` or `byte[]`; pretty-print.
- **Headers** and **exchange properties**: tables with name, value and **Java type** (`String`, `Integer`, `Long`, `Boolean`, `byte[]`, `Date`, `null`, …) — wrong types are a common cause of CPI failures.
- **Attachments** and **SOAP headers**: input tabs or test cases (`case.json`).
- **Script** in Monaco; **function name** (default `processData`, as in the step configuration).
- **Libraries**: `.jar` files from the project's `lib/` and the iFlow's `src/main/resources/lib/` on the classpath.
- **Profile**: `Groovy Script 1.x` or `2.x`.

### 6.2 Output
- Body with diff to input; headers and properties with added/changed/removed and type changes highlighted.
- **MPL view**: string properties, custom header properties, log attachments.
- **API call log**: which SAP APIs the script called (Secure Store, Value Mapping, …) — an idea taken from GrooveBox.
- Console (`println`), errors mapped to script lines, timing.

### 6.3 Our Script API implementation
**v1** `com.sap.gateway.ip.core.customdev.util.Message`: `getBody()`, `getBody(Class)`, `setBody`, `getHeaders`, `getHeader(String, Class)`, `setHeader(s)`, `getProperties`, `getProperty`, `setProperty`/`setProperties`, attachment methods, `getBodySize`, `getAttachmentsSize`; `messageLogFactory` binding with `MessageLog` (`setStringProperty`, `addCustomHeaderProperty`, `addAttachmentAsString`).

**v2** `com.sap.it.script.v2.api.*`: `Message` (incl. `getHeader(String)`, `getHeader(String, Object)`, `Attachment` methods, `clearAttachments`, `resetBodyStream`, `getExtensionFunctions` with `ExchangeExtensionFunctions`; `ProcessDirectService` fails with `UnsupportedInSimulator`), `attachment.DefaultAttachment`, `exceptions.*`. Not simulated: `Exchange`, `CamelContext` and the `language.Simple*` classes (F29).

**Type conversion**: our `Message` wraps a real Camel `DefaultExchange`, so `getBody(String)`, `getHeader(name, Type)` etc. use Camel's actual converters — the same rules as on CPI.

**Platform APIs** (`ITApiFactory`): mocks fed from `platform/` files in the project (credentials, value mappings, partner directory, keystore, number ranges; [project files](project-files.md)). Other services raise `InvalidContextException`, as SAP's Javadoc says (F27).

### 6.4 Linting
v1 imports in a v2 profile; Groovy 2→4 breaking changes (e.g. `groovy.util.XmlSlurper` → `groovy.xml.XmlSlurper`); wildcard imports (discouraged by SAP's upgrade guidance, S3). *Compare* runs a script on 1.x and 2.x side by side.

---

## 7. XSLT Mapping Simulator

### 7.1 The workflow (same as the Groovy UI)
Three panes: **Input** (body, headers, properties) → **XSLT** (Monaco, with includes) → **Output** (body, headers/properties set by the mapping, `xsl:message` console, errors with line/column). **Live mode** re-runs on every change (debounced), so editing the mapping shows the result immediately.

### 7.2 CPI step behaviour we implement
- **Parameter binding** (F10): every `xsl:param` gets the value of the header or exchange property with the same name. The precedence when both exist, the type passed (string vs. typed value), and behaviour for missing values (F12) were built on Camel's behaviour and confirmed with probe iFlows on a tenant (F51; Appendix A, Q-X2…X4).
- **`$exchange` and `setHeader` / `setProperty`** (F11): registered as **Saxon integrated extension functions** in namespace `http://sap.com/it/`, exactly where CPI registers them. Calls are recorded and applied to the output message.
- **Output**: result becomes the new body; output encoding and serialization as configured in the stylesheet (Saxon decides, as in CPI).
- **Includes/imports** resolved from the project's mapping folder, like iFlow resources.
- Step options such as source/target from header or property (newer step versions): to verify (Q-X6), not built.

### 7.3 Saxon profiles

| Profile | Engine | Purpose |
|---|---|---|
| **CPI 9.9 (default)** | Saxon-HE 9.9.1-x, native | Same release line as CPI (F8) — closest behaviour |
| **Modern HE** | Saxon-HE 12.x, native | Supports higher-order functions and `xsl:evaluate`, which CPI's EE 9.9 has but HE 9.9 lacks (F14) |

Features that need Saxon-PE/EE (higher-order functions on 9.9, schema-aware XSLT, streaming) get a diagnostic that names the profile to use. Reflexive Java calls are supported for JDK classes, since SAP's own samples use them (F32). A *bring your own Saxon-EE* profile was considered and not built.

---

## 8. Fidelity: profiles, calibration, limits

### 8.1 Runtime profiles
A profile is an engine build per CPI runtime (Saxon 9.9 / 12, Groovy Script 1.x / 2.x). Since the probe run of 2026-11-09 the Groovy profiles follow a tenant's fingerprint (F48–F50): Java 21, Camel 3.14.7, UTC, `en_US` and the libraries scripts can load. Camel 2 (tenant software 6.x / 5.x) was dropped in 0.3.0.

### 8.2 Isolation and safety
Fresh exchange per run, a 30-second timeout after which the engine restarts, captured output. XML runs: no network, file access limited to the project folder (following symbolic links). Groovy: no sandbox; scripts run with the user's rights, and the user guide says to run trusted scripts only.

### 8.3 Calibration and probe iFlows (the REA "observe" step)
- **Fingerprint iFlows** report Java, Groovy, Camel and Saxon versions, default charset, timezone, locale.
- **Probe iFlows**, one per open behaviour question (Appendix A); the simulator's own output is the baseline (`probes/baseline-simulator/`).
- Differences become bugs or documented limits ([fidelity](fidelity.md)), and probe cases become regression tests.

### 8.4 Limits we accept
- Adapter-specific headers exist only if set in the input.
- Tenant-specific settings (memory limits, custom libraries, real platform data) are not reproduced.
- Saxon-EE-only features (§7.3).
- SAP-internal classes outside the public script API.

---

## 9. Project and test-case format

```
my-project/
  cpi-workbench.json      # marks the folder as a project (name)
  scripts/  mappings/  schemas/
  lib/                    # extra .jar
  platform/               # mocked secure store, value mappings, partner directory, keystore, number ranges
  iflows/                 # imported iFlow exports
  tests/
    order-to-idoc/
      case.json           # step type, profile, function name, typed headers/properties
      input.xml
      expected-output.xml # optional → regression assertion
```
Plain, git-friendly files: [Project files](project-files.md). Since 2026-11-17 test cases are this
repository's regression tests only (`testdata/regression-project/tests`), not a user feature; users test a
step by running it in the app. The engines' `test` command (`cpiwb-xml-he99 test tests/`,
`java -jar cpiwb-groovy-v1.jar test tests/`) runs the cases in CI and exits non-zero on failure.
Format: [Regression test cases](test-cases.md). MPL-export-to-case import (format unknown, F18) and flow-step
test cases are not planned.

---

## 10. Roadmap

Completed delivery phases are recorded in the [changelog](../CHANGELOG.md), not kept as roadmap
items. Current coverage and limits are in [status](status.md).

Remaining work:

* Code signing and automatic update installation (§10.2); the manual update check already exists.
* Fidelity verification in [Appendix A](#appendix-a--question-checklist), particularly the prepared
  Encoder / Decoder / Message Digest and Gather probes (Q-E1, Q-F1).
* [Manual release checks](manual-test.md), including macOS and a real MCP client.
* Permanent regression coverage for receiver coordination and browser XML warning paths
  ([status](status.md#evidence-and-automated-coverage)).
* Current Windows/macOS performance measurements (§5.3).
* Release 0.6.0 (decided 2026-11-17), planned in [§10.6](#106-release-060): test cases become internal,
  UI ⇄ MCP parity tests for Flow Steps, a fidelity indicator in the app, and an offline where-used and
  parameter overview over imported iFlows.

These are open tasks and verification gaps, not a release-date commitment.

### 10.1 MCP server design

Setup, tools, protocol revisions and privileges: [MCP server](mcp.md). Decisions retained here:

* **One server in front of all engines**, as a subcommand of the desktop executable: reuse the
  supervisor, bundled runtime and Windows path alias rather than ship another runtime.
* **No protocol dependency:** implement the supported MCP revisions on `serde_json`.
  SDK compatibility and release-executable smoke coverage are recorded in [MCP](mcp.md#protocol).
* **The app leads:** step tools follow the tabs' engine calls; file tools provide the tree/save/import
  workflow to clients without filesystem tools. Test suites remain CLI-only; lint travels with script results.
* **Source-derived reference:** generate API topics from completions and clean-room classes, platform
  formats and fidelity from the docs, to avoid a second manually maintained API description.
* **Results by reference:** opaque, bounded, process-local `runId` handles preserve bodies, headers
  and properties for chaining. Intermediate files were rejected because they persist and lose metadata.
* **No sandbox or network server:** project file tools enforce paths; user code keeps the same
  privileges as in the app. Payloads may contain prompt injection.

### 10.2 Updates

The manual update check is implemented ([User guide](user-guide.md#what-a-run-can-access)).
It contacts GitHub only when clicked, never on startup; installation stays manual.
`open_release_page` accepts only this repository's release pages and uses the OS opener.
`tauri-plugin-opener` was rejected because it added about 40 crates for one link in the
2026-11-07 assessment. Real desktop-window checks remain in the release checklist.

**Open: automatic update installation.** Research on 2026-11-04 used `tauri-plugin-updater` 2.14.1
and Tauri's NSIS template; re-check versions before implementing. The proposed updater reads a
release `latest.json`, verifies updater signatures and installs the matching NSIS/MSI/macOS package.
Updater signatures are separate from OS code signing. On Windows the researched plugin exits with
`process::exit`, and NSIS uses Restart Manager for running processes. Required work:

* **Stop the engines in `on_before_exit`.** `process::exit` skips `RunEvent::Exit` in `lib.rs`, so the
  engines would otherwise keep running and may hold files in the install folder.
* Account for **MCP servers** using the same executable: installation can interrupt agent sessions.
  Add user-facing warnings when automatic installation is implemented.
* **CI:** `bundle.createUpdaterArtifacts`, signing on Windows/macOS, and a step in the `release` job
  that writes `latest.json` (version, notes, URL and signature per supported target:
  `windows-x86_64-nsis`, `windows-x86_64-msi`, `darwin-aarch64-app`). The workflow
  does not use `tauri-action`, so this step is our own.
* **Key custody:** losing the private updater key ends automatic updates for every installed app;
  it needs a backup outside GitHub.
* **Manual test** ([Manual test](manual-test.md)): install the previous release and update from the
  app, per bundle.

To verify then: whether SmartScreen prompts for an installer the updater downloaded (an assumption:
no Mark of the Web, so no prompt); replacing the ad-hoc signed macOS app; the updater behind a
company proxy (it has proxy settings; whether it uses the Windows system proxy is not checked).

### 10.3 Message Mapping scripts

Run custom functions on their own, not graphical `.mmap` execution. Public APIs follow SAP's
Javadoc (F40); execution types follow the signature evidence (F44, F59–F61), and 2.x warns because
SAP excludes it from Message Mapping (F41). Queue-wide execution is not offered (F44).
Single-value functions are intentionally called once with the supplied values, rather than once
per queue value. Marker identity remains an assumption (Q-M1).
Usage: [Mapping functions](user-guide.md#groovy-completions-and-message-mapping-functions);
case format: [Regression test cases](test-cases.md#message-mapping-functions).

### 10.4 Converter design

JSON ↔ XML and XML ↔ CSV as a *Converter* step in the Flow Steps tab, not a tab of their own: the tab already
has the input message, the step settings, the result and *Use as input* to chain steps, and the iFlow import
and `run_flow_step` cover it. The engine class is `ConverterStep` in the XML engine (native, no new library;
its JSON reader keeps member order, duplicates and number text). Rules come from SAP's documentation (F47,
S30), the iFlow keys from public iFlows (F46). Encoder / Decoder with Base64, GZIP and ZIP and Message
Digest are also implemented (W4). EDI ↔ XML, the EDI Extractor, MIME Multipart and XML Modifier remain
unsupported and unscheduled.

### 10.5 Workflow roadmap

On 2026-11-13 other tools for CPI development were read: their own pages and READMEs (S33–S46). Nothing
was installed or measured. Three pages could not be opened and are known from search results only: SAP
Help's pages on simulation, the blog on JDI debugging (S34) and IntegratedGroovy (not listed for that reason).
This is a dated feature review, not a current market survey or an independent assessment of the tools.
Only relevant ideas and exclusions are retained below.

Only ideas are taken. XSLTDebugX is AGPL-3.0 and CPI Helper GPL-3.0; none of their code goes into this
Apache-2.0 repository.

| Tool (source) | What it is, as its page says | Where code runs |
|---|---|---|
| Groovy CPI Studio (S33) | VS Code extension, 1.0.0, "MVP": Groovy 4.0.29 with mocks of `Message` and `MessageLog`; no attachments, SOAP, Camel or platform services | Locally |
| SAP CPI Groovy Script (S34) | VS Code extension: local run, breakpoints through JDI, extra jars, a JKS keystore, value mappings from a ZIP; needs its own Groovy and JDK | Locally |
| XSLTDebugX (S35) | Browser IDE on Saxon-JS 2 (XSLT 3.0 only): `cpi:setHeader` / `cpi:setProperty`, an XPath mode (matches highlighted, copy an element's XPath, expression history), an examples library, share links | Browser |
| Contiva Groovy IDE (S36) | Web IDE: Groovy 2.4.21 / 4.0.29 with a mock message, an XSLT editor, a run history, MCP as a paid option | Their server |
| GrooveBox (S37) | Web IDE: input tabs for value mapping, attachments, secure store and partner directory; output tabs for MPL and API calls | Their server |
| All-in-one Tools for CPI (S38) | Browser toolkit: XSLT, XPath, XSD, Groovy, value mapping, JWT, certificate and encoding tools; no licence file | Browser |
| SAP's integration flow simulation (S39) | Simulates a part of an iFlow in the web UI: at most 10 steps per run and an input below 100 KB (SAP Help, from search results) | Tenant |
| Figaf (S40) | Commercial: regression tests from recorded messages against a tenant, with mocked services | Tenant |
| CPI Developer Studio (S41) | Commercial, deployed to BTP: iFlow editor, XSLT test, "25+ lint rules", monitoring, AI functions | Tenant |
| CPI Helper (S42) | Browser extension for the tenant's web UI: trace per step; its GroovyDebugX and xsltDebugX plugins hand a traced step's payload, headers and properties to an external IDE | Tenant's web UI |
| cpictl (S43) | CLI and MCP server for tenant operations (deploy, logs, lint, snapshots); its mock tenant imitates the tenant's API, not the runtime | Tenant |
| cpi-saper-dev-mcp (S44) | MCP server, beta: edits iFlows locally, runs XSLT locally and Groovy through a runner iFlow on a development tenant | Local and tenant |
| sap-cpi-mocks (S45), CPILint (S46) | A Maven library of `Message` mocks for Spock tests; a governance lint for iFlows (MIT) | Locally |

#### Retained workflow decisions and verification

**W1–W2. Platform inputs and CPI Helper hand-over.** Inspired by GrooveBox and CPI Helper (S37,
S47). Current usage: [Groovy/platform inputs](user-guide.md#groovy-xslt-and-platform-data) and
[CPI Helper](user-guide.md#a-message-from-a-tenants-trace-cpi-helper); data formats:
[project files](project-files.md). Windows hand-over evidence and the upstream cut-off body remain
recorded in Q-T2/Q-T3.

**W2b. Desktop receiver design.** An opt-in loopback page transfers the URL fragment by a
same-origin POST, with confirmation and no automatic code execution. This does not authenticate
the sender. Current limits and coordination: [Architecture](architecture.md#cpi-helper-receiver).
* A commandline-based `cpi-workbench://` scheme was rejected: Windows `CreateProcessW` accepts at
  most 32,767 characters including the terminator, while an observed link was about 70,000.
  Chromium's Mojo URL serialization is limited to 2,097,152 characters; other browser paths may
  differ. These are transport limits, not limits on the original traced body (S48).
* Evidence records Windows pasted and receiver transfers (Q-T2), including a 25 MB body (Q-T3).
  Full plugin-click acceptance, macOS and Local Network Access remain in
  [manual test 2.11j](manual-test.md). No claim of cross-platform verification is made.

**W3. XPath text locations.** Inspired by XSLTDebugX (S35). Structural positions are mapped to
source text; entity references disable locations when they could hide nodes or namespaces.
Usage: [XSD and XPath](user-guide.md#xsd-and-xpath).

**W4. Encoder, Decoder and Message Digest (implemented; tenant probe pending).** *Prompted by the
encoding tools in All-in-one.*
* Sources: SAP's documentation of each step (F63), the iFlow keys from public iFlows (F62), and Camel's data
  formats for what SAP does not document (F64). The probe for Q-E1 is prepared (`probes/encoders/`, step 9 of
  its README, with the simulator's baseline) and not run yet; until it has, Base64's line wrapping, GZIP's
  header, the ZIP entry's name and the digest's *None* are assumptions.
* Current usage and binary-body handling: [Flow Steps](user-guide.md#flow-steps) and
  [MCP result chaining](mcp.md#passing-results-on-runid). `DigestStep` uses the JDK's XML signature API;
  native-image metadata must include its canonicalizers and resource bundle ([Building](building.md#native-image-metadata)).
* MIME Multipart, EDI and XML Modifier stay out. Assumptions and refusals: [Fidelity](fidelity.md).

**W5–W6. CLI reports and comparison rules.** JUnit output was prompted by Figaf/cpictl;
`expect.ignore` is a simulator feature. Formats: [Test cases](test-cases.md#casejson).
XML ignores use JDK XPath 1.0 so all four engines read the same rules; JSON ignores use JSON Pointers.
Reports from all four engines validated against Surefire's published schema with xmllint on
2026-11-16. Reading them with a CI reporter remains unverified; the workflow does not use `--junit`.

**W7. Value Mapping and package import.** Inspired by SAP CPI Groovy Script (S34).
`ArtifactImport` detects exports by their entries; F65/F66 record the observed formats, S52 the
bidirectional/default interpretation. Usage and collision rules: [iFlow import](user-guide.md#iflow-import).
Repeated values without a default remain an assumption (Q-VM1).

**W8–W9. Run history and bundled examples.** Inspired by Contiva, XSLTDebugX and GrooveBox.
History is intentionally session-only; examples omit dependencies on other project files.
Usage: [User guide](user-guide.md#desktop-app).

**W10. External Groovy debugging.** See [Debugging](debugging.md) for JDWP setup.
Breakpoints bind in jdb on both jars (F67); IntelliJ and VS Code remain unverified (Q-D1).
The bundled JRE lacks `jdk.jdwp.agent`, so debugging requires a full JDK. An in-app debugger is
not planned (see exclusions below).

**W11. iFlow outline and diagram.** *Seen in cpi-saper-dev-mcp (S44) and CPI Developer Studio (S54).*
`iflow.models` reads the model as F68 describes; `iflowmodel.ts` orders and draws it.
The diagram uses hand-written SVG rather than bpmn-js (its licence keeps a visible mark) or Mermaid
(which loses the exported layout). Join and parallel Multicast were not observed (F68).
Usage: [iFlow import](user-guide.md#iflow-import).

#### Not taken over

| What | Seen in | Why not |
|---|---|---|
| Anything that connects to a tenant: deploy, monitoring, reading traces, running Groovy through a runner iFlow, several tenants | S40, S41, S43, S44 | The app stays offline (§1) and holds no credentials. S44 complements this project: its Groovy runs on the tenant, ours locally. |
| Running code on a server | S36, S37 | Offline; scripts and payloads stay on the computer. |
| AI functions inside the app (generate a script, explain a mapping, fix lint findings) | S41 | The MCP server is the way in for agents (§10.1); a model in the app needs a network and keys. |
| A governance lint for iFlows | S41, S43, S46 | CPILint does it under MIT. Such rules are house rules, not CPI behaviour, which is what this project imitates. |
| A mock tenant | S43 | It imitates the tenant's API, another product. |
| JWT, certificate and general utility tools | S38 | Not CPI steps. |
| Memory profiling of a simulation | S39 | Tenant limits are not reproduced (§8.4). |
| Generating public share links | S35 | Project folders are the sharing unit. Importing CPI Helper's local links is supported (W2), but the app does not publish message data to a sharing service. |
| A chain of steps in the app, run history in Flow Steps, breakpoints in the app's editor | S39, S36, S34 | Not wanted by the owner (2026-11-16). A chain would also need evidence for what happens between steps (§1); the MCP server chains runs by `runId`, and `docs/debugging.md` covers breakpoints. |
| Saving a test case from the app | — | Removed on purpose (2026-10-25): test cases are written by hand and run from the CLI. |
| A VS Code extension | S33, S34 | A second UI to maintain; the CLI and the MCP server connect editors. |

### 10.6 Release 0.6.0

Decided with the owner on 2026-11-17, after a second review of other CPI tools. Work happens on the branch
`feature/0.6.0` in the order below; each part is finished with its tests and docs before the next starts.
Ideas from that review that were *not* taken: test cases for flow steps, an MCP `run_tests` tool (both
would make test cases a user feature, see P1), transactional publication of whole packages, trace
redaction, an architecture document generator, schema / contract diffs and change-impact reports.
The review follow-up stages and validates archives before publishing and atomically replaces the Value
Mapping table; filesystem failures while publishing folders can still leave a partial package.

#### P1. Test cases are internal

`case.json` test cases exist for this repository's regression tests in CI. Users test a step by running
it in the app. The `test` command and the test runner stay; only the user-facing surface goes.

1. **Split `docs/test-cases.md`.** A new user page, `docs/project-files.md` (*Project files and platform
   data*), takes what users need: the project layout (without `tests/`), header and property types, script
   inputs (attachments, SOAP headers, `lib/`, Java calls from XSLT), the Message Mapping function semantics
   and *Mocked platform data*. `docs/test-cases.md` keeps the `case.json` format, the `test` command,
   `--junit` and `expect.ignore`, retitled for contributors and linked from `docs/building.md`, not from the
   user index.
2. **Repoint links.** `mcp/reference.rs` reads the platform topic from the new page (its error text and
   `mcp/tests.rs` follow); anchors in `user-guide.md`, `mcp.md`, `README.md`, `docs/README.md`,
   `install.md` and this plan move with the sections.
3. **User guide and install.** Remove test-case wording (Init's `tests/`, closing a case, a case's
   attachments and `platform` object, the *test cases* bullet, "as in `case.json`"). The command-line
   sections keep the run commands and drop `test`; `status.md` drops the *Tests and CLI* row's user claim.
4. **App.** *Init* no longer creates `tests/` (`ProjectFiles.java`, the Sidebar tooltip). Texts that name
   test cases (`PlatformPanel.svelte`, `QueueEditor.svelte`) are reworded. Opening a `case.json` keeps
   loading it into its tab, unadvertised: the e2e tests and `manual-test.md` use it to load regression cases.
5. **Release notes.** The release body in `build.yml` stops advertising test runs for the jars.
6. **Changelog** under *Unreleased*: Init no longer creates `tests/`; test cases are documented for
   contributors only.

#### P2. UI ⇄ MCP parity tests for Flow Steps

Today the Flow Steps tab (`store.svelte.ts`) and `mcp/flow.rs` build the engine calls separately, kept in
step by comments. Known drift: from an iFlow step without a value, the app sends an empty encoder / decoder
scheme and digest settings where the MCP server sends the defaults; converter lists travel as text from the
app and as arrays from the MCP server (the engine accepts both).

1. **A seam in TypeScript.** Move the defaults (`converterDefaults`, `flowStepDefaults`), the iFlow-step →
   settings conversion (`openFlowStep`'s mapping) and the `run*` builders into a plain module
   `app/src/lib/flowcalls.ts` that takes the engine call as a parameter. `store.svelte.ts` passes the real
   `call` and keeps only state.
2. **A seam in Rust.** The step functions in `mcp/flow.rs` call the engine through a function in `Ctx`
   instead of `tools::engine_call` directly, so a test can record calls without engines.
3. **Shared fixtures** in `testdata/flow-parity/<step>-<case>.json`: the step as `FlowSteps` emits it from
   an iFlow (and, where it differs, as manual settings), the input message, scripted engine answers for
   multi-call steps (`simple.eval`, `xpath.eval`, router routes) and the expected call sequence
   (`engine`, `method`, `params`). One fixture per step type at least, plus the defaults and the known drift.
4. **Two runners.** `app/tests/flowparity.test.mts` and a test in `mcp/tests.rs` replay every fixture
   with a recording caller and compare the calls; neither needs engines, so both run in CI as they are.
5. **Fix the drift** the fixtures expose: one rule for missing values (the defaults, as the MCP server
   does, which the tab already shows for a manual step) and one list form towards the engine.
6. **Docs.** `AGENTS.md` and `architecture.md` name the fixtures instead of "change both together";
   changelog line for the iFlow-step defaults if the app's behaviour changes.

Result shapes stay free: the MCP server may add fields (`ok`, a route's `name`) that the tab does not need.

#### P3. Fidelity indicator

The app shows next to each Flow Step, and next to the XSLT / Groovy runtime selector, how well its
behaviour is backed, derived from `docs/fidelity.md` so there is one source.

1. **Make `fidelity.md` machine-readable.** Each table gets two columns: *Applies to* (step ids as the
   app uses them: `xslt`, `xsd`, `xpath`, `groovy`, `mapping`, `contentModifier`, `filter`, `router`,
   `splitter`, `gather`, `converter`, `encoder`, `decoder`, `messageDigest`, `camelSimple`) and *Basis*,
   one of **Tenant** (seen on a tenant, with its F id), **Documented** (SAP's docs or Camel's / Saxon's
   behaviour, not seen on a tenant), **Assumption** and **Not supported** (`UnsupportedInSimulator`). A row
   that holds several statuses (Gather) takes its weakest. Existing prose stays as the *Status* text.
2. **Parse it once.** `app/src/lib/fidelity.ts` parses the tables from `fidelity.md` imported with Vite's
   `?raw` (the docs folder is added to `server.fs.allow`). A unit test checks every row has a valid
   *Basis* and every step id has rows. `mcp/tests.rs` checks the same for the MCP `fidelity` topic.
3. **Show it.** A small label per step tab in `FlowStepsView.svelte` and at the runtime selector in
   `App.svelte`: the weakest *Basis* of the step's rows. A click opens a `Dialog` with the rows (area,
   basis, status text, F/Q ids). Offline only: no link to GitHub, so the release-page-only open command
   stays as it is. Hard-coded hints that repeat fidelity (the Encoder / Decoder "assumption" hint) go.
4. **Keep it current.** `AGENTS.md`'s rule "update `docs/fidelity.md`" now changes the app as well; the
   Gather learning (Q-F1) and the encoder probe (Q-E1) move rows to **Tenant** when their results come.
5. **Docs and changelog**: user guide (*Flow Steps*, *Desktop app*), `status.md`, changelog line.

#### P4. Offline where-used and parameter overview

Read-only search over the iFlows under `iflows/`. It widens the scope from simulating steps to reading
iFlows, beyond the outline and diagram (W11); nothing connects to a tenant. Seen as tenant-side tools in
CPI Explorer and CPI Flow Visualizer; only the idea is taken.

1. **Evidence first.** Record as a new finding which property keys adapters (HTTPS, SOAP, SFTP, JMS, AS2,
   OData, Mail, ProcessDirect, IDoc) use for address, credential and queue, and the format of
   `parameters.propdef`, from public iFlow exports, as F62 did for encoders. Until a key is recorded,
   the search treats it like any other property. *Done: F69, S55.*
2. **One implementation in the engine.** A new RPC `iflow.index` in `engine/core` (`IflowIndex.java`)
   on both engine kinds, so the app and the MCP server share it. Per iFlow:
   * every element (adapter flows, participants, steps) with its property map, as `iflow.models` reads it,
     with typed references where known: script / XSLT / XSD / mapping file (as `iflow.steps` resolves
     them, including script collections), value mapping, credential alias, address, queue;
   * the parameters: `parameters.prop` values, `parameters.propdef` definitions, every `{{name}}` use
     (element, key), and the derived lists *unused* and *unresolved* (used without a value).
3. **Search.** `iflow.search {query, kind?}` matches values and keys case-insensitively across all
   iFlows; `kind` narrows to a typed reference (e.g. credential alias). Results carry iFlow, element id,
   element name, key and value.
4. **App: a *Where used* tab** in the iFlow area next to *Diagram*: a search field with kind filters and
   results grouped by iFlow; a click selects the element in the diagram (`selectIflowNode(…, true)`), a
   script or mapping opens in its tab. A *Parameters* view lists each iFlow's parameters with value, uses,
   *unused* and *unresolved* marked. The diagram's details aside gets a "Where else is this used?" action.
5. **MCP.** A tool `iflow_where_used` (`query`, `kind`, `iflow`; `parameters: true` lists the parameters
   instead, which keeps `project_info` short), from the same RPCs.
6. **Fixtures and tests.** Extend `engine/core/src/test/resources/iflow` (or add a second iFlow) with
   adapters that carry an address, a credential alias and a queue, shared references between two iFlows,
   a `parameters.propdef`, an unused and an unresolved parameter. Java tests for index and search, unit
   tests for the result grouping, a Rust test for the MCP tool, an e2e step in `app/e2e`.
7. **Docs and changelog**: user guide (*iFlow import*), `mcp.md`, `status.md`, `architecture.md`,
   changelog; this table moves from *planned* to the changelog when released.

#### Release

After P1–P4: `CHANGELOG.md` section 0.6.0 and the version, as for 0.5.0 (`docs/building.md`, *Releasing*);
manual checks for the new tab and the indicator in `manual-test.md`.

---

## 11. Testing strategy
- Unit and golden-file tests for every API method and step behaviour we implement.
- **Differential tests** against tenant probe results (§8.3) — the main fidelity measure.
- A corpus of real CPI scripts and XSLTs (yours, anonymised).
- Conformance of XSLT/Groovy themselves is not our job — Saxon and Groovy are the reference.

---

## 12. Risks

| Risk | Mitigation |
|---|---|
| CPI's runtime differs from SAP's documentation and published interfaces | Tenant probes (§8.3); assumptions are labelled in [fidelity](fidelity.md) |
| CPI updates Groovy/Saxon/Java/Camel | Re-run the fingerprint probes; engines per runtime (§8.1) |
| Groovy 2.4.21 behaves differently from CPI's 1.x runtime | Java and Camel follow a tenant's fingerprint (F48); behaviour probes for the rest |
| Saxon-HE vs. EE differences | Both HE profiles, diagnostics for EE-only features (§7.3) |
| Undocumented iFlow format | Read generically, checked against public iFlows (F36, F46) |

---

## 13. Decisions

Taken: the build-vs-reuse principle (§3), XSLT first, tenant probes later; the name CPI Workbench and
the Apache-2.0 licence in a public repository; the HE profiles instead of a Saxon-EE licence. With the
first probe run (2026-11-09, F48–F52) for 0.3.0: both Groovy engines on Camel 3.14.7 and Java 21 and
Camel 2 dropped (tenants on software 6.x / 5.x are not covered); the tenant's timezone, locale,
bindings and libraries, in the versions it reports (F53), as the engines' defaults. With the converter
and Message Mapping probes (F54–F61): converter output follows the observed examples (CSV line-end
bytes remain unverified), and mapping functions follow the observed per-context calls and marker texts.
Open: the questions still marked open in Appendix A (among them CSV line ends, where `ScriptLogger`
writes, SUPPRESS in a mapping function's input).

---

## Appendix A — Question checklist

| ID | Question | Status | How to close it |
|---|---|---|---|
| Q-G1 | Groovy versions per step | Answered (F1, F2) | — |
| Q-G2 | Java version per step | Answered: Java 21 for 1.x and 2.x (F48) | — |
| Q-G3 | Camel version per step | Answered: 3.14.7 for 1.x and 2.x (F48) | — |
| Q-G4 | v2 API method list | Answered (F29, F49) | — |
| Q-G5 | `getBody(Class)` conversions for String/byte[]/InputStream/Document bodies | Inferred (Camel) | Probe iFlow |
| Q-G6 | Which bindings are in the script context (`messageLogFactory`, others?) and where does `ScriptLogger` write? | Bindings answered (F49, F53, F58); log destination open | Call `logInfo` on a test tenant and inspect the logs |
| Q-X1 | Saxon edition/version | Answered: EE 9.9.1.6 (F51) | — |
| Q-X2 | `xsl:param` precedence when header and property share a name | Answered: the header (F51) | — |
| Q-X3 | Type of bound parameter values (string vs. typed) | Answered: typed (F51) | — |
| Q-X4 | Behaviour for a declared `xsl:param` without matching header/property (F12) | Answered: the default (F51) | — |
| Q-X5 | `setHeader`/`setProperty` signatures and return value | Answered: the empty sequence (F51) | — |
| Q-X6 | Source/target from header/property in newer step versions | Open | SAP docs + probe |
| Q-V1 | XSD engine and version behind the XML Validator step | Open | Probe iFlow (error message format) |
| Q-T1 | Format of exported MPL trace content | Open (F18) | Export one sample |
| Q-T2 | How CPI Helper's GroovyDebugX and xsltDebugX plugins hand a traced step's payload, headers and properties to an external IDE (URL, clipboard, file; the encoding) | Answered from the plugins' sources (S47): a link, `<url><data>` or `<url>#share/<data>`, the data being JSON, raw DEFLATE, URL-safe Base64; a *Custom URL* can be set. Tried on a tenant on 2026-11-16 (GroovyDebugX and xsltDebugX, pasted and through the receiver). | — |
| Q-T3 | Where a large traced body is cut off: tenant trace or CPI Helper's fetch? | Open. On 2026-11-16 an xsltDebugX XML body ended at line 671,720; CPI Helper's trace view was cut off too. The valid compressed JSON already contained the cut body, and the same link transferred 25 MB through the receiver. Plugin files have no truncation/length check (S47). | Compare a downloaded tenant trace with CPI Helper's fetched and imported body |
| Q-M1 | Message Mapping scripts: Groovy and Java version, whether the signature alone picks the execution type (single value or context, F44), how the per-context results are joined, the marker value of SUPPRESS, whether a single-value function is called once per value | Partial: versions, one call per context, the join by context changes, single-value calls once per value and the marker texts answered (F59–F61, an empty context is called with an empty array); open: SUPPRESS in a function's input, identity or equals for the markers | `probe-mapping-v1.groovy` with the `Call` target (probes/README.md, step 6) |
| Q-C1 | Converters: compact or indented output, the XML declaration, CSV line ends and quoting, an unmapped JSON prefix without namespace mapping (F47), the XML to JSON namespace table's cell order | Answered for the probes' cases (F54–F57); open: the XML to JSON namespace table's cell order, `\r\n` or `\n` in CSV | Save a response with `curl --output` |
| Q-F1 | Gather's Combine with a body that is not XML (JSON or plain text after a script): failure, the text as it is (as character data in the wrapper, malformed only where the text is, e.g. `a & b`), or another form. SAP documents XML only and shows no such case | Open; the simulator keeps the text and warns | `probes/gather/` (probes/README.md, step 8) |
| Q-P1 | Partner Directory / Keystore / Secure Store edge cases: missing parameter, unknown partner, unknown keystore alias (null or exception, which message) | Answered (F52) | — |
| Q-E1 | Encoder, Decoder and Message Digest, what F62 and F63 leave open. Base64: whether long output is wrapped (and with which line end), what the decoder does with line breaks and with invalid input. GZIP: the header fields (time, name), so whether output bytes can be compared at all. ZIP: the entry's name (`CamelFileName`, the message id?), and a decompress of an archive with several entries. Digest: what *None* hashes (the body's bytes as they are?), a body that is not XML, a filter that selects several nodes or none, the header's type; the iFlow keys of the filter and of the GZIP schemes | Open (§10.5 W4); the settings and the digest's header are documented (F63); the simulator follows Camel's data formats (F64). Probe prepared, not run | `probes/encoders/` (step 9 of the probes' README); the probe iFlow's export for the keys |
| Q-VM1 | Value Mapping export format and lookup precedence for repeated values | Partial: format observed in one Value Mapping and one package export (F65, F66). Default precedence follows S52; a repeated value without a default remains unverified (the import takes the first group). | Probe `getMappedValue` with repeated values, with and without defaults |
| Q-D1 | Whether an IDE's breakpoints bind to a script that an engine jar runs under the JDWP agent | Partly answered (F67): jdb binds by class name on both jars; IntelliJ and VS Code not tried | Try it with IntelliJ and VS Code on `cpiwb-groovy-v1.jar run` |

## Sources

- S1 SAP Help — Define a Local Script Step: https://help.sap.com/docs/r/368c481cd6954bdfa5d0435479fd4eaf/Cloud/en-US/03b32eb2c5c249f0a59bcd27c44d1e4e.html
- S2 SAP Community — Testing Cloud Integration Groovy 4: https://community.sap.com/t5/integration-forum/testing-cloud-integration-groovy-4/m-p/14317619 ; Equalise IT — Upgrade to Groovy 4.0.x: https://equaliseit.com/upgrade-to-groovy-4-0-x-in-sap-cloud-integration/
- S3 SAP Community — Upgrade to script version 2.x: https://community.sap.com/t5/technology-blog-posts-by-members/how-to-fix-groovy-script-warnings-and-upgrade-to-script-version-2-x-in-sap/ba-p/14351691
- S4 SAP Community — Testing Groovy scripts locally (IntelliJ): https://blogs.sap.com/2022/03/03/testing-groovy-script-locally-using-intellij-idea/
- S5 SAP Community — Groovy script debugging in Eclipse (2024): https://answers.sap.com/t5/technology-blogs-by-members/groovy-script-debugging-in-eclipse-2024/ba-p/13582941
- S6 SAP Community — How do you test your Groovy scripts: https://community.sap.com/t5/technology-blogs-by-members/how-do-you-test-your-groovy-scripts/bc-p/13327543
- S7 SAP Community — XSLT Mapping enriched with XSLT 3.0: https://community.sap.com/t5/technology-blog-posts-by-sap/cloud-integration-xslt-mapping-is-enriched-with-xslt-3-0-specification/ba-p/13415753
- S8 SAP Community — Peeking under the hood of CPI's XSLT processor: https://community.sap.com/t5/technology-blog-posts-by-members/peeking-under-the-hood-of-cpi-s-xslt-processor/ba-p/13322917
- S9 SAP Community — Migrating XSLT mappings from PO to Integration Suite: https://community.sap.com/t5/integration-blog-posts/migrating-xslt-mappings-from-sap-process-orchestration-to-sap-integration/ba-p/14284726
- S10 SAP sample documentation (XSLT headers/properties): https://api.sap.com/odata/1.0/catalog.svc/Files('6e6e727f472741ce9a172adc6df81a68')/$value ; SAP Q&A: https://answers.sap.com/questions/12688623/cpi-xslt-using-of-header-variable-in-xslt.html ; SAP KBA 3095160: https://userapps.support.sap.com/sap/support/knowledge/en/3095160
- S11 Saxon-HE on Maven Central: https://central.sonatype.com/artifact/net.sf.saxon/Saxon-HE ; Saxonica licence conditions: https://www.saxonica.com/html/documentation9.8/conditions
- S12 Saxon 10 changes (HOF and xsl:evaluate in HE): https://saxonica.com/html/documentation12/changes/v10/xpath31.html ; BaseX list, Saxon EE 9.9 HOF error: https://mailman.uni-konstanz.de/mailman3/hyperkitty/list/basex-talk@mailman.uni-konstanz.de/message/V6MYV3KUN6LGC5ZPZVKGN7HAKUJC5WHI/
- S13 PTC Windchill — Saxon-HE full-interface extension functions: https://support.ptc.com/help/windchill/r13.1.2.0/en/Windchill_Help_Center/customization/WCCG_BusLogicCust_XSLT_SaxonJar.html
- S14 Saxonica forum — Native compilation with GraalVM (HE 12.4): https://saxonica.plan.io/boards/2/topics/9627
- S15 SaxonC product information: https://www.saxonica.com/html/saxon-c/index.html
- S16 Camel Quarkus Groovy extension: https://camel.apache.org/camel-quarkus/reference/extensions/groovy.html
- S17 Spring Boot with GraalVM (Groovy not supported): https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-with-GraalVM/Spring-Boot-Config-Data-Migration-Guide
- S18 SAP blog — Tracing of exchange properties: https://blogs.sap.com/2018/08/23/cloud-integration-enabling-tracing-of-exchange-properties-in-the-message-processing-log-viewer/
- S19 GrooveBox: https://grooveboxide.com/guides ; LinkedIn description: https://it.linkedin.com/in/gabrielbenolli
- S20 Contiva Groovy IDE: https://ide.contiva.com/
- S21 go-xslt: https://github.com/tim-riep/go-xslt
- S22 REA workflow skill: https://github.com/morluto/rea/blob/main/.agents/skills/reverse-engineer-anything/SKILL.md
- S23 SAP Script API jars on Maven Central: https://repo1.maven.org/maven2/com/sap/cloud/script/
- S24 SAP generic API (platform services) on Maven Central, with Javadoc jar: https://repo1.maven.org/maven2/com/sap/cloud/adk/generic.api/
- S25 SAP-docs Integration Suite documentation (GitHub mirror of help.sap.com): *Using Camel Simple Expression Language*, *Define Router*, *Define Looping Process Call*, *Define Content Modifier*, *Understanding Software Version Numbers*, *Patch Releases for Cloud Integration*: https://github.com/SAP-docs/btp-integration-suite/tree/main/docs/ci
- S26 SAP's sample iFlows (Apache-2.0), read for the model format only, not copied: https://github.com/SAP-samples/cloud-integration-flow
- S27 SAP Cloud Integration API Javadoc (`com.sap.it.public.api` 2.12.0; `com.sap.it.api.mapping`, `com.sap.it.api.msg`), read on 2026-11-07: https://help.sap.com/doc/d47441d304c14a0ab9d3986c1b553a1e/Cloud/en-US/index-all.html
- S28 SAP NetWeaver PI 7.31 — Advanced User-Defined Functions; Implementing Java Functions: https://help.sap.com/doc/saphelp_nw73ehp1/7.31.19/en-US/4b/fbd21666d33de4e10000000a42189e/content.htm ; https://help.sap.com/doc/saphelp_nw73ehp1/7.31.19/en-US/4b/fbd6bb66d33de4e10000000a42189e/content.htm
- S29 Community guides on Groovy mapping functions in CPI, read on 2026-11-07: https://sappipolearnings.blogspot.com/2022/02/udfs-groovy-script-in-message-mapping.html ; https://totoro.hashnode.dev/step-by-step-guide-using-groovy-scripts-in-sap-cpi-mappings ; examples (`addValue` of a BigDecimal sum, functions without `MappingContext`): https://sapcpidesign.blogspot.com/2021/08/sap-cpi-message-mapping-groovy-script.html . The SAP Community threads they cite (e.g. "SAP CPI UDF Execution type All values of Queue") sit behind a bot check and were not read.
- S30 SAP-docs Integration Suite documentation (S25 mirror): *Define JSON to XML Converter*, *Define XML to JSON Converter*, *Conversion Rules* and *Limitations* for both directions, *Example: Transformations … with and without Namespace Mapping* (both directions), *Example: Transformation to a JSON Message without Root Element Tag*, *How Streaming in the XML-to-JSON Converter Works*, *Configure CSV to XML Converter*, *Configure XML to CSV Converter*, *Use the CSV to XML Converter*, *Use the XML to CSV Converter*
- S31 Public iFlows on GitHub with converter steps (code search `JsonToXmlConverter`, `XmlToJsonConverter`, `CsvToXmlConverter`, `XmlToCsvConverter` in `.iflw` files, 2026-11-07), read for property names only
- S32 The probes in `probes/` run on one Cloud Integration tenant on 2026-11-09; the responses are in `probes/results-tenant/2026-11-09/`. One tenant and one date: other tenants or later releases may differ.
- S33 Groovy CPI Studio (VS Code Marketplace), read on 2026-11-13, as are S34–S46: https://marketplace.visualstudio.com/items?itemName=leopavanelli.groovy-cpi-studio
- S34 SAP CPI Groovy Script (VS Code Marketplace): https://marketplace.visualstudio.com/items?itemName=JohanCalderon.sap-cpi-groovy-script ; its author on JDI debugging (page not reachable, from search results): https://community.sap.com/t5/technology-blog-posts-by-members/how-to-debug-sap-cpi-groovy-scripts-locally-using-visual-studio-code/ba-p/14173680
- S35 XSLTDebugX (AGPL-3.0): https://github.com/linusdevx/XSLTDebugX
- S36 Contiva Groovy IDE: https://ide.contiva.com/de
- S37 GrooveBox: https://grooveboxide.com/
- S38 All-in-one Tools for CPI: https://github.com/Pavan-29/All-in-one-tools-for-CPI
- S39 SAP Help — Simulation of Integration Flow; Configure Simulation (pages not readable by the tool used, limits from search results): https://help.sap.com/docs/integration-suite/sap-integration-suite/simulation-of-integration-flow ; https://help.sap.com/docs/cloud-integration/sap-cloud-integration/configure-simulation
- S40 Figaf Testing Tool: https://figaf.com/figaf-testing-tool/
- S41 CPI Developer Studio: https://cpistudio.nxgenz.com/
- S42 CPI Helper (GPL-3.0): https://github.com/dbeck121/CPI-Helper-Chrome-Extension
- S43 cpictl: https://github.com/cpars-innovation/cpicli
- S44 cpi-saper-dev-mcp: https://github.com/devstylator/cpi-saper-dev-mcp ; https://community.sap.com/t5/technology-blog-posts-by-members/sap-cloud-integration-an-mcp-server-that-lets-ai-agents-build-and-test/ba-p/14516391
- S45 sap-cpi-mocks (Equalise IT), from search results: https://equaliseit.com/test-value-mapping-in-cpi-groovy-scripts/
- S46 CPILint: https://github.com/mwittrock/cpilint
- S47 CPI Helper's plugins `plugins/groovyDebugger.js` and `plugins/xsltDebugX.js` (GPL-3.0), read on 2026-11-13 for the format of the link they open and its settings, and again on 2026-11-16 for
  cutting and length checks (none), how the link is opened (`window.open`) and the Custom URL check (only that it
  parses as a URL); no code taken: https://github.com/dbeck121/CPI-Helper-Chrome-Extension/tree/master/plugins
- S48 Receiver transport constraints: Microsoft `CreateProcessW` commandline limit (32,767 characters including
  the terminator): https://learn.microsoft.com/en-us/windows/win32/api/processthreadsapi/nf-processthreadsapi-createprocessw ;
  Chromium Mojo URL limit (`kMaxURLChars = 2097152`): https://chromium.googlesource.com/chromium/src/+/HEAD/url/mojom/url.mojom ;
  top-level Local Network Access navigation discussion (not a compatibility guarantee): https://github.com/WICG/local-network-access/issues/48
- S49 SAP-docs Integration Suite documentation (S25 mirror, commit 147acb7 of 2026-10-22), read on 2026-11-16: *Define an Encoder*, *Define a Decoder*, *Define Message Digest*, *Simple Integration Flow Using a Message Digest Step*, *Use the Base64 Encoder*, *Use the Base64 Decoder*. help.sap.com renders these pages with scripts and could not be read directly.
- S50 Public iFlows on GitHub with Encoder, Decoder and Message Digest steps (code search `encoderType` and `MessageDigest canonicalizationMethod` in `.iflw` files, 2026-11-16), read for property names only: `alee536/CPI-iFlows` (Encoder and Decoder), `stevedpedersen/cpilint` (IF_LINT_NAMES), `nesun3/sap-cloud-integration-artifacts` (Zip Spliting).
- S51 Apache Camel 3.14.7 source, read on 2026-11-16 for the defaults of the data formats behind Base64, GZIP and ZIP: `components/camel-base64` (`Base64DataFormat`), `components/camel-zip-deflater` (`GzipDeflaterDataFormat`), `components/camel-zipfile` (`ZipFileDataFormat`); no code taken: https://github.com/apache/camel/tree/camel-3.14.7/components
- S52 SAP Help, *Integration Suite*, mirrored in SAP-docs/btp-integration-suite (commit 147acb7, 2026-10-22), read on 2026-11-16: `editing-value-mapping-6c8847f.md` (a value mapping is a bi-directional lookup table; when target values repeat for different source values, defaults are generated and can be changed in the *Default Values* tab).
- S53 Public `.iflw` files read on 2026-11-16 for their structure: SAP/apibusinesshub-integration-recipes (the
  recipes *Pattern IDoc Recipient – Dynamic Routing of IDoc*, *Pagination with SFSF* and the openSAP BTP week 4
  solution, each a package export), alee536/CPI-iFlows, nesun3/sap-cloud-integration-artifacts.
- S54 Tools that draw or describe iFlows, read on 2026-11-16: cpi-saper-dev-mcp's `iflow_render` (Apache-2.0,
  commit 3e47ee8: Mermaid from the model, the diagram's coordinates not used); codebude/iflow-plotter (MIT, 2021:
  the iFlow's own diagram through bpmn-js, whose licence keeps the bpmn.io mark visible); the *iFlow Documenter*
  of CPI Developer Studio (commercial: an ordered step list in Markdown). No SAP specification of the format was
  found.
- S55 Public iFlow exports read on 2026-11-17 for adapter property keys and the parameter files only: the 384
  `.iflw` files of SAP/apibusinesshub-integration-recipes and 59 from community repositories on GitHub (among
  them alee536/CPI-iFlows and nesun3/sap-cloud-integration-artifacts), with their `parameters.prop` and
  `parameters.propdef`. No code taken.
