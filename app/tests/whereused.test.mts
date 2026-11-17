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
