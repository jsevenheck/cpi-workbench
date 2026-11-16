// Writes the simulator's baseline for this probe (../baseline-simulator/encoders) through the MCP server: each
// step that is simulated, then probe-report.groovy on its message, as the probe iFlow does on a tenant.
//   node probes/encoders/baseline.mjs <cpi-workbench binary> <repository folder>
import { spawn } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import readline from 'node:readline';
const [bin, repo] = process.argv.slice(2).map((p) => path.resolve(p));
const probes = path.join(repo, 'probes'), out = path.join(probes, 'baseline-simulator', 'encoders');
fs.mkdirSync(out, { recursive: true });
const child = spawn(bin, ['mcp', '--project', probes], { stdio: ['pipe', 'pipe', 'inherit'] });
const waiting = new Map();
readline.createInterface({ input: child.stdout }).on('line', (l) => { const m = JSON.parse(l); waiting.get(m.id)?.(m); waiting.delete(m.id); });
let id = 1;
const request = (method, params) => new Promise((r) => { waiting.set(id, r); child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id: id++, method, params }) + '\n'); });
await request('initialize', { protocolVersion: '2025-06-18', capabilities: {}, clientInfo: { name: 'baseline', version: '1' } });
child.stdin.write(JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }) + '\n');
async function tool(name, args) {
  const r = await request('tools/call', { name, arguments: args });
  const text = r.result?.content?.[0]?.text ?? JSON.stringify(r);
  try { return JSON.parse(text); } catch { return { ok: false, raw: text }; }
}
const script = fs.readFileSync(path.join(probes, 'encoders', 'probe-report.groovy'), 'utf8');
const body = (f) => fs.readFileSync(path.join(probes, 'encoders', f), 'utf8');
const ns = { o: 'urn:example:order' };
const cases = [
  ['base64-encode.long', 'base64-encode', 'long.xml', { type: 'encoder', config: { scheme: 'base64Encode' } }],
  ['base64-encode.short', 'base64-encode', 'short.txt', { type: 'encoder', config: { scheme: 'base64Encode' } }],
  ['digest-c14n', 'digest-c14n', 'digest.xml', { type: 'messageDigest', config: { canonicalization: 'c14n10', algorithm: 'SHA256', targetHeader: 'ProbeDigest' } }],
  ['digest-c14n.not-xml', 'digest-c14n', 'not-xml.txt', { type: 'messageDigest', config: { canonicalization: 'c14n10', algorithm: 'SHA256', targetHeader: 'ProbeDigest' } }],
  ['digest-none', 'digest-none', 'digest.xml', { type: 'messageDigest', config: { canonicalization: 'none', algorithm: 'MD5' } }],
  ['digest-none.not-xml', 'digest-none', 'not-xml.txt', { type: 'messageDigest', config: { canonicalization: 'none', algorithm: 'MD5' } }],
  ['digest-filter', 'digest-filter', 'digest.xml', { type: 'messageDigest', config: { canonicalization: 'exclusive', algorithm: 'SHA1', filter: '/o:Order/o:Header' } }],
  ['digest-filter-many', 'digest-filter-many', 'digest.xml', { type: 'messageDigest', config: { canonicalization: 'exclusive', algorithm: 'SHA1', filter: '/o:Order/o:Item' } }],
];
for (const [name, probeStep, file, step] of cases) {
  const r = await tool('run_flow_step', { body: body(file), headers: { ProbeStep: probeStep }, namespaces: ns, step });
  const errors = (r.diagnostics ?? []).filter((d) => d.severity === 'error').map((d) => d.message);
  if (r.ok === false || !r.runId) {
    fs.writeFileSync(path.join(out, name + '.error.txt'), (errors.join('\n') || JSON.stringify(r)) + '\n');
    console.log(name, '-> error');
    continue;
  }
  const g = await tool('run_groovy', { runtime: 'groovy-1.x', messageFrom: r.runId, script });
  if (!g.ok) { console.error(name, JSON.stringify(g).slice(0, 400)); process.exit(1); }
  let text = g.output?.body;
  if (text === undefined) text = (await tool('read_file', { runId: g.runId })).content;
  fs.writeFileSync(path.join(out, name + '.json'), text + '\n');
  // What the digest is of, to try out explanations when a tenant's digest differs.
  if (r.canonical !== undefined) fs.writeFileSync(path.join(out, name + '.canonical.xml'), r.canonical);
  console.log(name, '-> ok', r.canonical === undefined ? '' : '(canonical ' + r.canonical.length + ' chars)');
}
child.kill();
