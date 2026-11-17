// docs/fidelity.md is the app's fidelity indicator (Fidelity.svelte): every row must say what it applies to and on what
// basis, and every step the app shows must have rows.
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { BASES, STEP_IDS, parseFidelity, plainText, rowsFor, weakest } from '../src/lib/fidelity.ts';

const md = readFileSync(new URL('../../docs/fidelity.md', import.meta.url), 'utf8');
const rows = parseFidelity(md);

test('every fidelity row has a known basis and known step ids', () => {
  assert.ok(rows.length >= 40, `${rows.length} rows`);
  for (const r of rows) {
    assert.ok((BASES as readonly string[]).includes(r.basis), `${r.area}: basis '${r.basis}'`);
    assert.ok(r.appliesTo.length > 0, `${r.area}: applies to nothing`);
    for (const id of r.appliesTo) assert.ok((STEP_IDS as readonly string[]).includes(id), `${r.area}: unknown step '${id}'`);
    assert.ok(r.status.length > 0, `${r.area}: no status`);
  }
});

test('every step the app shows has rows with a ranked basis', () => {
  for (const id of STEP_IDS) assert.ok(weakest(rowsFor(rows, id)), `no Tenant / Documented / Assumption row for ${id}`);
});

test('the simulator-only table is not read as CPI fidelity', () => {
  assert.ok(!rows.some((r) => r.section.startsWith('Local workflow')));
});

test('a cell may hold an escaped bar', () => {
  const r = parseFidelity('## S\n\n| Area | Applies to | Basis | Status |\n|---|---|---|---|\n| A | xslt, xpath | Tenant | `a \\|\\| b` |\n');
  assert.deepEqual(r, [{ section: 'S', area: 'A', appliesTo: ['xslt', 'xpath'], basis: 'Tenant', status: '`a || b`' }]);
});

test('weakest ranks Tenant above Documented above Assumption and ignores refusals', () => {
  const row = (basis: string) => ({ section: '', area: '', appliesTo: ['xslt'], basis, status: '' });
  assert.equal(weakest([row('Tenant'), row('Not supported')]), 'Tenant');
  assert.equal(weakest([row('Tenant'), row('Documented')]), 'Documented');
  assert.equal(weakest([row('Assumption'), row('Documented')]), 'Assumption');
  assert.equal(weakest([row('Not supported')]), null);
});

test('plain text keeps link text and drops markup', () => {
  assert.equal(plainText('**to verify** (`Q-V1`), see [SAP](https://help.sap.com) and *Define Router*'),
    'to verify (Q-V1), see SAP and Define Router');
});
