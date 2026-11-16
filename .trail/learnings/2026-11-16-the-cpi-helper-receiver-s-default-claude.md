---
title: "The CPI Helper receiver's default port moved to 7790; a saved 7778 is left alone because the app cannot change the plugins' Custom URL"
author: Claude
date: 2026-11-16
branch: claude/great-cannon-v2nd6h
cost: { minutes: 75, tokens: 868827 }
id: l-460c0a5b
scope: repo
status: active
src: agent
touches:
  - app/src/lib/share.ts
  - app/src/lib/receiver.svelte.ts
  - app/tests/share.test.mts
  - docs/user-guide.md
  - docs/architecture.md
  - docs/manual-test.md
  - docs/plan.md
  - CHANGELOG.md
  - docs/test-cases.md
  - app/scripts/mcp-smoke.mjs
---

## Decided
- The receiver's default was 7778 in 0.4.0. That is the Groovy engines' own `--http` default and the Groovy 1.x port of the browser mode, the e2e tests and CI (7777 to 7780 are taken by the four engines), so the desktop app with the receiver on and a browser-mode Groovy 1.x engine could not run side by side. The user agreed to 7790 on 2026-11-16, the day 0.4.0 was released (no downloads yet).
- No migration code. The setting is stored with its port every time the user switches it, so whoever turned the receiver on in 0.4.0 keeps 7778 and the Custom URL in GroovyDebugX and xsltDebugX keeps working. Only an installation that never touched the setting gets 7790.
- `test <path> --junit <file>` writes one `testsuite` per engine run. The user chose the engine's profile as each case's `classname` (2026-11-16), so the four engines' reports stay apart when a CI system merges them. Output and exit code do not change; a report that cannot be written is exit code 1, `--junit` without a file is 2.
- JUnit XML has no official schema. The layout is Maven Surefire's (root `testsuite`), because its schema is published and can be checked: reports from all four engines, with failed, broken and skipped cases, validate against `surefire-test-report.xsd` with xmllint.
- The XML is written by hand, not through the DOM helper: a message may quote any input, and characters XML 1.0 cannot hold (control characters, a lone surrogate) must become U+FFFD first, which the JDK serializer does not do. It also needs nothing new in the native images.
- All checks before a push ran in a Linux cloud container on 2026-11-16 (Java 21, Node 22, cargo present): `mvn verify`, the regression cases on the four jars, `npm ci` then check, test and build, both browser e2e tests, `cargo test` with the TAURI_CONFIG override, and the MCP smoke test.
- `expect.ignore` (W6) is XPath 1.0 of the JDK in `core`, not Saxon: the Groovy engines have no Saxon, and a case should read the same on all four engines.
- Prefixes come from `expect.namespaces`. This was not in the roadmap text; it came in because the JDK, without a namespace context, takes an unknown prefix for a name that matches nothing and says nothing. With the context an unknown prefix is an error.
- A rule that cannot apply makes the case an error (not a list of strings, no XPath, a number or string result, the whole document, no expected body, a comparison as text). A rule that matches nothing is allowed, because the node may be optional; when the bodies differ, the message names the rules that matched nothing.
- The sources for W4 were read on 2026-11-16 and are in the plan's ledger (F62, F63, S49, S50, Q-E1). Nothing of W4 is built: how a binary body is shown and passed on is the user's decision, and the probe for Q-E1 is not written.
- The Message Digest step and the Encoder's Base64 Encode were built first (the user's choice on 2026-11-16: steps whose result is text or a header before those with a binary body). Decode, GZIP and ZIP wait for `bodyBase64` in a step's result.
- Canonical XML comes from the JDK's public XML signature API (`XMLSignatureFactory`, a `CanonicalizationMethod` as transform) with a node set of the element, its attributes and everything below it. That gives the W3C's document-subset form: a filtered element keeps the namespaces in scope and, for Canonical XML, inherited xml: attributes.
- What SAP does not document is refused or labelled, not guessed: a filter with several nodes or none, a filter with None, None hashing the body's bytes (assumption, with an info or warning).
- A Decoder step in an imported iFlow is not listed, like other steps that are not simulated. A Message Digest whose iFlow has keys the import does not know gets a note, because the filter's key was never seen.
- Stage 2 of W4 (user, 2026-11-16: go on before the probe has run). Base64, GZIP and ZIP in both directions follow the source of Camel 3.14.7's camel-base64, camel-zip-deflater and camel-zipfile data formats with their defaults, on the assumption that CPI's steps are those data formats (not verified, Q-E1).
- That corrected stage 1: Base64 Encode now writes lines of 76 characters, each ended by CR LF, the last one too. The first version wrote one line, a guess.
- A result that is no text travels as `bodyBase64` with `binary: true` and no `body`; the engine's input already took `bodyBase64`.

