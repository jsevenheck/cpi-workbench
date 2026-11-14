// Unit tests of the Groovy tab's function list read from the script text: node --test tests/
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { maskCode, scriptFunctions } from '../src/lib/functions.ts';

const MAPPING = `import com.sap.it.api.mapping.MappingContext
import com.sap.it.api.mapping.Output

// def String commented(String a) {
def String getHeader(String headerName, MappingContext context) {
    return context.getHeader(headerName)
}

def String setProperty(String name, String value,
                       MappingContext context) {
    if (value) { context.setProperty(name, value) }
    return value
}

/* All values of a context */
def void countPerContext(String[] values, Output output, MappingContext context) {
    output.addValue(values.count { !output.isSuppress(it) } as String)
}
`;

test('mapping functions with their parameter names and the engine signature', () => {
  const fns = scriptFunctions(MAPPING);
  assert.deepEqual(fns.map((f) => f.signature), [
    'getHeader(String, MappingContext)', 'setProperty(String, String, MappingContext)',
    'countPerContext(String[], Output, MappingContext)']);
  assert.ok(fns.every((f) => f.kind === 'mapping'));
  assert.deepEqual(fns[2].params.map((p) => [p.name, p.kind]), [['values', 'queue'], ['output', 'output'], ['context', 'context']]);
  assert.deepEqual(fns[1].params.map((p) => p.name), ['name', 'value', 'context'], 'a parameter list over two lines');
});

test('script steps, one-liners and what is not a function', () => {
  const src = `import com.sap.gateway.ip.core.customdev.util.Message
def Message processData(Message message) { message }
def enrich(msg) {
  def s = "def String fake(String x) {"
  if (s) { return msg }
}
private String helper(String a, String b) { a + b }
static String util(String a, String b) { a }
class Inner { String inside(String a, String b) { a } }
def String upper(String v) { v.toUpperCase() }
def Map lookup(Map m, String key) { m[key] }
`;
  const fns = scriptFunctions(src);
  assert.deepEqual(fns.map((f) => [f.name, f.kind]), [
    ['processData', 'step'], ['enrich', 'step'], ['upper', 'mapping'], ['lookup', null]]);
});

test('untyped and Object parameters are single values, Object[] a context, as in the engine', () => {
  const [a, b] = scriptFunctions('def join(a, b) { a + b }\ndef String all(Object[] v, Output o) { }\n');
  assert.equal(a.signature, 'join(String, String)');
  assert.equal(a.kind, 'mapping');
  assert.equal(b.signature, 'all(String[], Output)');
});

test('maskCode keeps offsets and blanks comments and strings', () => {
  const src = 'a // b\n"c\\"d" /* e\nf */ \'\'\'g\nh\'\'\' i';
  const m = maskCode(src);
  assert.equal(m.length, src.length);
  assert.equal(m.split('\n').length, src.split('\n').length);
  assert.ok(!/[bcdefgh]/.test(m) && m.includes('a') && m.includes('i'));
});
