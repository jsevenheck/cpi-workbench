# CPI Workbench — Project Plan

The design, the research ledger (findings F…, sources S…, open questions Q…) and the roadmap. What is
built and how complete it is: [status](status.md). What matches CPI: [fidelity](fidelity.md).

---

## 1. The core idea

Testing a Groovy script or an XSLT mapping in SAP Integration Suite today means: change → deploy → wait → trigger → enable trace → open the monitor → read the payload → repeat. Each round takes minutes.

CPI Workbench removes that loop. It is a lean desktop app (Windows, macOS, Linux) where you:

1. enter an **input message** — body, headers, exchange properties,
2. pick a **Groovy script** (1.x or 2.x), an **XSLT mapping** or a **flow step**,
3. press **Run** (or let it re-run on every change),
4. see the **output message** — body, headers, properties, MPL entries, logs and errors — immediately.

The test must behave **as close as possible to the real CPI step**: same Groovy version, same Saxon release line, same parameter binding, same API surface, same type conversions. That fidelity is the reason to build this instead of using a generic Groovy console or XSLT tester.

Everything runs **offline** on the local machine.

### In scope
- Groovy Script step, **Script API v1** (step 1.x, Groovy 2.4.21) and **Script API v2** (step 2.x, Groovy 4.0.29)
- XSLT Mapping step (XSLT 1.0 / 2.0 / 3.0)
- XML Validator-like **XSD validation**, an **XPath tester**
- Single **flow steps**: Content Modifier, Filter, Router, Splitter, Gather, Converter (§10)
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
| F6 | v2 API in package `com.sap.it.script.v2.api` (`Message`, `Exchange`); full method list | Interface **Observed** (F29); runtime class Unknown | S2, S3, S23 |
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
| F20 | Saxon-HE (9.9 and 12) only has English names for `format-date` (`'de'` → `[Language: en]5. March 2026`). CPI's Saxon-EE is expected to localise. | Observed (simulator) / Inferred (CPI) | `probes/baseline-simulator/fingerprint.saxon-he-9.9.xml` |
| F21 | Passing headers/properties as Java objects through JAXP `setParameter` makes Saxon convert them itself: `Integer` → `xs:integer`, `Boolean` → `xs:boolean`, `Date` → `xs:dateTime`, `byte[]` → sequence of integers. | Observed (simulator) | `probes/baseline-simulator/probe-xslt-behaviour.saxon-he-9.9.xml` |
| F22 | Header-over-property precedence is modelled on Camel's XsltBuilder (properties set first, headers override). A tenant gives the header precedence too (F51). | Inferred, confirmed on one tenant | `XsltStep.java`, S32 |
| F23 | Saxon instantiates built-in functions reflectively; native builds need reflection metadata for all of them (generated by `engine/native/GenReflect.java`), plus the JDK XPath 1.0 function classes. | Observed | native build failures fixed in this phase |
| F24 | Groovy 2.4.21 runs the Script API v1 corpus on Java 17 and 21 without changes (7 community scripts). | Observed (simulator) | `GroovyCorpusTest` |
| F25 | `XmlUtil.serialize` / XSLT pretty-printing differs between JDK versions (indentation), so the Java version behind each CPI step matters for byte-exact comparisons. | Observed (corpus expected files vs. JDK 17/21 output) | `GroovyCorpusTest` |
| F26 | A classic 1.x → 2.x break is reproduced: scripts using `XmlParser`/`XmlSlurper` without import (default `groovy.util` package) fail on Groovy 4. | Observed (simulator) | corpus `edi-segment-counter` |
| F27 | Partner Directory, Keystore, Secure Store and number ranges: SAP's Javadoc (`generic.api`, F33) documents null for a missing Partner Directory parameter, partner or certificate parameter; `IllegalArgumentException` for null/empty IDs and for types other than `String`/`BinaryData`; null for an unknown keystore alias or the wrong entry kind (`IllegalStateException` when a key cannot be recovered); `SecureStoreException` for an unknown credential. The simulator follows this. A tenant matches it for names that do not exist; its messages are in F52. | Observed (Javadoc, one tenant) | S24, S32 |
| F28 | CPI has no public script API for the Data Store; scripts that access it use internal classes looked up from the Camel registry. The simulator does not mock this. | Inferred (community examples) | — |
| F29 | SAP publishes its Script API interfaces on Maven Central (`com.sap.cloud.script:script.api` 2.14.0–2.16.4 for v1, `script.v2.api` 4.0.0–4.2.2 for v2; SAP Developer License, not bundled). The simulator's `Message`, `AttachmentWrapper`, `SoapHeader` and the v2 `Attachment`, extension-function and exception types follow these signatures. The v2 `Message` has no `getSoapHeaders`/`setSoapHeaders` and no `AttachmentWrapper` methods, but adds `getHeader(String)`, `getHeader(String, Object)`, `clearAttachments`, `resetBodyStream` and `getExtensionFunctions` (exchange timestamps, ProcessDirect). The interface is a lower bound: CPI's implementation class may offer more, which the fingerprint probes list (`messageMethods`). | Observed (published jars) / Unknown (runtime class) → Q-G4 | Maven Central `com.sap.cloud.script` |
| F30 | The v1 API jar moved from Camel 2.24 (`script.api` 2.15.0, May 2024: imports `org.apache.camel` [2.24,3), `AttachmentWrapper` extends `org.apache.camel.impl.DefaultAttachment`) to Camel 3.14 (2.16.2, Dec 2025, and 2.16.4: [3.14,4), `org.apache.camel.attachment.DefaultAttachment`). So 1.x steps may now run on Camel 3.14, which the fingerprint confirmed (F48); since 0.3.0 the simulator's 1.x engine does too, and its `AttachmentWrapper` extends Camel 3's `DefaultAttachment`. The simulator's v1 Message has the overloads that take Camel's `Attachment` type, as a tenant's does (F49). | Observed (jar manifests, one tenant) | Maven Central `com.sap.cloud.script:script.api` |
| F31 | Which libraries CPI puts on a script's class path besides Groovy and Camel (e.g. commons-codec, IAIK used by SAP's own sample scripts) is not known; the simulator offers none and loads jars from `lib/` and the iFlow's `src/main/resources/lib/`. | Answered by the fingerprints (F50, F53): the simulator bundles the libraries and versions a tenant reports | SAP sample iFlows |
| F32 | SAP's sample XSLTs call Java static methods through `java:` namespaces (e.g. `java:java.util.Date`), so CPI's Saxon-EE allows reflexive extension functions. The simulator registers them as integrated functions for JDK classes (native engines: a fixed list of JDK classes). Classes from the iFlow's or project's jars are not available to XSLT (the native engines cannot load classes at runtime). Exact argument conversion rules of Saxon-EE are not reproduced in all cases. | Observed (SAP samples) / Inferred (conversion) | SAP sample iFlows, `JavaExtensions.java` |
| F33 | SAP also publishes the platform API interfaces as `com.sap.cloud.adk:generic.api` (4.10.2, with Javadoc; SAP Developer License, not bundled): `ITApiFactory`, `ITApiException`/`InvalidContextException`, Keystore (incl. key and trust managers), message log, number ranges (`NumberRangeConfigurationService.getNextValuefromNumberRange(name, correlationId)`), Partner Directory (incl. `getCertificateParameter(parameterId, partnerId)`, authorized users, deprecated LIKE-pattern lookups) and Secure Store (incl. OAuth2 tokens: `getAccesTokenForOauth2ClientCredential`, SAP's spelling). `ValueMappingApi` is not in it. The simulator's classes follow these signatures. | Observed (published jar + Javadoc) | S24 |
| F34 | The Camel line is a property of the tenant: SAP numbers Cloud Integration software 8.x (Cloud Foundry) and 7.x (Neo) for runtimes on Camel 3, 6.x and 5.x for Camel 2. Patch notes from 2024 to 2026 describe the migration of tenants to Camel 3 and back-ports to the Camel 3.14.7 branch. Whether any tenant still runs Camel 2 is not stated. | Observed (SAP docs) | S25 |
| F35 | SAP's Router table uses `=`, `and`, `or` and `${property.x}`, and its docs never use `${exchangeProperty.x}`. Plain Camel has no `=` operator ("Unexpected token =" on 2.24 and 3.14), and Camel 3.14 rejects `and`, `or` (only `&&`, `\|\|`) and `property`. SAP's patch notes (August 2024) confirm that CPI converts `property` to `exchangeProperty`; how it handles `=`, `and` and `or` is not documented. | Observed (SAP docs, simulator) / Inferred (`=`, `and`, `or`) | S25, `SimpleStepTest` |
| F36 | In exported iFlows a Content Modifier is `activityType=Enricher` with `headerTable` / `propertyTable` (rows of `<cell id='Action\|Type\|Value\|Default\|Name\|Datatype'>`), `bodyType` and `wrapContent`. Source types: constant, header, property, xpath, expression, persisted variables, global persisted variables, numberRange, external parameter. Delete rows name `$name=X` or `$include=…,$exclude=…`. A Router is an `exclusiveGateway` (`throwException`, `default` flow) whose outgoing flows carry `expressionType` XML or NonXML and a `conditionExpression`. A splitter has `splitType` GeneralSplitter or IteratingSplitter, `exprType` XPath, Line Break or Token, `splitExprValue` or `tokenValue`, `grouping`, `Streaming`. Values may hold `{{parameters}}` from `parameters.prop`. | Observed (1,570 Content Modifiers, 629 Routers, 77 splitters in 389 exported iFlows) | S26 |
| F37 | Router XML conditions in real iFlows use XPath 2.0+ syntax (`/node()/*:IDOC[1][1=last()]`, `/a/count(b) = 0`), so CPI's Router does not evaluate them as XPath 1.0. | Observed | S26 |
| F38 | Content Modifier versions up to 1.3 have no `bodyType`, and their bodies hold Camel Simple (`${header.x}`, `${exception.message}`; 39 such steps, 19 with `${…}`), as in SAP's sample "Externalizing Additional Parameter" (1.2), which shows header values in the body: these bodies are expressions. From 1.4 the type is stored; 1.6 mostly stores `constant` for an empty body. In the same samples, 1,565 of 1,568 Content Modifiers and 623 of 625 Non-XML Router conditions run on the simulator's Camel; the rest fail on input values, a misspelt class (`java.Lang.Integer`) or three-level nested `${…}` that Camel's parser rejects. | Observed | S26 |
| F39 | Filter steps store `xpathType` (String, Integer, Boolean, Node, Nodelist) and the XPath in `wrapContent`; Gather steps store `messageType` (SameXMLFormat, DiffXMLFormat, PlainText), `aggregationAlgorithm` (sap-identical-multi-mapping, sap-pi-multi-mapping, xpath-merge-strategy, concatenate-strategy), `sourceXPath` and `targetXPath` (64 Filters, 55 Gathers in the samples). Of the 64 Filter XPaths, 60 compile on Saxon without variables; the other 4, and one Router XML condition, use `$name` for a header or property. Camel's XPath variable resolver (`MessageVariableResolver`, Camel 2.24.3 and 3.14.7, read from the Apache jars) resolves an unprefixed `$name` to the header, else the exchange property. | Observed | S26 |
| F40 | SAP's Javadoc of the Cloud Integration API (`com.sap.it.public.api` 2.12.0) documents `com.sap.it.api.mapping`: `MappingContext` extends `com.sap.it.api.msg.ExchangePropertyProvider` with `getHeader(String)` / `setHeader(String, Object)` and `getProperty(String)` / `setProperty(String, Object)` (null for a missing name; set creates or overwrites). `Output` is "used in advanced user-defined functions (execution type 'All values of Context' or 'All values of a Queue') to return the result": `addValue(Object)`, `addContextChange()`, `addSuppress()` (the target field and its sub nodes are not generated), `addXSINIL()`, `clear()`, `isContextChange(Object)` (to find context changes in an input array), `isSuppress(Object)`, `isXSINIL(Object)`. None of these is in SAP's jars on Maven Central (`script.api` 2.16.4, `script.v2.api` 4.2.2, `generic.api` 4.10.2). The simulator follows these signatures. | Observed (Javadoc) | S27 |
| F41 | SAP's patch notes (software increment 2602 and later: 6.77.47, 7.50.10, 6.78.56, 7.51.11) say: "Filtered out Groovy Script Version 2 artifacts from Message Mappings, as this version is currently not supported in Message Mapping scenarios." So mapping scripts run on the 1.x side; which Groovy and Java version they get is not stated. | Observed (SAP docs) / Unknown (versions) → Q-M1 | S25 |
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
| **XSD validator step**, **XPath tester** and **flow steps** | CPI-style configuration and error reporting; Content Modifier, Filter, Router, Splitter, Gather and Converter (§10) |
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
| JDK (Eclipse Temurin) | 17 | GPLv2 + Classpath Exception | CPI runtime (F3) |
| Tauri, Monaco editor | current | MIT / Apache-2.0 | App shell and code editor |

All of them are long-lived projects backed by foundations or companies (Apache, Saxonica, Eclipse, the Tauri team, Microsoft) — not one-person projects.

### 3.4 What we deliberately don't do
- **Use go-xslt (or similar) as a component.** It is a reference for features and UX only (a desktop XSLT tester with live results, error markers, project folders). Fidelity-wise it would be a step away from CPI, because CPI runs Saxon, and it is a pre-1.0 one-person project.
- **Write our own XSLT 3.0 / XPath 3.1 / XSD engine.** This is years of work (the W3C test suites alone have ~127,000 cases) and would still behave differently from Saxon in implementation-defined details (serialization defaults, `format-number`/`format-dateTime` output, regex and collation edge cases, error codes). It would work against the core goal. If a reason appears later (e.g. a browser version), this can be re-evaluated.
- **Write our own Groovy implementation.** Not feasible (F17), and scripts call the Java class library directly.

---

## 4. Method: how REA is applied

*Reverse Engineer Anything* (REA, `morluto/rea`) is a workflow for studying the behaviour of a closed target and rebuilding it clean-room. Its tools (MCP server, disassemblers) are made for shipped binaries and are not installed here. Rebuilding an **open-source** project like go-xslt with it would make no sense: its source code is public, and copying it would not be our own work.

The right target for REA's method is **CPI itself**, observed as a black box:

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
- **XSLT/XSD/XPath as a native binary** (GraalVM Native Image): Saxon is pure Java and has been compiled natively before (F16). Result: fast start, low memory, no JVM for XML work. If native compilation of 9.9 hits a blocker, the same code runs on the bundled JRE (fallback, slower start).
- **Groovy on a JVM**: unavoidable (F17). v1 and v2 run as separate worker processes so Groovy 2 and 4 never mix, and a runaway script only kills its own worker.
- **Shell in Rust (Tauri 2)**: small native binary, uses the operating system's webview (no bundled browser), good installer and update tooling. The UI needs a real code editor, which the webview + Monaco provides; a pure-Rust UI toolkit (egui, as in ArtCraft) has no comparable editor.

### 5.3 Footprint and performance

The original targets. As built: one all-in-one installer (no separate Groovy pack), messages go through
JSON-RPC (no temp files), the heap is fixed at 512 MB, and the engines time out after 30 s. Measured numbers
are in [architecture](architecture.md#measured-performance).

**Lean by default**
- **Modular delivery**: the base app contains shell, UI and the native XML engine. The **Groovy pack** (JRE + Groovy + Camel + our API) is downloaded on first Groovy use, or comes with an **all-in-one offline installer** for machines without internet access.
- **Per-user installation without admin rights** (Windows NSIS current-user install; portable ZIP).
- **jlink-trimmed JRE**: only the JDK modules Groovy and Camel need (determined with `jdeps`), debug info stripped, compressed. One shared JRE for v1 and v2 if calibration shows both use the same Java version.

**Fast**
- XML engine starts in milliseconds (native); no JVM for XSLT-only users.
- JVM starts only on the first Groovy run, then stays **warm**; **idle shutdown** after 10 min (configurable) frees memory.
- **AppCDS** class-data archive for faster JVM starts.
- **Compiled-script and compiled-stylesheet cache** keyed by content hash and profile: changing only the input skips compilation.
- Low-footprint JVM flags (`-XX:+UseSerialGC`, 512 MB max heap by default, configurable per project).
- Large messages (above ~1 MB) are passed as temp files, not through JSON-RPC, and shown in a virtualized viewer.

**Target budgets** — goals to verify in the spikes, not measurements:

| Metric | Target |
|---|---|
| Base installer (incl. native XML engine) | ≤ 60 MB |
| Groovy pack download | ≤ 70 MB |
| App start to usable UI | ≤ 1 s |
| Memory, no Groovy worker running | ≤ 200 MB |
| XSLT run, 1 MB input, typical mapping, cached stylesheet | ≤ 100 ms |
| First Groovy run (cold JVM, with AppCDS) | ≤ 2 s |
| Groovy re-run (warm worker, cached script) | ≤ 100 ms |

---

## 6. Groovy Script Simulator

### 6.1 Input
- **Body**: XML / JSON / text (from a file or typed), as `String`, `InputStream` or `byte[]`; pretty-print.
- **Headers** and **exchange properties**: tables with name, value and **Java type** (`String`, `Integer`, `Long`, `Boolean`, `byte[]`, `Date`, `null`, …) — wrong types are a common cause of CPI failures.
- **Attachments** and **SOAP headers**: in test cases (`case.json`).
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

**Platform APIs** (`ITApiFactory`): mocks fed from `platform/` files in the project (credentials, value mappings, partner directory, keystore, number ranges; [test cases](test-cases.md)). Other services raise `InvalidContextException`, as SAP's Javadoc says (F27).

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
Plain, git-friendly files. The engines' `test` command (`cpiwb-xml test tests/`, `java -jar cpiwb-groovy-v1.jar test tests/`) runs the cases and exits non-zero on failure. Format: [test cases](test-cases.md). Open: a test case from an **exported MPL trace** (export format unknown, F18) and test cases for flow steps.

---

## 10. Roadmap

| Phase | Scope | Status |
|---|---|---|
| **0 — Spikes** | Saxon-HE 9.9 as GraalVM native image; CPI parameter binding and `setHeader`/`setProperty` as integrated extension functions; Tauri ⇄ engine JSON-RPC; budgets (§5.3) | Done |
| **1 — Probe kit** | Fingerprint and probe iFlows for the questions in Appendix A | Ran on one tenant on 2026-11-09 (F48–F61); results in `probes/results-tenant/` |
| **2 — XSLT MVP** | XSLT with live mode, CPI binding, both Saxon profiles, XSD validation, XPath tester, test cases, CLI | Done (0.1.0) |
| **3 — Groovy MVP** | Groovy 1.x and 2.x, typed headers/properties, MPL, API call log, console, diff | Done (0.1.0) |
| **4 — Productivity** | Platform API mocks, lint, runtime comparison, iFlow-zip import, flow steps, MCP server | Done (0.1.0–0.3.0) |
| **5 — Distribution** | Installers for Windows, macOS, Linux; code signing, auto-update (§10.2) | Unsigned installers and a manual update check; signing and auto-update open |
| **6 — Workflow** | What a review of other tools showed (§10.5): platform data in the Groovy tab, a traced message from CPI Helper, XPath comfort, Encoder / Decoder and Message Digest steps, JUnit output and compare rules for test cases, Value Mapping import, run history, examples, debugging | Planned (2026-11-13); W1 to W3 built (W2 without the desktop link) |

### 10.1 MCP server for coding agents (built, 2026-11-01)

`cpi-workbench mcp` ([MCP server](mcp.md)) lets a coding agent run mappings, scripts, flow steps and
test cases on the simulator through the [Model Context Protocol](https://modelcontextprotocol.io).
Decisions:

* **One server in front of all engines**, as a subcommand of the desktop executable: it reuses the
  shell's engine supervisor, the bundled JRE and native images, and the Windows junction for
  non-ASCII install folders, and the installers need no new file. On Windows the executable is a
  GUI-subsystem program; CI starts the release build with piped stdio on all three OSes
  (`app/scripts/mcp-smoke.mjs`), which answers the open question from the first assessment.
* **No new dependency:** the protocol is implemented on `serde_json` (`app/src-tauri/src/mcp/`). It is
  dual-era as the 2026-07-28 specification allows: stateless 2026-07-28 requests and the `initialize`
  handshake of 2025-11-25 back to 2024-11-05, which the official SDKs still negotiate (TypeScript
  1.32.2 and Python 2.3.0, tried on 2026-11-01).
* **The app leads; the server adds no function.** One tool per tab: `project_info`, `run_xslt`,
  `run_groovy`, `validate_xsd`, `evaluate_xpath`, `run_flow_step`, with the tabs' inputs. Left out on
  purpose because the app has no such tab: running test cases (CLI only), Camel Simple on its own,
  a lint tool (the lint comes with every `run_groovy` result).
* **File access for agents without their own (2026-11-13).** Claude Desktop has no file tools, so without a
  filesystem server it could run steps but not look at the input or the iFlow, nor keep a result. The server
  adds what the app's file tree, **Save as…** and **Import iFlow** give a person: `read_file`, `save_file`
  (an existing file only with `overwrite`), `import_iflow` (an existing folder only with `overwrite`), all
  through the engines' `ProjectFiles` path checks, plus `reference` (Script API, `platform/` formats, fidelity,
  generated from `completions.ts`, the clean-room classes and the docs, so it cannot drift), server
  `instructions` with the way to work, and one prompt. Still left out: running test cases (they are the
  repository's regression cases, not how a user tries a step).
* **Results by reference (2026-11-13).** A headless agent limited to the MCP tools copied each output body into
  the next call. Runs now return a `runId`, and `messageFrom` / `messagesFrom` take the message on, as it flows
  through an iFlow. Opaque handles of the server process, as the 2026-07-28 specification describes stateful
  tools (validated on each call, an unknown one is a tool error); a per-process prefix makes an id from an
  earlier process unknown rather than another run. Intermediate files in `inputs/` were ruled out: they stay
  in the project and lose headers and properties.
* **Flow steps** run like the Flow Steps tab, call for call (`mcp/flow.rs` follows
  `store.svelte.ts`), so no CLI command was needed first. A test-case step for flow steps stays open.
* **Security:** the server is bound to one project folder, opens no port, and marks only the project,
  XSD and XPath tools read-only (XSLT's reflexive `java:` calls and Groovy run code without a sandbox,
  as in the app). Payload files may carry prompt injection; the docs say so.

### 10.2 Updates (check built for 0.3.0; install planned)

Researched on 2026-11-04 against the source of `tauri-plugin-updater` 2.14.1 and Tauri's NSIS
template; the Tauri docs site was not reachable then. Installs of 0.2.0 and earlier have neither
step and are updated by hand once.

**0.3.0: check for updates.** A gear icon opens a small settings menu with the installed version
and *Check for updates*. The button asks the GitHub API for the latest release
(`/repos/jsevenheck/cpi-workbench/releases/latest`), compares the versions (SemVer) and, for a
newer one, links to its release page; installing stays manual. Decisions:

* **Only on demand.** The app makes no network request on its own: no check at start-up. The README
  and the user guide say that this button contacts github.com (the app still never connects to a
  tenant).
* The CSP allows `connect-src https://api.github.com` (GitHub's API answers CORS requests with
  `Access-Control-Allow-Origin: *`). No keys, no CI change.
* *Built (2026-11-07):* the release page opens through a command of the shell
  (`open_release_page`), not through `tauri-plugin-opener`: the plugin added about 40 crates to
  `Cargo.lock` (D-Bus via `zbus` on Linux) for one link. The command accepts this repository's
  release pages only and hands them to the system's opener (`rundll32 url.dll,FileProtocolHandler`
  on Windows, `open` on macOS, `xdg-open` on Linux). The menu is in the desktop app only; tried in a
  browser with a mocked shell, not yet in the desktop window on Windows or macOS.

**With signed installers (Phase 5): install the update.** `tauri-plugin-updater` checks a
`latest.json` in the release, downloads the package and installs it. Facts from the source: it
handles all our bundles (NSIS `setup.exe`, `.msi`, the macOS `.app`, `.deb` and `.rpm`, the last two
since 2.10.0 with `pkexec` or `sudo`) and picks the one the app was installed with; every package is
signed with an updater key pair of its own (public key in `tauri.conf.json`, private key as a CI
secret), independent of code signing; on Windows it starts the installer and ends the app with
`std::process::exit(0)`; the NSIS installer finds a running `cpi-workbench.exe` through the Restart
Manager and offers to close it. What this repository needs:

* **Stop the engines in `on_before_exit`.** `process::exit` skips `RunEvent::Exit` in `lib.rs`, so the
  engines would otherwise keep running and may hold files in the install folder.
* **MCP servers** started by an agent run the same `cpi-workbench.exe`; the installer offers to close
  them too. The docs say so.
* **CI:** `bundle.createUpdaterArtifacts`, signing on all three OSes, and a step in the `release` job
  that writes `latest.json` (version, notes, URL and signature per target: `windows-x86_64-nsis`,
  `windows-x86_64-msi`, `darwin-aarch64-app`, `linux-x86_64-deb`, `linux-x86_64-rpm`). The workflow
  does not use `tauri-action`, so this step is our own.
* **Key custody:** losing the private updater key ends automatic updates for every installed app;
  it needs a backup outside GitHub.
* **Manual test** ([Manual test](manual-test.md)): install the previous release and update from the
  app, per bundle.

To verify then: whether SmartScreen prompts for an installer the updater downloaded (an assumption:
no Mark of the Web, so no prompt); replacing the ad-hoc signed macOS app; the updater behind a
company proxy (it has proxy settings; whether it uses the Windows system proxy is not checked).

### 10.3 Message Mapping scripts (built for 0.3.0)

Found in the Windows test on 2026-11-06: iFlows carry Groovy scripts of Message Mappings, custom
functions such as `def String f(String arg1, MappingContext context)`. Graphical Message Mapping stays
out of scope (§1); running its scripts on their own is in scope. Built from SAP's Javadoc (F40), SAP's
patch notes (F41) and SAP's PI documentation of queues (F42):

* `MappingContext`, `ExchangePropertyProvider` and `Output` in `api-common`, for both engines. A
  function whose parameters are `String`, `String[]`, `Output` and `MappingContext` runs with
  arguments instead of a message; the result has its returned value or output queue, and headers
  and properties as for a script step. On 2.x a warning cites F41.
* Execution types follow the signature (F44): *single* (no `String[]`: one call) and *All values of
  a context* (`String[]`: one call per context, without context changes in the arrays, F42; a tenant
  showed it, F59, F60). Each call gets an `Output` of its own; the results are joined by context
  changes, as on a tenant (F60).
  A first version also offered *All values of a queue*; it was removed when two community guides
  said CPI has no such type.
* The iFlow import lists Message Mapping steps (F45) and opens the scripts their `.mmap` uses.
* Not simulated: graphical Message Mapping and `.mmap` files; calling a single-value function once per
  value of a queue (a tenant does, F60; the simulator runs it once with the values given). The markers
  have a tenant's texts (F60) and are compared by identity (an assumption).

### 10.4 Converters (built for 0.3.0)

JSON ↔ XML and XML ↔ CSV as a *Converter* step in the Flow Steps tab, not a tab of their own: the tab already
has the input message, the step settings, the result and *Use as input* to chain steps, and the iFlow import
and `run_flow_step` cover it. The engine class is `ConverterStep` in the XML engine (native, no new library;
its JSON reader keeps member order, duplicates and number text). Rules come from SAP's documentation (F47,
S30), the iFlow keys from public iFlows (F46). Not built, from SAP's list of message transformers: EDI ↔ XML
and the EDI Extractor (they need EDI schemas), Encoder / Decoder (Base64, GZIP, ZIP, MIME Multipart), XML
Modifier and Message Digest; they can follow when needed (Encoder / Decoder and Message Digest are
planned, §10.5 W4).

### 10.5 Next features, from a review of other tools (planned, 2026-11-13)

On 2026-11-13 other tools for CPI development were read: their own pages and READMEs (S33–S46). Nothing
was installed or measured. Three pages could not be opened and are known from search results only: SAP
Help's pages on simulation, the blog on JDI debugging (S34) and IntegratedGroovy (not listed for that reason).
No tool found combines Groovy, XSLT and flow steps offline; each covers a part. Most are small projects by
what their pages showed that day (single-digit stars, one extension with about 150 installs); SAP CPI Groovy
Script (about 1,900 installs) and CPI Helper (97 stars) are the exceptions. What they do better
in their part is planned here, and what stays out is listed with the reason.

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

#### Planned work, in this order

Sizes are estimates: S is a day or two, M about a week, L more. Each item follows the rules in `AGENTS.md`
(tests, `fidelity.md` and `status.md` where behaviour changes, a changelog line).

**W1. Platform data, attachments and SOAP headers in the Groovy tab (M; built on 2026-11-13).** *Seen in GrooveBox.*
Before, the mocks' `platform/*.json` files ([test cases](test-cases.md)) opened only as JSON in the File tab,
and a test case's attachments and SOAP headers showed only as a note ("edit in `case.json`"). Built as planned
below; a table's unsaved rows go with each run as the `platform` override, **Save** writes the file.
* Input tabs for attachments and SOAP headers, and a *Platform* tab with tables for value mappings,
  credentials, partner directory parameters and number ranges. The keystore stays a file reference.
* The JSON files stay the source of truth, as the queue editor keeps its text form: the tables read and write
  them, so the CLI, test cases and the MCP server are untouched. Without a project the rows go with the run
  as the `platform` override a test case already uses.
* No engine change and no fidelity change. The tab repeats the rule on test-only secrets.
* Done when a script that uses `ValueMappingApi` and `SecureStoreService` can be set up and run without
  editing JSON, and the Groovy e2e covers one table.

**W2. Take a traced message over from CPI Helper (built on 2026-11-13, except the desktop link).** *Seen in CPI Helper's plugins.*
Built: the spike answered Q-T2 (S47): both plugins have a *Custom URL* setting and append the data to it, so
the browser mode takes a link that points at it (`#share/<data>`, which the browser sends to no server), and
**Import…** in the Groovy and XSLT tabs takes a pasted link or a message as JSON (`app/src/lib/share.ts`). An
imported script or mapping is not run until the user starts it, and the import goes one way (the app sends and calls nothing). Not tried with the extension on a tenant: the reading
was tested against links built as the plugins' sources build them. Open: one click into the **desktop app**,
a `cpi-workbench://` link the installers register (Tauri's deep-link and single-instance plugins); it needs
the installers and a check of how long such a link may be on Windows and macOS (a Windows command line ends at
32,767 characters, and a link carried 296,000 characters of body in 70,000 here). The plan as written before:
The largest gain in daily work: a real message from a tenant's trace as local input. The app still
connects to nothing; the user's browser has the data and hands it over. It also answers part of Q-T1 by
another route.
* Spike: how the plugins pass the data (a URL with the encoded message, as XSLTDebugX's share links are;
  the clipboard; a file) is not known (Q-T2).
* First step, whatever the spike finds: *Import message…* in the input pane takes the clipboard or a file
  and fills body, headers and properties of the open tab. A link the browser mode accepts, or a
  `cpi-workbench://` link for the desktop app, can follow.
* Headers come as the trace has them, adapter headers included. A trace can hold business data: it stays on
  the computer, and the user guide says so.
* Done when a traced step's message is the input of a local run in two steps, and the user guide shows how.

**W3. XPath tab: matches in the document, copy an element's XPath, history (S; built on 2026-11-14).** *Seen in XSLTDebugX.*
Built: the engine gives each node of the body its place as element positions from the root (`at`), on both
XPath versions (the JDK's DOM has no line numbers, so positions instead of lines); the UI finds the place in the
text with a small scanner (`app/src/lib/xmltext.ts`). An entity reference in the content can stand for elements
the scanner cannot see, which shifts same-named siblings (found in a review on 2026-11-14: the mark landed on the
wrong one); for such a document nothing is marked and XPath at cursor is off. The element name is compared as
well. The same scanner writes the XPath at the cursor, which goes
into the expression field and the clipboard. The plan as written before:
* Matches marked in the document editor. `xpath.eval` returns the items without their position, so the engine
  has to add line numbers: Saxon keeps them when asked; whether the JDK's XPath 1.0 path can is to check.
* *Copy XPath* for the element at the cursor, written with the prefixes of the Namespace mapping.
* The last expressions of the session with ↑ and ↓.

**W4. Encoder, Decoder and Message Digest as flow steps (M).** *Prompted by the encoding tools in All-in-one;
they are CPI steps, listed as not built in §10.4.*
* First the sources, as for the converters: SAP's documentation of each step, the iFlow keys from public
  iFlows (as F46), and a probe for what the documentation leaves open (Q-E1).
* Base64 encode and decode, GZIP and ZIP compress and decompress, Message Digest. MIME Multipart, EDI and the
  XML Modifier stay out.
* Compressed bodies are bytes, and bodies travel as text through JSON-RPC; how a binary result is shown and
  passed on is the design question of this item.
* The Flow Steps tab and `mcp/flow.rs` together, the iFlow import, a row in `fidelity.md`, probe baselines.

**W5. JUnit XML from the test command (S).** *Prompted by the CI focus of Figaf and cpictl.*
`test --junit <file>` writes the report that GitHub Actions reporters, Jenkins and GitLab read: one test
case per case, the cases of other engines as skipped. In `TestRunner` and `Cli`; documented in
[test cases](test-cases.md).

**W6. Test cases: leave out values that change per run (S to M).** *Our own idea; no tool's page describes how
it compares.*
`compare` knows `xml`, `text` and `trimmed`; headers and properties are checked only when named. New:
`expect.ignore`, a list of XPaths whose nodes are removed from both sides before an `xml` comparison
(timestamps, generated ids). JSON bodies can follow.

**W7. Import a Value Mapping artifact (S to M).** *Seen in SAP CPI Groovy Script.*
An exported Value Mapping artifact (zip) becomes rows of `platform/value-mappings.json`, next to the iFlow
import in `IflowImport`, with its path checks. The export format is not documented (Q-V1); it is read from a
sample first, best effort as the iFlow import is.

**W8. Run history (S to M).** *Seen in Contiva.* The last runs of a tab in this session (time, status, output)
and *Compare with the previous run* through the existing comparison view. In memory only.

**W9. Examples without a project (S).** *Seen in XSLTDebugX and GrooveBox.* An *Examples* menu loads a mapping
or script with its input into the editors. The installers do not ship `examples/demo-project` today, so the
examples have to be bundled.

**W10. Debugging Groovy with breakpoints (spike S; a debugger in the app: L, not planned).** *Seen in SAP CPI
Groovy Script.*
* Stage 1: a documented way to attach IntelliJ or VS Code to an engine jar started with the JDWP agent
  (`java -agentlib:jdwp=… -jar cpiwb-groovy-v1.jar run --script …`). Whether breakpoints bind to the script
  file is open (Q-D1). It needs a full JDK: the app's trimmed JRE (`engine/groovy/build-jre.sh`) has no
  `jdk.jdwp.agent` module.
* Stage 2, breakpoints in the app's editor, only if users ask for it.

#### To decide before any work

* **A chain of steps in the app.** SAP simulates up to 10 steps, and the MCP server chains runs by `runId`
  (§10.1). Whole iFlows are out of scope (§1): what happens between steps (which headers stay, how a
  Splitter iterates) would need evidence step by step. If it is built, then as a chain the user puts
  together, with no claim to be the iFlow.
* **A diagram or description of an imported iFlow** (as `iflow_render` in S44): helps to find a step, changes
  nothing about the simulation. M.

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
| Share links with the data in the URL | S35 | The project folder is the unit here, in Git if wanted; such links end at about 2,000 characters (S35). |
| Saving a test case from the app | — | Removed on purpose (2026-10-25): test cases are written by hand and run from the CLI. |
| A VS Code extension | S33, S34 | A second UI to maintain; the CLI and the MCP server connect editors. |

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
and Message Mapping probes (F54–F61): the converters write a tenant's bytes, and mapping functions
follow its calls and marker texts.
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
| Q-G6 | Which bindings are in the script context (`messageLogFactory`, others?) | Answered (F49, F53, F58); where `ScriptLogger` writes is open | — |
| Q-X1 | Saxon edition/version | Answered: EE 9.9.1.6 (F51) | — |
| Q-X2 | `xsl:param` precedence when header and property share a name | Answered: the header (F51) | — |
| Q-X3 | Type of bound parameter values (string vs. typed) | Answered: typed (F51) | — |
| Q-X4 | Behaviour for a declared `xsl:param` without matching header/property (F12) | Answered: the default (F51) | — |
| Q-X5 | `setHeader`/`setProperty` signatures and return value | Answered: the empty sequence (F51) | — |
| Q-X6 | Source/target from header/property in newer step versions | Open | SAP docs + probe |
| Q-V1 | XSD engine and version behind the XML Validator step | Open | Probe iFlow (error message format) |
| Q-T1 | Format of exported MPL trace content | Open (F18) | Export one sample |
| Q-T2 | How CPI Helper's GroovyDebugX and xsltDebugX plugins hand a traced step's payload, headers and properties to an external IDE (URL, clipboard, file; the encoding) | Answered from the plugins' sources (S47): a link, `<url><data>` or `<url>#share/<data>`, the data being JSON, raw DEFLATE, URL-safe Base64; a *Custom URL* can be set. Not tried on a tenant. | Send one step from a tenant and import it |
| Q-M1 | Message Mapping scripts: Groovy and Java version, whether the signature alone picks the execution type (single value or context, F44), how the per-context results are joined, the marker value of SUPPRESS, whether a single-value function is called once per value | Partial: versions, one call per context, the join by context changes, single-value calls once per value and the marker texts answered (F59–F61, an empty context is called with an empty array); open: SUPPRESS in a function's input, identity or equals for the markers | `probe-mapping-v1.groovy` with the `Call` target (probes/README.md, step 6) |
| Q-C1 | Converters: compact or indented output, the XML declaration, CSV line ends and quoting, an unmapped JSON prefix without namespace mapping (F47), the XML to JSON namespace table's cell order | Answered for the probes' cases (F54–F57); open: the XML to JSON namespace table's cell order, `\r\n` or `\n` in CSV | Save a response with `curl --output` |
| Q-F1 | Gather's Combine with a body that is not XML (JSON or plain text after a script): failure, the text as it is (as character data in the wrapper, malformed only where the text is, e.g. `a & b`), or another form. SAP documents XML only and shows no such case | Open; the simulator keeps the text and warns | `probes/gather/` (probes/README.md, step 8) |
| Q-P1 | Partner Directory / Keystore / Secure Store edge cases: missing parameter, unknown partner, unknown keystore alias (null or exception, which message) | Answered (F52) | — |
| Q-E1 | Encoder, Decoder and Message Digest: line breaks in Base64 output, the entry name in a ZIP, the digest's header name, encoding and canonicalization | Open (§10.5 W4) | SAP's step documentation, then a probe iFlow |
| Q-V1 | Format of an exported Value Mapping artifact | Open (§10.5 W7) | Export one sample, or read one from SAP's public sample packages |
| Q-D1 | Whether an IDE's breakpoints bind to a script that an engine jar runs under the JDWP agent | Open (§10.5 W10) | Try it with IntelliJ and VS Code on `cpiwb-groovy-v1.jar run` |

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
- S47 CPI Helper's plugins `plugins/groovyDebugger.js` and `plugins/xsltDebugX.js` (GPL-3.0), read on 2026-11-13 for the format of the link they open and its settings; no code taken: https://github.com/dbeck121/CPI-Helper-Chrome-Extension/tree/master/plugins
