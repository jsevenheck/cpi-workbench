// function:simple(...) in a Filter XPath: node --test tests/ (Node 22.18+ runs the TypeScript sources).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { resolveSimpleFunctions } from '../src/lib/simple.ts';

test('values go into the XPath literally, apostrophes doubled', async () => {
  for (const [value, literal] of [['$$', "'$$'"], ['$&', "'$&'"], ['$1', "'$1'"], ["it's $$ $&", "'it''s $$ $&'"]]) {
    assert.equal(await resolveSimpleFunctions("/r/v[. = function:simple('${property.x}')]", async () => value), `/r/v[. = ${literal}]`);
  }
});

test('both quote styles, several calls, each evaluated once in order', async () => {
  const seen: string[] = [];
  const r = await resolveSimpleFunctions(`/a[@x = function:simple( "\${header.a}" )][@y = function:simple('\${header.b}')]`,
    async (e) => { seen.push(e); return e.includes('.a') ? 'A' : 'B'; });
  assert.equal(r, "/a[@x = 'A'][@y = 'B']");
  assert.deepEqual(seen, ['${header.a}', '${header.b}']);
});

test('an XPath without function:simple stays as it is', async () => {
  assert.equal(await resolveSimpleFunctions('/a/b', async () => { throw new Error('not called'); }), '/a/b');
});
