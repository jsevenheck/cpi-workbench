// Unit tests of the editor formatters: node --test tests/ (Node 22.18+ runs the TypeScript sources).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { formatJson } from '../src/lib/json.ts';
import { reindentXml, reindentGroovy, detectUnit } from '../src/lib/indent.ts';

test('formatJson keeps the spelling of numbers and the data', () => {
  const src = '{"a":15.50,"b":[1,2.00,1e3],"s":"x, {y}: \\"q\\" \\\\","e":{},"f":[ ],"big":12345678901234567890}';
  const out = formatJson(src);
  assert.deepEqual(JSON.parse(out), JSON.parse(src));
  for (const n of ['15.50', '2.00', '1e3', '12345678901234567890']) assert.ok(out.includes(n), n);
  assert.ok(out.includes('"e": {}') && out.includes('"f": []'));
  assert.equal(formatJson(out), out);
  assert.throws(() => formatJson('{bad'));
});

test('reindentXml moves structure lines only', () => {
  const src = [
    '<xsl:stylesheet version="3.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">',
    '<xsl:template match="/">',
    '      <out>',
    '  <xsl:text>  keep',
    '     this </xsl:text>',
    '   Text line',
    '     <b/>',
    '<xsl:variable name="v"',
    '     select="concat(\'a',
    '   b\', 1 +',
    '        2)"/>',
    '<!-- a',
    '     comment -->',
    '<lit attr="x',
    '     y"/>',
    '      </out>',
    '</xsl:template>',
    '</xsl:stylesheet>',
  ].join('\n');
  const want = [
    '<xsl:stylesheet version="3.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform">',
    '  <xsl:template match="/">',
    '    <out>',
    '      <xsl:text>  keep',
    '     this </xsl:text>', // inside xsl:text
    '   Text line', // text: its leading whitespace reaches the output
    '     <b/>', // follows text in the same text node
    '      <xsl:variable name="v"',
    '           select="concat(\'a', // moves with its tag
    '   b\', 1 +', // inside an XPath string literal
    '              2)"/>',
    '      <!-- a',
    '     comment -->',
    '      <lit attr="x',
    '     y"/>', // a literal attribute value keeps its spaces
    '    </out>',
    '  </xsl:template>',
    '</xsl:stylesheet>',
  ].join('\n');
  assert.equal(reindentXml(src, '  '), want);
  assert.equal(reindentXml(want, '  '), want);
});

test('reindentXml leaves xml:space="preserve" and CDATA alone', () => {
  const src = '<r xml:space="preserve">\n      <a/>\n</r>\n<s>\n<![CDATA[x]]>\n   <t/>\n</s>';
  assert.equal(reindentXml(src, '  '), src);
});

test('reindentGroovy indents by brackets and keeps strings', () => {
  const src = [
    'def Message processData(Message message) {',
    'def m = [a: 1,',
    'b: "${x["k"]} }"]',
    '  String s = """line {',
    '   keep { this',
    '"""',
    'def r = /a{2}\\/b/',
    '      /**',
    '  * doc',
    '       */',
    'if (m) {',
    'foo({ x ->',
    'bar(x)',
    '})',
    '} else { baz() }',
    'message',
    '.setBody(\'}\')',
    'return message',
    '}',
  ].join('\n');
  const want = [
    'def Message processData(Message message) {',
    '    def m = [a: 1,',
    '        b: "${x["k"]} }"]',
    '    String s = """line {',
    '   keep { this',
    '"""',
    '    def r = /a{2}\\/b/',
    '    /**',
    '     * doc',
    '     */',
    '    if (m) {',
    '        foo({ x ->',
    '            bar(x)',
    '        })',
    '    } else { baz() }',
    '    message',
    '        .setBody(\'}\')',
    '    return message',
    '}',
  ].join('\n');
  assert.equal(reindentGroovy(src, '    '), want);
  assert.equal(reindentGroovy(want, '    '), want);
});

test('detectUnit uses the file\'s own indentation', () => {
  assert.equal(detectUnit('a\n  b\n    c', '    '), '  ');
  assert.equal(detectUnit('a\n\tb', '  '), '\t');
  assert.equal(detectUnit('a', '    '), '    ');
});
