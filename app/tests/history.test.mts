// Run history of a tab: node --test tests/ (Node 22.18+ runs the TypeScript sources).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { addRun, timeOf, type HistoryEntry } from '../src/lib/history.ts';

type R = { ok: boolean; output?: { body: string } | null };
const run = (at: number, body: string, what = 'mappings/a.xsl', ok = true): HistoryEntry<R> =>
  ({ at, profile: 'xml-he12', what, result: { ok, output: { body } } });

test('a new run goes in front, the oldest drop out', () => {
  let list: HistoryEntry<R>[] = [];
  for (let i = 0; i < 5; i++) list = addRun(list, run(i, 'b' + i), 3);
  assert.deepEqual(list.map((e) => e.result.output?.body), ['b4', 'b3', 'b2']);
});

test('the same outcome again only moves the time of the newest run', () => {
  let list = addRun([], run(1, 'x'));
  list = addRun(list, run(2, 'x'));
  assert.equal(list.length, 1);
  assert.equal(list[0].at, 2);
  list = addRun(list, run(3, 'x', 'mappings/b.xsl'));
  list = addRun(list, run(4, 'x', 'mappings/b.xsl', false));
  assert.equal(list.length, 3, 'another file, then another status, are new runs');
});

test('the time is local hours, minutes and seconds', () => {
  assert.match(timeOf(Date.now()), /^\d\d:\d\d:\d\d$/);
});
