---
title: "The MCP server can serve a workspace of projects, and every engine call carries the open project"
author: Claude
date: 2026-11-24
branch: claude/optimistic-dijkstra-ek2iek
cost: { minutes: 16, tokens: 302382 }
id: l-9b82c894
scope: repo
status: active
src: agent
touches:
  - docs/mcp.md
  - app/src-tauri/src/mcp/mod.rs
---

## Decided
* User (2026-11-24): one Claude Desktop entry for many integrations. `--workspace <folder>` serves the folders directly inside it; `open_project` opens one or creates one (create + init, as the app's Import iFlow without a project). project_info lists them under `projects`. `--project` and `--workspace` exclude each other; with `--workspace` CLAUDE_PROJECT_DIR is ignored.
* MCP engine calls now go through `project.request` with `projectRoot` (Engines::call_desktop), like the desktop app. The old `on_start` hook (project.open on each engine start) was removed: switching needs no engine restart and a restarted engine still has the project.
* Tool calls hold a shared lock, open_project an exclusive one, so a running call finishes on the project it started on. runIds are kept across a switch (a message can be carried into another project).

## Tried and ruled out
* open_project with any path: the agent could open and write any folder on the disk; the project boundary of docs/mcp.md would be gone.
* MCP roots: Claude Desktop support was not checked; the user would configure the folders there anyway.

## Watch out
* read_file on a missing file returns only the absolute path as the error (the engine's NoSuchFileException message), which an agent cannot read as "not found". Open finding for the real-client test.
* A shell test that pipes requests and closes stdin at once gets no tool answers: the server drops running requests when stdin closes. Keep stdin open (sleep) when trying the binary by hand.

In the code: app/src-tauri/src/mcp/mod.rs (Folder, parse_args, switching lock), files.rs open_project / workspace_projects, tools.rs engine_call; tests open_project_stays_inside_the_workspace, a_workspace_switches_and_creates_projects.
