// The Flow Steps tab's side of the parity fixtures (testdata/flow-parity): each step run must make exactly the
// engine calls the fixture lists. The MCP server replays the same files (flow_parity in app/src-tauri/src/mcp/tests.rs).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import { runStep, settingsFromIflowStep, type FlowRun } from '../src/lib/flowcalls.ts';

const dir = new URL('../../testdata/flow-parity/', import.meta.url);
const files = readdirSync(dir).filter((f) => f.endsWith('.json'));

test('there are parity fixtures', () => assert.ok(files.length >= 10, files.join(', ')));

for (const file of files) {
  test(`flow parity: ${file}`, async () => {
    const f = JSON.parse(readFileSync(new URL(file, dir), 'utf8'));
    const answers: Record<string, any[]> = structuredClone(f.answers ?? {});
    const calls: any[] = [];
    const run: FlowRun = {
      input: f.input, namespaces: f.namespaces, xml: 'xml' as any, camel: 'camel' as any,
      call: async (method, params, profile) => {
        calls.push({ engine: profile, method, params: JSON.parse(JSON.stringify(params)) });
        return answers[method]?.shift() ?? { ok: true };
      },
    };
    const settings = f.from === 'iflow' ? settingsFromIflowStep(f.step.type, f.step.config) : f.step.config;
    const result = runStep(run, f.step.type, f.step.type === 'gather' ? { ...settings, messages: f.messages } : settings);
    if (f.error !== undefined) await assert.rejects(result, { message: f.error });
    else if (f.result !== undefined) assert.deepEqual(await result, f.result);
    else await result;
    assert.deepEqual(calls, f.calls);
  });
}

test('an iFlow step names the choices it leaves out, which take the default', async () => {
  const { defaultedSettings, settingsFromIflowStep } = await import('../src/lib/flowcalls.ts');
  assert.deepEqual(defaultedSettings('encoder', {}), ['scheme']);
  assert.deepEqual(defaultedSettings('encoder', { scheme: 'gzipCompress' }), []);
  assert.deepEqual(defaultedSettings('messageDigest', { algorithm: 'SHA-256', targetHeader: null }), ['canonicalization', 'targetHeader']);
  assert.deepEqual(defaultedSettings('contentModifier', {}), []);
  assert.equal(settingsFromIflowStep('encoder', {}).scheme, 'base64Encode');
});
