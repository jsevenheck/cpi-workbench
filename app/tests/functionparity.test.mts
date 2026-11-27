// The Groovy tab's side of the function-list parity fixture (testdata/functions-parity). The MCP server replays the
// same file (functions_parity in app/src-tauri/src/mcp/functions.rs).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { scriptFunctions } from '../src/lib/functions.ts';

const f = JSON.parse(readFileSync(new URL('../../testdata/functions-parity/cases.json', import.meta.url), 'utf8'));

for (const c of f.cases) {
  test(`function parity: ${c.name}`, () => assert.deepEqual(scriptFunctions(c.script), c.expected));
}
