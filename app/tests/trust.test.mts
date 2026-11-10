import { test } from 'node:test';
import assert from 'node:assert/strict';

import { callsJava, isLocalEngine } from '../src/lib/trust.ts';

test('a stylesheet with a java: namespace calls Java', () => {
  assert.equal(callsJava(`<xsl:stylesheet xmlns:rt="java:java.lang.Runtime" version="2.0"/>`), true);
  assert.equal(callsJava(`<xsl:stylesheet xmlns:d = 'java:java.util.Date'/>`), true);
  assert.equal(callsJava(`<xsl:stylesheet xmlns:hci="http://sap.com/it/"><xsl:text>java:x</xsl:text></xsl:stylesheet>`), false);
});

test('browser mode takes engines on this machine only', () => {
  for (const ok of ['http://127.0.0.1:7778', 'http://localhost:7779/', 'http://[::1]:7780']) assert.equal(isLocalEngine(ok), true, ok);
  for (const bad of ['https://attacker.example', 'http://attacker.example:7778', 'http://localhost.attacker.example:7778',
    'http://127.0.0.1@attacker.example:7778', 'http://user:pw@127.0.0.1:7778', 'javascript:alert(1)', '//attacker.example', 'x']) {
    assert.equal(isLocalEngine(bad), false, bad);
  }
});