## Tried and ruled out
- Rewriting a saved 7778 to the new default: the hand-over is one way, the app cannot change the Custom URL in the extension, so the one-click path would break without a word. A saved 7778 also cannot be told apart from one the user chose.
- Moving the engines' ports instead: more places (the Java default, the browser mode's address, e2e, CI, docs) and the older convention.
- A hint under the settings while the port is 7778: the bind error already says "Port 7778 cannot be used … Choose another port" when the conflict happens.
- Using the option in CI (`build.yml`): the user said not for now.
- The case's folder as `classname`: the user preferred the profile.
- `Document.normalize()` after removing nodes: the text on both sides of a removed element stayed two text nodes, so `<a>x<t/>y</a>` without `t` did not equal `<a>xy</a>`. `normalizeDocument()` merges them.
- A case in the regression project: its counts are known to CI, the shell tests and a learning; the rule is covered by the core tests instead.
- Reading help.sap.com directly: its pages are rendered by scripts and come back empty. The same pages are in the public mirror `SAP-docs/btp-integration-suite` under `docs/ci/Development/` (a shallow git clone works; GitHub's API for that repository is not open to a session).
- The SAP blog on ZIP and GZIP that the pages link: HTTP 403. The ZIP entry's name and GZIP details therefore stay open in Q-E1; do not fill them from search-result snippets.
- Apache Santuario as a dependency, or the JDK's internal canonicalizer classes: a new library with its own native-image needs, or classes the module does not export.
- Copying the filtered element into a new document before canonicalizing: it loses the namespaces and xml: attributes the element inherits, which Canonical XML includes.
- Taking the tracing agent's output as it is: it also rewrote the locales of resource bundles that were there and added Saxon classes that `saxon-generated` covers. Only the canonicalizer classes, the digest classes and the `xmlsecurity` bundle were kept.

## Watch out
- Whether other common software uses 7790 was not checked.
- A unit test cannot import a `.svelte.ts` file (runes), so the constant lives in plain TypeScript and the test also checks that the user guide names the same port.

In the code: `RECEIVER_DEFAULT_PORT` in `app/src/lib/share.ts`, used by `app/src/lib/receiver.svelte.ts` (key `cpiwb.receiver` in localStorage); test in `app/tests/share.test.mts`; `docs/user-guide.md`, `docs/architecture.md`, `docs/manual-test.md` 2.11j, `docs/plan.md` W2b.
- No CI reporter (GitHub Actions, Jenkins, GitLab) has read a report yet; the docs say so. Do not write "read by" until one has.
- The text and JSON summary still count an error under "failed"; only the report separates `failures` and `errors`.
- The cases run before `--junit` without a file is rejected.

In the code: `Cli.testCommand`, `Cli.junitXml`, `Cli.xml` in `engine/core`; both `Main` classes call `testCommand`; tests in `CoreTest`; `docs/test-cases.md`, `docs/plan.md` W5. Schema: https://maven.apache.org/surefire/maven-surefire-plugin/xsd/surefire-test-report.xsd
- `cargo test` fails in the build of `gdk-sys` until Tauri's Linux libraries are installed: `apt-get install -y libgtk-3-dev libwebkit2gtk-4.1-dev libsoup-3.0-dev libjavascriptcoregtk-4.1-dev librsvg2-dev libayatana-appindicator3-dev`.
- `cargo test` builds no `target/debug/cpi-workbench`. Run `cargo build` with the same TAURI_CONFIG override before `node app/scripts/mcp-smoke.mjs`.
- Playwright is already installed in the container and the e2e scripts find it through NODE_PATH; do not install it again.
- Stop the four `serve --http 777x` engines after the e2e tests, or the next start fails to bind.

In the code: `docs/building.md` (Shell tests, End-to-end tests), `app/e2e/*.mjs`, `app/scripts/mcp-smoke.mjs`.
- JSON bodies have no such rule yet.
- The rule is this tool's own; it imitates nothing in CPI.

In the code: `TestRunner.check`, `TestRunner.compare` (five arguments) and `TestRunner.remove` in `engine/core`; tests `xmlCompareLeavesOutIgnoredNodes` and `aCaseWithIgnoreRunsAndAWrongRuleIsAnError` in `CoreTest`; `docs/test-cases.md`.
- In an exported iFlow a Decoder step holds its scheme in the key `encoderType` too (`Base64 Decode`, `ZIP Decompress`); there is no `decoderType`.
- The documentation writes the algorithms as `SHA512`, the one iFlow sample has `SHA-512`. Only that value and `canonicalizationMethod` = `none` were seen; the digest's filter key and the GZIP schemes were not.
- The mirror's page *Define an Encoder* has an empty list of schemes.
- The engine already reads a binary input body (`bodyBase64`); a step's output body is text only.

In the code: `docs/plan.md` (F62, F63, S49, S50, Q-E1, W4); `SimExchange.fromJson` and `toJson` in `engine/core`.
- In a native image the canonicalization failed with "No handler installed" until the metadata was added; the JVM tests could not show it. The smoke workload now runs every canonicalization method.
- GraalVM Community 21.0.2 for Linux unpacks into a scratch folder (about 290 MB) and builds one XML engine in about two minutes on 4 CPUs, so native behaviour can be checked in a cloud container. Windows and macOS still need CI.
- In the Flow Steps tab the function that opens an imported step treats a type it does not know as a Splitter: every new step type needs its own branch there, and a row in the MCP server's config keys, its type list and `produced`.

In the code: `DigestStep`, `EncoderStep` in `engine/xml`; `FlowSteps.of` in `engine/core`; `flow.encoder`, `flow.digest`, `openFlowStep` in `app/src/lib/store.svelte.ts`; `mcp/flow.rs`, `mcp/runs.rs`; `engine/native/workload.py`; `META-INF/native-image/io.cpiwb/cpiwb-xml-he*/`; `probes/encoders/`.
- Checked in a scratch run against commons-codec 1.16.1 and commons-compress 1.26.1 (Camel 3.14.7 ships older ones): 401 random inputs for Base64 Encode and GZIP, 20,000 dirty Base64 texts for the lenient decoder, no difference. commons-codec's decoder also takes `-` and `_`.
- The work was interrupted: see the last commit's message for what is not adapted yet.

In the code: `EncoderStep` (`encode.run`, `decode.run`), `SimExchange.toJsonOrBinary`, `ProjectFiles` (`binary`, `contentBase64`), `FlowSteps.of`.
