// Unit tests of Message Mapping queues: node --test tests/
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { argText, formatQueue, mappingArgs, parseQueue } from '../src/lib/queue.ts';

test('parseQueue reads one context per line or the whole queue', () => {
  assert.deepEqual(parseQueue('["A", "B"]\n\n["C"]\n[]'), [['A', 'B'], ['C'], []]);
  assert.deepEqual(parseQueue('[["A"], ["B", {"suppress": true}]]'), [['A'], ['B', { suppress: true }]]);
  assert.deepEqual(parseQueue('["A"]'), [['A']]);
  assert.deepEqual(parseQueue('  '), [[]]);
  assert.throws(() => parseQueue('A\nB'), /Line 1 is not a JSON array/);
});

test('formatQueue and argText give the same text form', () => {
  const q = [['2'], [{ xsiNil: true as const }], []];
  assert.equal(formatQueue(q), '["2"]\n[{"xsiNil":true}]\n[]');
  assert.deepEqual(parseQueue(formatQueue(q)), q);
  assert.equal(argText([['A'], ['B']]), '["A"]\n["B"]');
  assert.equal(argText(['A', 'B']), '["A","B"]', 'a flat list is one context');
  assert.equal(argText('OrderId'), 'OrderId');
});

test('mappingArgs skips Output and MappingContext and names the bad argument', () => {
  assert.deepEqual(mappingArgs(['String', 'String[]', 'Output', 'MappingContext'], ['x', '["a"]']), ['x', [['a']]]);
  assert.deepEqual(mappingArgs(['String', 'MappingContext'], []), ['']);
  // A null String argument stays null from the test case to the request (not "").
  assert.equal(argText(null), null);
  assert.deepEqual(mappingArgs(['String', 'MappingContext'], [argText(null)]), [null]);
  assert.deepEqual(mappingArgs(['String[]', 'Output'], [null]), [[[]]]);
  assert.throws(() => mappingArgs(['String', 'String[]', 'Output'], ['x', 'oops']), /Argument 2: Line 1/);
});
