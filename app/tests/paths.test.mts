// Unit tests of path comparison: node --test tests/
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { pathKey, projectPath } from '../src/lib/paths.ts';

test('pathKey gives every spelling of a project path the same key', () => {
  const key = pathKey('scripts/new-script.groovy');
  for (const p of ['scripts/./new-script.groovy', 'scripts//new-script.groovy', 'scripts\\new-script.groovy',
    './Scripts/New-Script.groovy', 'mappings/../scripts/new-script.groovy', '/scripts/new-script.groovy']) {
    assert.equal(pathKey(p), key, p);
  }
  assert.notEqual(pathKey('scripts/other.groovy'), key);
});

test('projectPath: a case-relative file as a project path, keeping ".." above the root', () => {
  assert.equal(projectPath('tests/case', 'note.txt'), 'tests/case/note.txt');
  assert.equal(projectPath('tests/case', '../../inputs/./a.pdf'), 'inputs/a.pdf');
  // Above the project root the ".." stay, so the engine refuses the file instead of reading another one.
  assert.equal(projectPath('tests/case', '../../../etc/passwd'), '../etc/passwd');
  assert.equal(projectPath('', 'a/b'), 'a/b');
});
