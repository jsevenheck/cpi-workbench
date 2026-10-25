// Unit tests of path comparison: node --test tests/
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { pathKey } from '../src/lib/paths.ts';

test('pathKey gives every spelling of a project path the same key', () => {
  const key = pathKey('scripts/new-script.groovy');
  for (const p of ['scripts/./new-script.groovy', 'scripts//new-script.groovy', 'scripts\\new-script.groovy',
    './Scripts/New-Script.groovy', 'mappings/../scripts/new-script.groovy', '/scripts/new-script.groovy']) {
    assert.equal(pathKey(p), key, p);
  }
  assert.notEqual(pathKey('scripts/other.groovy'), key);
});
