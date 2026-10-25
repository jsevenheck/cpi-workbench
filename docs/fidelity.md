# Fidelity: what matches CPI and what does not (yet)

"To verify" means the behaviour follows SAP's documentation or Camel's code but has not been
observed on a tenant. The probes in [`probes/`](../probes/README.md) turn these items into facts.
Finding numbers (F…) and questions (Q…) refer to the [plan](plan.md).

## XSLT Mapping and XML tools

| Area | Status |
|---|---|
| Saxon release line (9.9) | Same line as CPI. CPI runs the **EE** edition, the simulator **HE** (F8). |
| Higher-order functions, `xsl:evaluate` | Not in HE 9.9; use the *Modern HE (Saxon 12)* profile. The error message says so. |
| Schema-aware XSLT, streaming | EE only; not available. The error message says that CPI supports it. |
| `format-date` with languages other than English | HE prints English names (`[Language: en]…`); CPI's EE probably localises them (F20). |
| Header vs. property with the same name | Header wins (Camel XsltBuilder order). **To verify** (Q-X2). |
| Declared parameter without header or property | The XSLT default is used; can be switched to "error". **To verify** (Q-X4). |
| Typed parameter values | Passed as Java objects, so Saxon converts them (F21). **To verify** (Q-X3). |
| Reflexive Java calls (`java:` namespaces) | Supported for JDK classes, since SAP's own samples use them (F32). Classes from the iFlow's jars are not available to XSLT. Argument conversion follows simple rules, not all of Saxon-EE's. |
| XSD validation | JDK validator, XML Schema 1.0. CPI's validator is **to verify** (Q-V1). |
| File access (includes, `doc()`, `unparsed-text()`, external entities) | Inside the project folder only, no network: a simulator rule, standing in for the iFlow's resources. Whether CPI resolves external entities and DTDs is **to verify**. |
| `xsl:result-document`, `collection()` | Not supported (`UnsupportedInSimulator`); the simulator has no place for secondary results. What CPI does with them is **to verify**. |

## Groovy Script

| Area | Status |
|---|---|
| Groovy versions | Groovy 2.4.21 for 1.x and 4.0.29 for 2.x, as SAP documents (F1, F2). |
| Java version | Java 17, as reported for 2.x (F3). The Java version behind 1.x is **to verify** (F4). |
| Camel version | 3.14.7 for 2.x (reported). 1.x runs on 2.24.3, but SAP's v1 API jar targets Camel 3.14 since late 2025, so 1.x may run on 3.14 now: **to verify** (F30, Q-G3). |
| Script API v1 | The methods of SAP's published interface (`script.api` on Maven Central): body, headers, properties, attachments (`AttachmentWrapper`), SOAP headers, sizes (F29). Not offered: the overloads that take Camel's own `Attachment` type. |
| Script API v2 | The methods of SAP's published interface (`script.v2.api` 4.2.2): attachments, `getHeader(name, default)`, `resetBodyStream`, exchange timestamps. ProcessDirect calls raise `UnsupportedInSimulator`. CPI's runtime class may offer more than the interface; `fingerprint-v2.groovy` lists it (Q-G4). |
| Message log | `messageLogFactory` and `MessageLog` follow SAP's published interface; MPL disabled gives `null`. |
| Platform APIs | Follow SAP's published `generic.api` (F33): Secure Store (user credentials, OAuth2 tokens from a fixed value), Value Mapping (`getMappedValue`), Partner Directory (parameters, certificate parameters, partner IDs, authorized users, deprecated pattern lookups), Keystore (keys, certificates, chains, key pairs, key and trust managers), number ranges. Other services raise `InvalidContextException`. The runtime's edge cases are **to verify** (Q-P1). |
| Data Store from scripts | Not simulated: CPI has no public script API for it (F28). |
| Extra libraries on the class path | Unknown (F31). The simulator adds only `lib/` jars. |
| Groovy modules on 2.x | xml, json, dateutil, datetime, templates and nio are bundled. CPI's set is **to verify**. |
| Pretty-printing (`XmlUtil.serialize`) | Depends on the JDK version (F25), so byte-exact comparisons depend on Java 17 being right. |
