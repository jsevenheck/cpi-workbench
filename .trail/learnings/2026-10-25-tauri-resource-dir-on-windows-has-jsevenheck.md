---
title: Tauri resource_dir on Windows has a \?\ prefix that Java rejects
author: Jsevenheck
date: 2026-10-25
branch: main
cost: { minutes: 2, tokens: 21435 }
touches:
  - CHANGELOG.md
  - app/src-tauri/src/engine.rs
  - app/src-tauri/src/lib.rs
---

## Decided
* `lib.rs` passes `app.path().resource_dir()` through `engine::plain_path`, which strips `\?\` (and maps `\?\UNC\` to `\`). Java's `-jar` cannot open a verbatim path ("Unable to access jarfile \?\C:\..."), so the installed Windows app failed to start the Groovy engines with "exited during start (exit code None)". Native XML engines were unaffected (located via current_exe, and CreateProcess accepts verbatim paths).
## Tried and ruled out
* Adding the `dunce` crate: not needed for this one call site.
## Watch out
* Dev runs from the repo don't hit this; only an installed build resolves the jar from resource_dir. Reproduce with the installed jre: `jre/bin/java.exe -jar '\?\C:\...\cpiwb-groovy-v2.jar' serve --stdio`.
