// Which engines a Flow Steps configuration needs, and the message when one is missing (before anything is called).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { flowRequirements, flowStepDefaults, missingFlowRuntime, simpleCalls } from '../src/lib/flowcalls.ts';

const settings = () => flowStepDefaults();

test('steps without Camel Simple need only the XML engine', () => {
  for (const step of ['encoder', 'decoder', 'messageDigest', 'gather'] as const) {
    assert.deepEqual(flowRequirements(step, settings()), { xml: true, camel: false }, step);
  }
  assert.deepEqual(flowRequirements('filter', settings()), { xml: true, camel: false });
  assert.deepEqual(flowRequirements('splitter', settings()), { xml: true, camel: false });
  assert.deepEqual(flowRequirements('converter', settings()), { xml: true, camel: false });
});

test('the Content Modifier needs Camel, and the XML engine only for an XPath row that creates', () => {
  const s = settings();
  assert.deepEqual(flowRequirements('contentModifier', s), { xml: false, camel: true });
  s.cm.headers.push({ action: 'Create', name: 'h', type: 'xpath', value: '/a', dataType: '' } as any);
  assert.deepEqual(flowRequirements('contentModifier', s), { xml: true, camel: true });
  s.cm.headers[0].action = 'Delete' as any;
  assert.deepEqual(flowRequirements('contentModifier', s), { xml: false, camel: true });
});

test('a Router needs the engine of its condition type; mixed or no conditions call none', () => {
  const s = settings();
  assert.deepEqual(flowRequirements('router', s), { xml: false, camel: false });
  s.router.routes = [{ expressionType: 'xml' }, { default: true, expressionType: 'nonxml' }] as any;
  assert.deepEqual(flowRequirements('router', s), { xml: true, camel: false });
  s.router.routes = [{ expressionType: 'nonxml' }] as any;
  assert.deepEqual(flowRequirements('router', s), { xml: false, camel: true });
  s.router.routes = [{ expressionType: 'xml' }, { expressionType: 'nonxml' }] as any;
  assert.deepEqual(flowRequirements('router', s), { xml: false, camel: false });
});

test('Filter, Splitter and CSV to XML need Camel only for Simple expressions', () => {
  const s = settings();
  s.filter.expression = "//a[. = function:simple('${header.x}')]";
  assert.deepEqual(flowRequirements('filter', s), { xml: true, camel: true });
  s.filter.expression = "//a[. = 'function:simple(''${header.x}'')'] (: function:simple('${header.y}') :)";
  assert.deepEqual(flowRequirements('filter', s), { xml: true, camel: false });
  s.splitter.grouping = '${property.n}';
  assert.deepEqual(flowRequirements('splitter', s), { xml: true, camel: true });
  s.converter.conversion = 'csvToXml' as any;
  s.converter.recordMarker = '${header.m}';
  assert.deepEqual(flowRequirements('converter', s), { xml: true, camel: true });
  s.converter.conversion = 'xmlToJson' as any;
  assert.deepEqual(flowRequirements('converter', s), { xml: true, camel: false });
});

test('a missing engine is named before the step runs', () => {
  const s = settings();
  const all = { xml: true, camel: true };
  assert.equal(missingFlowRuntime('encoder', s, all), '');
  // Groovy engines only: an XML step names the Saxon engine.
  assert.equal(missingFlowRuntime('encoder', s, { xml: false, camel: true }),
    'This step configuration requires a Saxon XML engine. Install or start the missing engine.');
  assert.equal(missingFlowRuntime('contentModifier', s, { xml: false, camel: true }), '');
  // XML engines only.
  assert.equal(missingFlowRuntime('contentModifier', s, { xml: true, camel: false }),
    'This step configuration requires a Groovy engine (Camel). Install or start the missing engine.');
  s.filter.expression = "function:simple('${header.x}')";
  assert.equal(missingFlowRuntime('filter', s, { xml: false, camel: false }),
    'This step configuration requires a Saxon XML engine and a Groovy engine (Camel). Install or start the missing engine.');
});

test('function:simple calls are found outside string literals and comments only', () => {
  const found = (x: string) => simpleCalls(x).map((c) => [x.slice(c.start, c.end), c.expression]);
  assert.deepEqual(found(`a[. = function:simple( "\${h}" )]`), [[`function:simple( "\${h}" )`, '${h}']]);
  assert.deepEqual(found(`a[. = 'it''s function:simple("x")']`), []);
  assert.deepEqual(found(`a[. = "say ""function:simple('x')"""]`), []);
  assert.deepEqual(found(`(: (: function:simple('x') :) function:simple('y') :) function:simple('z')`), [["function:simple('z')", 'z']]);
  // A quote inside a comment does not open a literal; a comment marker inside a literal is text.
  assert.deepEqual(found(`(: ' :) function:simple('a') '(:' function:simple('b')`), [["function:simple('a')", 'a'], ["function:simple('b')", 'b']]);
  assert.deepEqual(found('function:simple(oops) function:simple("x"'), []);
});
