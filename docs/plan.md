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
| F22 | Header-over-property precedence is modelled on Camel's XsltBuilder (properties set first, headers override). That CPI uses this code path is an inference. | Inferred → probe Q-X2 | `XsltStep.java` |
| F23 | Saxon instantiates built-in functions reflectively; native builds need reflection metadata for all of them (generated by `engine/native/GenReflect.java`), plus the JDK XPath 1.0 function classes. | Observed | native build failures fixed in this phase |
| F24 | Groovy 2.4.21 runs the Script API v1 corpus on Java 17 and 21 without changes (7 community scripts). | Observed (simulator) | `GroovyCorpusTest` |
| F25 | `XmlUtil.serialize` / XSLT pretty-printing differs between JDK versions (indentation), so the Java version behind each CPI step matters for byte-exact comparisons. | Observed (corpus expected files vs. JDK 17/21 output) | `GroovyCorpusTest` |
| F26 | A classic 1.x → 2.x break is reproduced: scripts using `XmlParser`/`XmlSlurper` without import (default `groovy.util` package) fail on Groovy 4. | Observed (simulator) | corpus `edi-segment-counter` |
| F27 | Partner Directory, Keystore, Secure Store and number ranges: SAP's Javadoc (`generic.api`, F33) documents null for a missing Partner Directory parameter, partner or certificate parameter; `IllegalArgumentException` for null/empty IDs and for types other than `String`/`BinaryData`; null for an unknown keystore alias or the wrong entry kind (`IllegalStateException` when a key cannot be recovered); `SecureStoreException` for an unknown credential. The simulator follows this. Whether the deployed runtime matches its Javadoc, and the exact messages, are not verified. | Observed (Javadoc) / Unknown (runtime) → probe Q-P1 | S24 |
| F28 | CPI has no public script API for the Data Store; scripts that access it use internal classes looked up from the Camel registry. The simulator does not mock this. | Inferred (community examples) | — |
| F29 | SAP publishes its Script API interfaces on Maven Central (`com.sap.cloud.script:script.api` 2.14.0–2.16.4 for v1, `script.v2.api` 4.0.0–4.2.2 for v2; SAP Developer License, not bundled). The simulator's `Message`, `AttachmentWrapper`, `SoapHeader` and the v2 `Attachment`, extension-function and exception types follow these signatures. The v2 `Message` has no `getSoapHeaders`/`setSoapHeaders` and no `AttachmentWrapper` methods, but adds `getHeader(String)`, `getHeader(String, Object)`, `clearAttachments`, `resetBodyStream` and `getExtensionFunctions` (exchange timestamps, ProcessDirect). The interface is a lower bound: CPI's implementation class may offer more, which the fingerprint probes list (`messageMethods`). | Observed (published jars) / Unknown (runtime class) → Q-G4 | Maven Central `com.sap.cloud.script` |
| F30 | The v1 API jar moved from Camel 2.24 (`script.api` 2.15.0, May 2024: imports `org.apache.camel` [2.24,3), `AttachmentWrapper` extends `org.apache.camel.impl.DefaultAttachment`) to Camel 3.14 (2.16.2, Dec 2025, and 2.16.4: [3.14,4), `org.apache.camel.attachment.DefaultAttachment`). So 1.x steps may now run on Camel 3.14, which the fingerprint confirmed (F48); the simulator's 1.x engine still uses Camel 2.24.3. The simulator's v1 Message leaves out the overloads that take Camel's `Attachment` type. | Observed (jar manifests, one tenant) | Maven Central `com.sap.cloud.script:script.api` |
| F31 | Which libraries CPI puts on a script's class path besides Groovy and Camel (e.g. commons-codec, IAIK used by SAP's own sample scripts) is not known; the simulator offers none and loads jars from `lib/` and the iFlow's `src/main/resources/lib/`. | Unknown → fingerprint `classesAvailable` | SAP sample iFlows |
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
| F47 | SAP documents the JSON ↔ XML conversion rules, limitations, error texts and examples, XML to JSON streaming, and two CSV examples with input and output (S30); the converters follow them. SAP's own pages disagree in one place: the JSON to XML rule keeps an unmapped prefix in the element name (`"p.A"` → `<p.A>`), while the example "No Namespace Mapping" turns `"abc.A"` into `<A>` (with separator `:`). The examples also contain typos (`<E>valueE<E>`). Output formatting is shown compact for JSON ↔ XML, indented for CSV to XML. | Observed (SAP docs) → Q-C1 | S30 |
| F48 | The probes on one tenant (2026-11-09): Groovy Script **1.x and 2.x both run on Java 21** (SAP SE JVM 21.0.13) and **Camel 3.14.7-sap-59**; Groovy 2.4.21 and 4.0.29. Default charset UTF-8, timezone `Etc/UTC`, locale `en_US`. The 1.x runtime also offers Saxon-EE 9.9.1.6 to scripts; 2.x does not. | Observed (one tenant) | S32 |
| F49 | Script context on that tenant: 1.x binds `log`, `logger`, `messageLogFactory` and `javax.script.filename`; 2.x only `messageLogFactory` and `javax.script.filename`. Neither binds `out`. The types of `log` and `logger` were not recorded. Runtime message classes: 1.x `com.sap.gateway.ip.core.customdev.processor.MessageImpl` with both the `AttachmentWrapper` and Camel `Attachment` overloads, `getAttachmentObjects`/`setAttachmentObjects` and `getExtensionFunctions`, but no `getHeader(String)`; 2.x `com.sap.it.script.v2.executor.impl.MessageImpl` with the v2 interface plus `getSoapHeaders`/`setSoapHeaders` and `setProxyHandler`. The body behind an HTTPS sender is an `InputStreamCache`. | Observed (one tenant) | S32 |
| F50 | Libraries a script can load on that tenant: 1.x has commons-lang3, Jackson databind, commons-codec, IAIK and `groovy.sql`; 2.x has commons-lang3 and Groovy's yaml and toml modules, but no Jackson, commons-codec, IAIK or `groovy.sql`. Neither has POI or `groovy.ant`. Versions were not recorded. | Observed (one tenant) | S32 |
| F51 | XSLT Mapping on that tenant: **Saxon-EE 9.9.1.6**, streaming, dynamic evaluation and higher-order functions supported, implicit timezone `PT0S`, `format-date` localises German month names (`5. März 2026`), `saxon:evaluate` available. Parameters: a header wins over a property of the same name; typed values arrive typed (Integer → `xs:integer`, Boolean, Date → `xs:dateTime`, `byte[]` → two items); a missing parameter takes the stylesheet's default; `hci:setHeader` returns the empty sequence; `$exchange` prints as `Exchange[<id>]`. All of this matches the simulator except the edition (HE) and the `Exchange` text. | Observed (one tenant) | S32 |
| F52 | Platform services on that tenant, for names that do not exist: Partner Directory lookups return null (also `getAuthorizedUsers` of an unknown partner); a type other than String/BinaryData gives "The type java.lang.Integer is invalid. Allowed types are java.lang.String or com.sap.it.api.pd.BinaryData"; an empty ID "parameterId is empty"; a `[` in a pattern "The pattern '[x]' contains the invalid character '['". Keystore lookups of an unknown alias return null. Secure Store: "Could not fetch the credential for alias X" (also for an empty alias); an unknown OAuth2 client credential "Problem during retrieving the access token for the OAuth2 client credential X: No artifact descriptor found for artifactName X". Number ranges: "Error has occurred while getting next value from number range object. Cannot find nro_metadata for sequence: X". `ITApiFactory.getService(ITApi)` throws `InvalidContextException`. | Observed (one tenant) | S32 |

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
| **Runtime profiles + calibration probes** | One engine per CPI runtime (Saxon 9.9 / 12, Groovy Script 1.x / 2.x, Camel 2.24 / 3.14); probe iFlows to verify against a real tenant (§8) |
| **Compatibility linter** | v1→v2 migration warnings, Groovy 2→4 breaking changes, EE-only XSLT features |
| **Test-case format, runner, CLI** | Regression tests without a tenant |
| **Desktop app and UI** | The editor, the panels, the diff, the "live run" loop |

