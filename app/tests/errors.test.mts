import { test } from 'node:test';
import assert from 'node:assert/strict';

import { isExistsConflict, isProjectUnavailable } from '../src/lib/errors.ts';

test('a create-new conflict is recognised on both transports', () => {
  // Desktop: the message only. Browser mode: the message and the exception class.
  assert.equal(isExistsConflict(new Error('exists already: mappings/a.xsl')), true);
  assert.equal(isExistsConflict({ message: 'exists already: mappings\\a.xsl', type: 'IllegalArgumentException' }), true);
  assert.equal(isExistsConflict({ message: 'C:\\p\\mappings\\a.xsl', type: 'FileAlreadyExistsException' }), true);
  assert.equal(isExistsConflict({ message: 'anything', code: 'exists' }), true);
  for (const other of ['Access denied: a.xsl', 'the file exists already: a.xsl', '', undefined]) {
    assert.equal(isExistsConflict(new Error(other)), false, String(other));
  }
  assert.equal(isExistsConflict(null), false);
});

test('a deleted or renamed project folder is recognised', () => {
  assert.equal(isProjectUnavailable(new Error('Project folder is unavailable: C:\\gone. Reopen the project to continue.')), true);
  assert.equal(isProjectUnavailable({ message: 'x', code: 'projectUnavailable' }), true);
  assert.equal(isProjectUnavailable(new Error('No response from the local engine.')), false);
});
