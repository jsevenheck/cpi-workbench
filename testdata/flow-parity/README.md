# Flow Steps parity fixtures

Each file is one flow step run and the engine calls it must make. The Flow Steps tab
(`app/src/lib/flowcalls.ts`, test `app/tests/flowparity.test.mts`) and the MCP server
(`app/src-tauri/src/mcp/flow.rs`, test `flow_parity` in `mcp/tests.rs`) replay every file with a recording
engine and must make exactly these calls, so the two cannot drift apart. No engine runs.

| Key | Meaning |
|---|---|
| `step` | `{ "type", "config" }`, the step's settings |
| `from` | `iflow`: `config` is what `iflow.steps` returns for an iFlow step (keys may be missing, lists are arrays); `tab`: `config` is the tab's settings as a user leaves them (all keys) |
| `input`, `namespaces` | the message and the XPath namespaces of the run |
| `messages` | a Gather's messages |
| `answers` | per method, the engine's answers in call order; a call without one gets `{ "ok": true }` |
| `calls` | the expected calls in order: `engine` (`xml` or `camel`), `method`, `params` |
| `result`, `diagnostics` | optional: the whole expected result, or only its `diagnostics` |

Results are compared only where a fixture gives `result` or `diagnostics`: the MCP server may add fields the tab does not need.