### 3.3 What we reuse (the foundation CPI runs on)

| Component | Version | Licence | Why |
|---|---|---|---|
| Groovy | 2.4.21 and 4.0.29 | Apache-2.0 | Exactly what CPI uses (F1, F2) |
| Apache Camel core (type converters, Simple) | 3.14.7 for 2.x, 2.24.3 for 1.x (to calibrate, F30) | Apache-2.0 | Same type conversion rules as CPI (F3) |
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
          shared code base: engine/core (Java 17) — JSON-RPC, project files,
          test runner, iFlow import
```

Details: [architecture](architecture.md).

### 5.2 Technology choices and why
- **Engine core in Java 17** (`engine/core`, `engine/xml`, `engine/groovy`): it must sit directly on Groovy, Camel and Saxon, which are Java libraries. Plain Java (rather than Kotlin) keeps the runtime small and is the safest choice for GraalVM native compilation.
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
- **Parameter binding** (F10): every `xsl:param` gets the value of the header or exchange property with the same name. The precedence when both exist, the type passed (string vs. typed value), and behaviour for missing values (F12) are measured with probe iFlows before implementation (Appendix A, Q-X2…X4).
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
A profile is an engine build per CPI runtime (Saxon 9.9 / 12, Groovy Script 1.x / 2.x) and, for flow steps, a Camel line (2.24 / 3.14). Versioned profile files with a `verifiedOn` date were planned; they wait for the probe results.

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
| **1 — Probe kit** | Fingerprint and probe iFlows for the questions in Appendix A | Written, baseline recorded; waiting for a tenant run |
| **2 — XSLT MVP** | XSLT with live mode, CPI binding, both Saxon profiles, XSD validation, XPath tester, test cases, CLI | Done (0.1.0) |
| **3 — Groovy MVP** | Groovy 1.x and 2.x, typed headers/properties, MPL, API call log, console, diff | Done (0.1.0) |
| **4 — Productivity** | Platform API mocks, lint, runtime comparison, iFlow-zip import, flow steps, MCP server | Done (0.1.0–0.3.0) |
| **5 — Distribution** | Installers for Windows, macOS, Linux; code signing, auto-update (§10.2) | Unsigned installers and a manual update check; signing and auto-update open |

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
  a lint tool (the lint comes with every `run_groovy` result). The agent reads and writes files with
  its own tools; the server writes nothing into the project.
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
  a context* (`String[]`: one call per context, without context changes in the arrays, F42). Each
  call gets an `Output` of its own; joining the results with a context change is an assumption.
  A first version also offered *All values of a queue*; it was removed when two community guides
  said CPI has no such type.
* The iFlow import lists Message Mapping steps (F45) and opens the scripts their `.mmap` uses.
* Not simulated: graphical Message Mapping and `.mmap` files; calling a single-value function once per
  value of a queue (PI does; for CPI see Q-M1); CPI's own marker values (the simulator's markers are its
  own strings, compared by identity).

### 10.4 Converters (built for 0.3.0)

JSON ↔ XML and XML ↔ CSV as a *Converter* step in the Flow Steps tab, not a tab of their own: the tab already
has the input message, the step settings, the result and *Use as input* to chain steps, and the iFlow import
and `run_flow_step` cover it. The engine class is `ConverterStep` in the XML engine (native, no new library;
its JSON reader keeps member order, duplicates and number text). Rules come from SAP's documentation (F47,
S30), the iFlow keys from public iFlows (F46). Not built, from SAP's list of message transformers: EDI ↔ XML
and the EDI Extractor (they need EDI schemas), Encoder / Decoder (Base64, GZIP, ZIP, MIME Multipart), XML
Modifier and Message Digest; they can follow when needed.

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
| Groovy 2.4.21 on Java 17 behaves differently from CPI's 1.x runtime | Calibrate (F4) |
| Saxon-HE vs. EE differences | Both HE profiles, diagnostics for EE-only features (§7.3) |
| Undocumented iFlow format | Read generically, checked against public iFlows (F36, F46) |

---

## 13. Decisions

Taken: the build-vs-reuse principle (§3), XSLT first, tenant probes later; the name CPI Workbench and
the Apache-2.0 licence in a public repository; the HE profiles instead of a Saxon-EE licence. Open: a
run of the probes on a test tenant (planned after 0.3.0).

---

## Appendix A — Question checklist

| ID | Question | Status | How to close it |
|---|---|---|---|
| Q-G1 | Groovy versions per step | Answered (F1, F2) | — |
| Q-G2 | Java version per step | Answered: Java 21 for 1.x and 2.x (F48) | — |
| Q-G3 | Camel version per step | Answered: 3.14.7 for 1.x and 2.x (F48) | — |
| Q-G4 | v2 API method list | Answered (F29, F49) | — |
| Q-G5 | `getBody(Class)` conversions for String/byte[]/InputStream/Document bodies | Inferred (Camel) | Probe iFlow |
| Q-G6 | Which bindings are in the script context (`messageLogFactory`, others?) | Answered (F49); the types of 1.x `log` and `logger` are open | Probe listing the binding's types |
| Q-X1 | Saxon edition/version | Answered: EE 9.9.1.6 (F51) | — |
| Q-X2 | `xsl:param` precedence when header and property share a name | Answered: the header (F51) | — |
| Q-X3 | Type of bound parameter values (string vs. typed) | Answered: typed (F51) | — |
| Q-X4 | Behaviour for a declared `xsl:param` without matching header/property (F12) | Answered: the default (F51) | — |
| Q-X5 | `setHeader`/`setProperty` signatures and return value | Answered: the empty sequence (F51) | — |
| Q-X6 | Source/target from header/property in newer step versions | Open | SAP docs + probe |
| Q-V1 | XSD engine and version behind the XML Validator step | Open | Probe iFlow (error message format) |
| Q-T1 | Format of exported MPL trace content | Open (F18) | Export one sample |
| Q-M1 | Message Mapping scripts: Groovy and Java version, whether the signature alone picks the execution type (single value or context, F44), how the per-context results are joined, the marker value of SUPPRESS, whether a single-value function is called once per value | Partial (F40–F44) | `probe-mapping-v1.groovy` |
| Q-C1 | Converters: compact or indented output, the XML declaration, CSV line ends and quoting, an unmapped JSON prefix without namespace mapping (F47), the XML to JSON namespace table's cell order | Partial (F46, F47) | `probes/converters/` |
| Q-P1 | Partner Directory / Keystore / Secure Store edge cases: missing parameter, unknown partner, unknown keystore alias (null or exception, which message) | Answered (F52) | — |

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
