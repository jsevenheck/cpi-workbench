// The Diagram tab's side of the iFlow model parity fixture (testdata/iflowmodel-parity): outline, adapters and type
// labels. The MCP server replays the same file (iflowmodel_parity in app/src-tauri/src/mcp/iflowmodel.rs).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { adapters, outline, typeLabel } from '../src/lib/iflowmodel.ts';

const f = JSON.parse(readFileSync(new URL('../../testdata/iflowmodel-parity/cases.json', import.meta.url), 'utf8'));

for (const c of f.cases) {
  test(`iFlow model parity: ${c.model.iflow}`, () => {
    // As JSON: a route without `default` has it undefined, which the fixture cannot hold.
    const json = (v: unknown) => JSON.parse(JSON.stringify(v));
    assert.deepEqual(json(outline(c.model).map((g) => ({ process: g.process.id, entries: g.entries.map((e) => ({ node: e.node.id,
      depth: e.depth, route: e.route ?? null, again: !!e.again, unreachable: !!e.unreachable })) }))), c.outline);
    assert.deepEqual(adapters(c.model).map((a) => ({ flow: a.flow.id, system: a.system?.id ?? null, inbound: a.inbound })), c.adapters);
    assert.deepEqual(Object.fromEntries((c.model.nodes ?? []).map((n: any) => [n.id, typeLabel(n)])), c.labels);
  });
}
