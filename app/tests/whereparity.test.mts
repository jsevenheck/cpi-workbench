// The Where used tab's side of the parity fixture (testdata/whereused-parity): the overview, parameter matches,
// resolved {{parameters}} and table rows. The MCP server replays the same file (whereused_parity in
// app/src-tauri/src/mcp/whereused.rs).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { inventory, matchingParameters, resolveParameters, tableHit } from '../src/lib/whereused.ts';

const f = JSON.parse(readFileSync(new URL('../../testdata/whereused-parity/cases.json', import.meta.url), 'utf8'));

test('where-used parity: overview', () => assert.deepEqual(inventory(f.index), f.overview));

test('where-used parity: parameters', () => {
  for (const c of f.parameters) {
    assert.deepEqual(matchingParameters(f.index, c.query).map((i) => [i.iflow, i.parameters.map((p) => p.name)]), c.expected, c.query);
  }
});

test('where-used parity: resolved parameters', () => {
  for (const c of f.resolve) assert.equal(resolveParameters(c.value, new Map(Object.entries(c.params))), c.expected, c.value);
});

test('where-used parity: table rows', () => {
  for (const c of f.tables) assert.deepEqual(tableHit(c.value, c.query), c.expected, c.value);
});
