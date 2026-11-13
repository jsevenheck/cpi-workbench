// Unit tests of the platform data tables and the script's further inputs: node --test tests/
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { attachmentInput, attachmentRows, newPlatformRow, parsePlatformFile, partnerExtras, platformDoc, platformFileText, platformRows,
  platformRunData, PLATFORM_FILES, PLATFORM_KEYS, soapHeaderInput, soapHeaderRows } from '../src/lib/platform.ts';

const project = new URL('../../testdata/regression-project/', import.meta.url);

test('the regression project\'s platform files survive rows and back unchanged', () => {
  for (const key of PLATFORM_KEYS) {
    const file = new URL(PLATFORM_FILES[key], project);
    if (!fs.existsSync(file)) continue;
    const text = fs.readFileSync(file, 'utf8');
    const doc = parsePlatformFile(text);
    const saved = platformFileText(platformDoc(key, platformRows(key, doc), doc));
    assert.deepEqual(JSON.parse(saved), JSON.parse(text), key);
  }
});

test('numbers keep their text in the file and are numbers in a run', () => {
  const text = '{ "ORDER": { "next": 1, "max": 99999999999999999999, "step": 1.50, "note": "7 of 9, -1", "q": "a \\"1\\" b\\\\" },\n  "T": { "next": 5e3 } }';
  const doc = parsePlatformFile(text);
  const rows = platformRows('numberRanges', doc);
  assert.equal(rows[0].max, '99999999999999999999');
  rows[0].next = '0042';
  const saved = platformFileText(platformDoc('numberRanges', rows, doc));
  assert.ok(saved.includes('"max": 99999999999999999999') && saved.includes('"step": 1.50') && saved.includes('"next": 42'), saved);
  assert.ok(saved.includes('"note": "7 of 9, -1"') && saved.includes('"next": 5e3'), saved);
  assert.deepEqual(platformRunData(platformDoc('numberRanges', rows, doc)),
    { ORDER: { next: 42, max: 1e20, rotate: false, step: 1.5, note: '7 of 9, -1', q: 'a "1" b\\' }, T: { next: 5000, rotate: false } });
  assert.throws(() => parsePlatformFile('{ "a": 1, }'), SyntaxError);
});

test('value mappings: rows in order, unknown members kept, empty rows left out', () => {
  const doc = [{ sourceAgency: 'A', sourceIdentifier: 'i', sourceValue: '1', targetAgency: 'B', targetIdentifier: 'j', targetValue: 'x', note: 'keep' }];
  const rows = platformRows('valueMappings', doc);
  assert.equal(rows[0].targetValue, 'x');
  rows.push(newPlatformRow('valueMappings'));
  assert.deepEqual(platformDoc('valueMappings', rows, doc), doc);
  assert.throws(() => platformRows('valueMappings', {}), /expected a list/);
  assert.deepEqual(platformRows('valueMappings', undefined), []);
});

test('credentials: OAuth fields and properties stay, an alias can be renamed', () => {
  const doc = { ERP: { username: 'u', password: 'p', properties: { k: 'v' } }, TOKEN: { accessToken: 't', user: 'x' } };
  const rows = platformRows('credentials', doc);
  assert.deepEqual(rows.map((r) => [r.alias, r.username, r.password]), [['ERP', 'u', 'p'], ['TOKEN', '', '']]);
  rows[0].alias = 'S4';
  rows[0].password = 'q';
  assert.deepEqual(platformDoc('credentials', rows, doc), {
    S4: { username: 'u', password: 'q', properties: { k: 'v' } }, TOKEN: { accessToken: 't', user: 'x' } });
});

test('partner directory: binary parameters and the partners\' other data are kept', () => {
  const doc = { partners: {
    P1: { parameters: { URL: 'https://example.invalid', CERT: { contentType: 'crt', file: 'p1.crt' } }, alternativePartners: [{ agency: 'a', scheme: 's', id: '1' }] },
    P2: { parameters: { X: '1' } },
    P3: { authorizedUsers: ['u'] },
  } };
  const rows = platformRows('partnerDirectory', doc);
  assert.deepEqual(rows.map((r) => [r.partner, r.name, r.value, !!r.binary]),
    [['P1', 'URL', 'https://example.invalid', false], ['P1', 'CERT', '', true], ['P2', 'X', '1', false]]);
  assert.deepEqual(partnerExtras(doc), [{ partner: 'P1', alternativePartners: 1, authorizedUsers: 0 }, { partner: 'P3', alternativePartners: 0, authorizedUsers: 1 }]);
  // P2's only row goes, a new partner comes: P2 leaves the file, P1 and P3 keep what the table does not show.
  const next = [...rows.slice(0, 2), { partner: 'P4', name: 'Y', value: '2', binary: null }];
  assert.deepEqual(platformDoc('partnerDirectory', next, doc), { partners: {
    P1: { parameters: { URL: 'https://example.invalid', CERT: { contentType: 'crt', file: 'p1.crt' } }, alternativePartners: [{ agency: 'a', scheme: 's', id: '1' }] },
    P3: { parameters: {}, authorizedUsers: ['u'] },
    P4: { parameters: { Y: '2' } },
  } });
});

test('number ranges: numbers are numbers in the file, a wrong one is an error', () => {
  const doc = { ORDER: { next: 1, min: 1, max: 999999, fieldLength: 6, rotate: false } };
  const rows = platformRows('numberRanges', doc);
  assert.equal(rows[0].max, '999999');
  assert.deepEqual(platformRunData(platformDoc('numberRanges', rows, doc)), doc);
  rows[0].fieldLength = '';
  rows[0].rotate = true;
  assert.deepEqual(platformRunData(platformDoc('numberRanges', rows, doc)), { ORDER: { next: 1, min: 1, max: 999999, rotate: true } });
  rows[0].next = 'x';
  assert.throws(() => platformDoc('numberRanges', rows, doc), /ORDER: next must be a whole number/);
  assert.equal(platformFileText({ a: 1 }), '{\n  "a": 1\n}\n');
});

test('attachments and SOAP headers: a case\'s entries as rows and back as a run\'s input', () => {
  const rows = attachmentRows([{ file: 'note.pdf', contentType: 'application/pdf' }, { name: 'a.txt', text: 'hi' }, { name: 'b', base64: 'aGk=' }],
    (f) => 'tests/case/' + f);
  assert.deepEqual(rows.map((r) => [r.kind, r.file]), [['file', 'tests/case/note.pdf'], ['text', ''], ['base64', '']]);
  rows.push({ name: '', contentType: '', kind: 'text', text: '', file: '', base64: '' });
  assert.deepEqual(attachmentInput(rows), [
    { contentType: 'application/pdf', file: 'tests/case/note.pdf' }, { name: 'a.txt', text: 'hi' }, { name: 'b', base64: 'aGk=' }]);
  const soap = soapHeaderRows([{ name: '{urn:ns}Local', value: '<Local xmlns="urn:ns"/>', mustUnderstand: true }]);
  soap.push({ name: '', value: '', mustUnderstand: false, actor: '' });
  assert.deepEqual(soapHeaderInput(soap), [{ name: '{urn:ns}Local', mustUnderstand: true, value: '<Local xmlns="urn:ns"/>' }]);
});
