---
title: "The Windows desktop app can be driven by Playwright over WebView2's debug port, so manual checks need no clicking"
author: Jona
date: 2026-11-13
branch: claude/nice-turing-nql3ja
cost: { minutes: 18, tokens: 131549 }
id: l-4706536c
scope: repo
status: active
src: agent
touches:
  - docs/manual-test.md
  - engine/groovy/src/main/java/io/cpiwb/groovy/MappingFunction.java
---

## Decided
* For the manual checks of the desktop app on Windows (docs/manual-test.md), start `npx tauri dev` with WebView2 remote debugging and drive the real webview with Playwright's `connectOverCDP`: the × close buttons with Cancel/OK, the Function picker and the context editor were checked this way on 2026-11-13, with screenshots of the Windows rendering.
* A project is opened without the native folder dialog by setting `localStorage['cpiwb.project']` to its path and reloading; the app reopens the last project on start.

## Watch out
* `getHeader(null)` on MappingContext fails with a NullPointerException once any header is set: Camel's header map compares names with `String.CASE_INSENSITIVE_ORDER`. With no headers it returns null (Camel returns early). The old manual-test row expected `null`; what a tenant does is not verified.
* A new String[] argument starts with an empty context 1 (no value box); **+ Context** adds a context with one empty value.
* Clicking Monaco's `.view-lines` with Playwright is blocked by the overview ruler canvas; click with a position and `force: true`.
* The app's footer shows the Java of `JAVA_HOME` (25 here), not the bundled 21, in a dev run.

In the code: `WEBVIEW2_ADDITIONAL_BROWSER_ARGUMENTS=--remote-debugging-port=9223`, `JAVA_HOME=<jdk>`, `npx tauri dev --config '{"bundle":{"externalBin":null,"resources":null}}'` in app/; then `chromium.connectOverCDP('http://127.0.0.1:9223')` with playwright from `npm install --no-save playwright` in app/. MappingFunction.Context.getHeader in engine/groovy/src/main/java/io/cpiwb/groovy/MappingFunction.java.
