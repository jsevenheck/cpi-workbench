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
const ns = { o: 'urn:example:order' };
const enc = (scheme) => ({ type: 'encoder', config: { scheme } });
const dec = (scheme) => ({ type: 'decoder', config: { scheme } });
const digestC14n = { type: 'messageDigest', config: { canonicalization: 'c14n10', algorithm: 'SHA256', targetHeader: 'ProbeDigest' } };
const digestNone = { type: 'messageDigest', config: { canonicalization: 'none', algorithm: 'MD5' } };
// [baseline name, ProbeStep, request body (encoders/), step, more request headers]
const cases = [
  ['base64-encode.long', 'base64-encode', 'long.xml', enc('base64Encode')],
  ['base64-encode.short', 'base64-encode', 'short.txt', enc('base64Encode')],
  ['base64-decode.oneline', 'base64-decode', 'base64-oneline.txt', dec('base64Decode')],
  ['base64-decode.wrapped', 'base64-decode', 'base64-wrapped.txt', dec('base64Decode')],
  ['base64-decode.invalid', 'base64-decode', 'base64-invalid.txt', dec('base64Decode')],
  ['gzip.long', 'gzip', 'long.xml', enc('gzipCompress')],
  ['gunzip.long', 'gunzip', 'long.xml.gz', dec('gzipDecompress')],
  // Without CamelFileName the entry is named after a message id the simulator makes up, which differs per run;
  // the archive's bytes hold the time of the run in either case.
  ['zip.named', 'zip', 'long.xml', enc('zipCompress'), { CamelFileName: 'order.xml' }],
  ['unzip.one-entry', 'unzip', 'one-entry.zip', dec('zipDecompress')],
  ['unzip.two-entries', 'unzip', 'two-entries.zip', dec('zipDecompress')],
  ['digest-c14n', 'digest-c14n', 'digest.xml', digestC14n],
  ['digest-c14n.not-xml', 'digest-c14n', 'not-xml.txt', digestC14n],
  ['digest-none', 'digest-none', 'digest.xml', digestNone],
  ['digest-none.not-xml', 'digest-none', 'not-xml.txt', digestNone],
  ['digest-filter', 'digest-filter', 'digest.xml', { type: 'messageDigest', config: { canonicalization: 'exclusive', algorithm: 'SHA1', filter: '/o:Order/o:Header' } }],
  ['digest-filter-many', 'digest-filter-many', 'digest.xml', { type: 'messageDigest', config: { canonicalization: 'exclusive', algorithm: 'SHA1', filter: '/o:Order/o:Item' } }],
];
for (const [name, probeStep, file, step, headers = {}] of cases) {
  // bodyPath: the server passes a file that is no text (.gz, .zip) as its bytes.
  const r = await tool('run_flow_step', { bodyPath: 'encoders/' + file, headers: { ProbeStep: probeStep, ...headers }, namespaces: ns, step });
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
