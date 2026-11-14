// Unit tests of the XPath tab's XML scanner: node --test tests/
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { nodeAt, rangeOf, scanXml, xpathAt } from '../src/lib/xmltext.ts';

const DOC = `<?xml version="1.0"?>
<!DOCTYPE p:a [ <!ENTITY e "<x>"> <!-- > --> ]>
<!-- <not/> -->
<p:a xmlns:p="urn:p" xmlns="urn:d">
  <x a="1 > 0"/>
  <p:b n='1'>t<![CDATA[<no/>]]></p:b>
  <?pi <no/>?>
  <p:b q:n="2" xmlns:q="urn:q"><c/></p:b>
</p:a>`;

test('the engine\'s places are found in the text: elements, attributes, text', () => {
  const scan = scanXml(DOC);
  const text = (r: { start: number; end: number } | null) => (r ? DOC.slice(r.start, r.end) : null);
  assert.equal(text(rangeOf(scan, { path: [1, 2], name: 'p:b' })), `<p:b n='1'>t<![CDATA[<no/>]]></p:b>`);
  assert.equal(text(rangeOf(scan, { path: [1, 3], name: 'p:b', attribute: 'q:n' })), 'q:n="2"');
  assert.equal(text(rangeOf(scan, { path: [1, 2], name: 'p:b', text: true })), 't<![CDATA[<no/>]]>');
  assert.equal(text(rangeOf(scan, { path: [1, 1], name: 'x' })), '<x a="1 > 0"/>');
  assert.equal(text(rangeOf(scan, { path: [1, 3, 1], name: 'c' })), '<c/>');
  // A place the scan does not agree with is not marked.
  assert.equal(rangeOf(scan, { path: [1, 2], name: 'x' }), null);
  assert.equal(rangeOf(scan, { path: [1, 9], name: 'p:b' }), null);
  assert.equal(rangeOf(scan, { path: [1, 1], name: 'x', text: true }), null, 'an empty element has no text');
});

test('the XPath at the cursor, with the Namespace mapping\'s prefixes and positions where needed', () => {
  const scan = scanXml(DOC);
  const at = (s: string, k = 1) => DOC.indexOf(s) + k;
  const ns = { p: 'urn:p', d: 'urn:d', q: 'urn:q' };
  assert.deepEqual(xpathAt(scan, at('<c/>'), ns, '3.1'), { exact: '/p:a/p:b[2]/d:c', general: '/p:a/p:b/d:c', unmapped: [] });
  assert.deepEqual(xpathAt(scan, at('q:n="2"', 2), ns, '3.1'), { exact: '/p:a/p:b[2]/@q:n', general: '/p:a/p:b/@q:n', unmapped: [] });
  assert.deepEqual(xpathAt(scan, at("n='1'"), ns, '1.0'), { exact: '/p:a/p:b[1]/@n', general: '/p:a/p:b/@n', unmapped: [] });
  assert.equal(xpathAt(scan, at('>t<'), ns, '1.0')!.exact, '/p:a/p:b[1]', 'in the content: the element');
  assert.equal(xpathAt(scan, at('xmlns:p'), ns, '1.0')!.exact, '/p:a', 'a namespace declaration is no attribute');
  // Without a prefix for the default namespace: Q{} in 3.1, local-name() in 1.0.
  const noD = { p: 'urn:p' };
  assert.deepEqual(xpathAt(scan, at('<x a'), noD, '3.1'), { exact: '/p:a/Q{urn:d}x', general: '/p:a/Q{urn:d}x', unmapped: ['urn:d'] });
  assert.equal(xpathAt(scan, at('<x a'), noD, '1.0')!.exact, "/p:a/*[local-name()='x' and namespace-uri()='urn:d']");
  assert.equal(xpathAt(scan, 0, ns, '3.1'), null, 'outside the root element');
  assert.equal(nodeAt(scan, DOC.indexOf('<not/>')), null, 'inside a comment before the root');
});

test('text that is not well-formed gives what can be read', () => {
  const scan = scanXml('<a><b>1</b><b');
  assert.equal(scan.elements.length, 3);
  assert.deepEqual(scan.elements.map((e) => e.name), ['a', 'b', 'b']);
  assert.equal(scanXml('not xml').elements.length, 0);
  assert.equal(scanXml('').roots.length, 0);
});
