// Unit tests of the update check: node --test tests/
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { checkForUpdate, compareVersions } from '../src/lib/version.ts';

test('compareVersions follows SemVer precedence', () => {
  assert.equal(compareVersions('0.3.0', '0.2.0'), 1);
  assert.equal(compareVersions('v0.2.0', '0.2.0'), 0);
  assert.equal(compareVersions('0.10.0', '0.9.9'), 1, 'numeric, not text');
  assert.equal(compareVersions('1.0.0-rc.1', '1.0.0'), -1);
  assert.equal(compareVersions('1.0.0', '1.0.0-rc.1'), 1);
  assert.equal(compareVersions('1.0.0-alpha', '1.0.0-alpha.1'), -1);
  assert.equal(compareVersions('1.0.0-alpha.beta', '1.0.0-beta'), -1);
  assert.equal(compareVersions('1.0.0-beta.2', '1.0.0-beta.11'), -1);
  assert.equal(compareVersions('1.0.0-rc.1', '1.0.0-beta.11'), 1);
  assert.equal(compareVersions('1.0.0+build.5', '1.0.0'), 0);
  assert.throws(() => compareVersions('latest', '1.0.0'), /Not a version/);
});

const answer = (status: number, body: unknown) => (async () =>
  new Response(JSON.stringify(body), { status, statusText: status === 200 ? 'OK' : 'Forbidden' })) as unknown as typeof fetch;

test('checkForUpdate links to a newer release only', async () => {
  const url = 'https://github.com/jsevenheck/cpi-workbench/releases/tag/v0.3.0';
  assert.deepEqual(await checkForUpdate('0.2.0', answer(200, { tag_name: 'v0.3.0', html_url: url })), { newer: true, latest: '0.3.0', url });
  assert.deepEqual(await checkForUpdate('0.3.0', answer(200, { tag_name: 'v0.3.0', html_url: url })), { newer: false, latest: '0.3.0' });
  const elsewhere = await checkForUpdate('0.2.0', answer(200, { tag_name: 'v0.3.0', html_url: 'https://example.invalid/x' }));
  assert.equal(elsewhere.newer && elsewhere.url, 'https://github.com/jsevenheck/cpi-workbench/releases/', 'only GitHub release pages');
  await assert.rejects(checkForUpdate('0.2.0', answer(403, {})), /GitHub answered 403 Forbidden/);
});
