---
title: Reject colliding package folders before importing any value mappings
author: Jona
date: 2026-11-16
branch: claude/great-cannon-v2nd6h
id: l-a471ffb0
scope: repo
status: active
src: agent
touches:
  - engine/core/src/main/java/io/cpiwb/core/ArtifactImport.java
  - engine/core/src/test/java/io/cpiwb/core/ArtifactImportTest.java
  - engine/core/src/main/java/io/cpiwb/core/ValueMappingImport.java
---

Decided: ArtifactImport.importPackage preflights the destination folders of all importable folder artifacts before ValueMappingImport.importGroups or any extraction. Reject collisions in both createNew modes rather than merging or assigning suffixes: suffixes would break scriptBundleId references, and overwrite would silently mix artifact contents. Keys use the same sanitization as IflowImport.extract and Locale.ROOT case folding so packages are safe across Windows and case-sensitive filesystems. Tests cover duplicate uniqueId values, distinct names sanitizing to the same folder, case-only collisions, and fallback filenames, with exact before/after snapshots including absent or existing value-mapping tables. Watch out: a symlink regression test with invalid external JSON alone can pass for the wrong reason. Test an empty valid external array too, require the outside-project diagnostic for invalid JSON and non-array JSON, and assert link identity and external bytes remain unchanged. Removing the ValueMappingImport file-level guard made all six external-file cases fail (both createNew modes); restored guard passed all 55 core tests. Use only synthetic temporary fixtures; never inspect a project symlink target to populate a test.
