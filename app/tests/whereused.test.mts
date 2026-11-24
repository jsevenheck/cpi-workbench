import { test } from 'node:test';
import assert from 'node:assert/strict';
import { groupHits, highlight, type IndexEntry } from '../src/lib/whereused.ts';

const hit = (iflow: string, element: string, key: string, value: string): IndexEntry =>
  ({ iflow, element, node: element + '-node', name: element === 'MF' ? null : element.toUpperCase(), type: 'step', label: 'Script', key, value, kind: null });

test('hits are grouped by iFlow, then element, in the order found', () => {
  const g = groupHits([hit('A', 's1', 'k1', 'x'), hit('B', 's2', 'k', 'x'), hit('A', 's1', 'k2', 'x'), hit('A', 'MF', 'address', 'x')]);
  assert.deepEqual(g.map((x) => x.iflow), ['A', 'B']);
  assert.deepEqual(g[0].elements.map((e) => [e.element, e.name, e.node, e.hits.map((h) => h.key)]),
    [['s1', 'S1', 's1-node', ['k1', 'k2']], ['MF', 'MF', 'MF-node', ['address']]]);
});

test('highlight marks every match ignoring case, and nothing for an empty query', () => {
  assert.deepEqual(highlight('sftp://Host/host', 'host'),
    [{ text: 'sftp://', match: false }, { text: 'Host', match: true }, { text: '/', match: false }, { text: 'host', match: true }]);
  assert.deepEqual(highlight('abc', ' '), [{ text: 'abc', match: false }]);
  // 'İ' lower-cases to two units; the mark must still cover 'host', not 'ost'.
  assert.deepEqual(highlight('İhost', 'host'), [{ text: 'İ', match: false }, { text: 'host', match: true }]);
  assert.deepEqual(highlight('İx', 'i'), [{ text: 'İ', match: true }, { text: 'x', match: false }]);
});

test('a diagram value is searched whole, a cut one up to the cut, a skipped key not at all', async () => {
  const { whereUsedQuery } = await import('../src/lib/whereused.ts');
  assert.equal(whereUsedQuery('address', 'https://x'), 'https://x');
  const cut = 'a'.repeat(2000) + '…';
  assert.equal(whereUsedQuery('table', cut), 'a'.repeat(2000));
  assert.equal(whereUsedQuery('note', 'and so on…'), 'and so on…');
  assert.equal(whereUsedQuery('componentNS', 'sap'), null);
  assert.equal(whereUsedQuery('address', ''), null);
});

test('the overview counts each distinct value per kind, most used first, and leaves out unclassified settings', async () => {
  const { inventory } = await import('../src/lib/whereused.ts');
  const e = (kind: string | null, value: string): IndexEntry => ({ ...hit('A', 's', 'k', value), kind });
  const index = [
    { iflow: 'A', name: null, file: 'a', error: null, parameters: [], entries: [e('address', 'https://b'), e('address', 'https://a'), e(null, 'x'), e('credential', 'CRED')] },
    { iflow: 'B', name: null, file: 'b', error: null, parameters: [], entries: [e('address', 'https://a'), e('queue', '')] },
  ];
  assert.deepEqual(inventory(index), [
    { kind: 'address', label: 'Address', values: [{ value: 'https://a', count: 2, resolved: [] }, { value: 'https://b', count: 1, resolved: [] }] },
    { kind: 'credential', label: 'Credential', values: [{ value: 'CRED', count: 1, resolved: [] }] },
  ]);
});

test('parameters resolve per iFlow, and shared iFlow names get their folder', async () => {
  const { inventory, resolveParameters, iflowLabels } = await import('../src/lib/whereused.ts');
  const p = (name: string, value: string | null) => ({ name, value, type: null, required: null, description: null, uses: [], unused: false, unresolved: value == null });
  const e: IndexEntry = { ...hit('A', 's', 'address', 'https://{{Host}}/{{Path}}'), kind: 'address' };
  const index = [
    { iflow: 'A', name: 'X', file: 'a', error: null, entries: [e], parameters: [p('Host', 'a.example'), p('Path', null)] },
    { iflow: 'B', name: 'X', file: 'b', error: null, entries: [{ ...e, iflow: 'B' }], parameters: [p('Host', 'b.example')] },
    { iflow: 'C', name: null, file: 'c', error: null, entries: [{ ...e, iflow: 'C' }], parameters: [] },
  ];
  assert.deepEqual(inventory(index)[0].values, [{ value: 'https://{{Host}}/{{Path}}', count: 3,
    resolved: ['https://a.example/{{Path}}', 'https://b.example/{{Path}}'] }]);
  assert.equal(resolveParameters('plain', new Map([['Host', 'x']])), null);
  assert.deepEqual([...iflowLabels([{ iflow: 'A', name: 'X' }, { iflow: 'B', name: 'X' }, { iflow: 'C', name: null }, { iflow: 'D', name: 'Y' }])],
    [['A', 'X · A'], ['B', 'X · B'], ['C', 'C'], ['D', 'Y']]);
});
