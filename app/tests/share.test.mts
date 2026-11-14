// Unit tests of the hand-over from CPI Helper's plugins: node --test tests/
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readShared, sharedSummary } from '../src/lib/share.ts';

/** The plugins' encoding: JSON, raw DEFLATE, URL-safe Base64 without padding. */
async function encode(data: unknown): Promise<string> {
  const stream = new Blob([JSON.stringify(data)]).stream().pipeThrough(new CompressionStream('deflate-raw'));
  const bytes = new Uint8Array(await new Response(stream).arrayBuffer());
  return Buffer.from(bytes).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

const GROOVY = {
  input: { body: '<Order id="4711">ä€</Order>', headers: { SenderId: 'ERP_100', CamelHttpMethod: 'POST' }, properties: { OrderId: '4711' } },
  script: { code: 'def Message processData(Message message) { message }', function: 'processData' },
};

test('GroovyDebugX: the data after the address, for GroovyIDE and for a custom URL', async () => {
  const token = await encode(GROOVY);
  for (const link of [`https://groovyide.com/cpi/share/v1/${token}`, `http://127.0.0.1:7777/#share/${token}`, token, `  ${token}\n`]) {
    const m = await readShared(link);
    assert.equal(m.kind, 'groovy');
    assert.equal(m.body, GROOVY.input.body, 'non-ASCII text survives');
    assert.deepEqual(m.headers, [{ name: 'SenderId', value: 'ERP_100', type: 'String' }, { name: 'CamelHttpMethod', value: 'POST', type: 'String' }]);
    assert.deepEqual(m.properties.map((r) => r.name), ['OrderId']);
    assert.equal(m.function, 'processData');
    assert.ok(m.source.includes('processData'));
    assert.equal(sharedSummary(m), 'body, 2 headers, 1 property, script');
  }
});

test('xsltDebugX: #share/ with rows of headers and properties; parts left out are empty', async () => {
  const token = await encode({ xml: '<a/>', xslt: '', headers: [{ name: 'h', value: '1' }], properties: [] });
  const m = await readShared(`https://xsltdebugx.pages.dev/#share/${token}`);
  assert.deepEqual([m.kind, m.body, m.source, m.function], ['xslt', '<a/>', '', '']);
  assert.deepEqual(m.headers, [{ name: 'h', value: '1', type: 'String' }]);
  assert.equal(sharedSummary(m), 'body, 1 header');
});

test('a message as plain JSON', async () => {
  const m = await readShared('{ "body": "x", "headers": { "A": "1" } }');
  assert.deepEqual([m.kind, m.body, m.headers.length, m.properties.length], ['message', 'x', 1, 0]);
});

test('what cannot be read says why', async () => {
  await assert.rejects(readShared(''), /Nothing to import/);
  await assert.rejects(readShared('https://ide.contiva.com/cpi/script/debug?data=H4sIAAAA%2B'), /Contiva IDE/);
  await assert.rejects(readShared('https://example.invalid/some page'), /not a link from CPI Helper/);
  await assert.rejects(readShared('https://groovyide.com/cpi/share/v1/AAAAzzzz'), /could not be unpacked|not the JSON/);
  const token = await encode(GROOVY);
  await assert.rejects(readShared(token.slice(0, token.length - 12)), /could not be unpacked|not the JSON/);
  await assert.rejects(readShared('{ "body": '), /not valid JSON/);
  await assert.rejects(readShared(await encode({ other: 1 })), /none of input, script/);
  await assert.rejects(readShared(await encode([1])), /expected an object/);
});
