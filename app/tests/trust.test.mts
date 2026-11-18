import { test } from 'node:test';
import assert from 'node:assert/strict';

import { isLocalEngine } from '../src/lib/trust.ts';

test('browser mode takes engines on this machine only', () => {
  for (const ok of ['http://127.0.0.1:7778', 'http://localhost:7779/', 'http://[::1]:7780']) assert.equal(isLocalEngine(ok), true, ok);
  for (const bad of ['https://attacker.example', 'http://attacker.example:7778', 'http://localhost.attacker.example:7778',
    'http://127.0.0.1@attacker.example:7778', 'http://user:pw@127.0.0.1:7778', 'javascript:alert(1)', '//attacker.example', 'x']) {
    assert.equal(isLocalEngine(bad), false, bad);
  }
});
