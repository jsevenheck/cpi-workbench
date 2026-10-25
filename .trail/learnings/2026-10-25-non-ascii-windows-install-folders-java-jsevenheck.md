---
title: "Non-ASCII Windows install folders: Java runs through a junction in ProgramData"
author: Jsevenheck
date: 2026-10-25
branch: main
cost: { minutes: 15, tokens: 132379 }
touches:
  - CHANGELOG.md
  - app/src-tauri/Cargo.lock
  - app/src-tauri/Cargo.toml
  - app/src-tauri/src/engine.rs
  - app/src-tauri/src/lib.rs
  - docs/architecture.md
  - docs/install.md
  - app/src-tauri/src/ascii_alias.rs
---

## Decided
* Java 17 on Windows cannot start from a folder with characters outside the ANSI code page (JDK-8205991, open): "could not find java.dll", or "Unable to access jarfile" for the jar. NSIS currentUser installs land in %LOCALAPPDATA%, so a user name like "Łukasz" on a German Windows breaks the Groovy engines. "Jürgen" works (cp1252).
* Fix: `app/src-tauri/src/ascii_alias.rs` points `resource_dir` at a directory junction in `%ProgramData%\CPI Workbench <user SID>` when the path is non-ASCII; AppCDS archives go there too. Verified with the installed 0.1.0 JRE and jars copied to a "Łukasz Żółw" folder: both Groovy engines start.
* The per-user folder is created with a protected DACL (user, SYSTEM, Administrators) and refused if it is a reparse point or owned by someone else: ProgramData subfolders inherit write access for all Users, so another local user could otherwise re-point the junction to their own java.exe.
## Tried and ruled out
* Relative jar path with current_dir: the JRE itself still fails to load java.dll.
* 8.3 short paths: the launcher starts, but the JVM expands java.home to the long name and fails loading net.dll.
* Copying the JRE to an ASCII folder: works but costs ~100 MB per version; a junction is instant and follows updates.
* `cmd /c mklink /J`: user names may contain % and &, which cmd interprets; the `junction` crate avoids the shell.
## Watch out
* Uninstall leaves `%ProgramData%\CPI Workbench <SID>` (junction + CDS archives). The unit test `junction_reaches_a_non_ascii_folder` also leaves that folder behind on the machine running it.
* -XX:ArchiveClassesAtExit with a non-ANSI path makes the JVM exit with code 1 at shutdown; on Windows CDS is only used with ASCII cache paths.
