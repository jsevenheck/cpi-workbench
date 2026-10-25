// Unit tests of test-case rows: node --test tests/
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { toRows } from '../src/lib/rows.ts';

test('toRows types shorthand values like the engine (TypedValue.readMap)', () => {
  assert.deepEqual(toRows({ Flag: true, Count: 5, Name: 'x', Empty: null, Typed: { value: '7', type: 'Integer' } }), [
    { name: 'Flag', value: 'true', type: 'Boolean' },
    { name: 'Count', value: '5', type: 'String' },
    { name: 'Name', value: 'x', type: 'String' },
    { name: 'Empty', value: '', type: 'null' },
    { name: 'Typed', value: '7', type: 'Integer' },
  ]);
  assert.deepEqual(toRows([{ name: 'a', value: '1', type: 'Long', disabled: true }]),
    [{ name: 'a', value: '1', type: 'Long', disabled: true }]);
  assert.deepEqual(toRows(undefined), []);
});
