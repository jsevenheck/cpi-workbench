// Starts `cpi-workbench mcp` the way an MCP client does (a child process with piped stdio) and checks
// both protocol eras and a run on each kind of engine. CI runs it against the release build on every OS,
// which on Windows is a GUI-subsystem executable.
//   node scripts/mcp-smoke.mjs <cpi-workbench binary> <project folder>
import { spawn } from 'node:child_process';
import path from 'node:path';
import readline from 'node:readline';

const [binArg, projectArg] = process.argv.slice(2);
if (!binArg || !projectArg) {
  console.error('usage: node scripts/mcp-smoke.mjs <cpi-workbench binary> <project folder>');
  process.exit(2);
}
const [bin, project] = [path.resolve(binArg), path.resolve(projectArg)];

const child = spawn(bin, ['mcp', '--project', project], { stdio: ['pipe', 'pipe', 'inherit'], windowsHide: true });
const waiting = new Map();
readline.createInterface({ input: child.stdout }).on('line', (line) => {
  const msg = JSON.parse(line);
  waiting.get(msg.id)?.(msg);
  waiting.delete(msg.id);
});
const timer = setTimeout(() => fail('no answer within 120 s'), 120_000);

let nextId = 1;
function request(method, params) {
  const id = nextId++;
  child.stdin.write(JSON.stringify({ jsonrpc: '2.0', id, method, params }) + '\n');
  return new Promise((resolve) => waiting.set(id, resolve));
}
const meta = { 'io.modelcontextprotocol/protocolVersion': '2026-07-28', 'io.modelcontextprotocol/clientCapabilities': {},
  'io.modelcontextprotocol/clientInfo': { name: 'mcp-smoke', version: '1' } };

function fail(message) {
  console.error('MCP smoke test failed:', message);
  child.kill();
  process.exit(1);
}
function check(cond, message, detail) {
  if (!cond) fail(`${message}: ${JSON.stringify(detail)}`);
  console.log('ok -', message);
}

const discover = await request('server/discover', { _meta: meta });
check(discover.result?.supportedVersions?.[0] === '2026-07-28', 'server/discover (2026-07-28)', discover);

const init = await request('initialize', { protocolVersion: '2025-11-25', capabilities: {}, clientInfo: { name: 'mcp-smoke', version: '1' } });
check(init.result?.protocolVersion === '2025-11-25', 'initialize (2025-11-25)', init);
child.stdin.write(JSON.stringify({ jsonrpc: '2.0', method: 'notifications/initialized' }) + '\n');

const list = await request('tools/list', {});
check(list.result?.tools?.length === 6, 'tools/list', list);

async function tool(name, args) {
  const r = await request('tools/call', { name, arguments: args, _meta: meta });
  if (r.result?.isError) fail(`${name}: ${r.result.content?.[0]?.text}`);
  return r.result?.structuredContent;
}

const info = await tool('project_info', {});
check(info.runtimes.every((r) => r.installed), 'all runtimes installed', info.runtimes);

const xslt = await tool('run_xslt', { runtime: 'both', body: '<a/>', headers: { h: 'hello' },
  mapping: "<xsl:stylesheet version='2.0' xmlns:xsl='http://www.w3.org/1999/XSL/Transform'><xsl:param name='h'/>"
    + "<xsl:output method='text'/><xsl:template match='/'><xsl:value-of select='$h'/></xsl:template></xsl:stylesheet>" });
check(xslt.runs.every((r) => r.result?.output?.body === 'hello'), 'run_xslt on both Saxon lines', xslt);

for (const runtime of ['groovy-1.x', 'groovy-2.x']) {
  const g = await tool('run_groovy', { runtime, body: 'abc', script: 'def processData(m) { m.setBody(m.getBody(String).reverse()); m }' });
  check(g.output?.body === 'cba', `run_groovy on ${runtime}`, g);
}

const route = await tool('run_flow_step', { headers: { SenderId: 'ERP' }, step: { type: 'router', config: { routes: [
  { name: 'ERP', expressionType: 'nonxml', condition: "${header.SenderId} = 'ERP'", default: false },
  { name: 'Other', expressionType: 'nonxml', condition: '', default: true }] } } });
check(route.taken === 'ERP', 'run_flow_step (Router, Camel Simple)', route);

clearTimeout(timer);
child.stdin.end();
const code = await new Promise((resolve) => child.on('exit', resolve));
check(code === 0, 'exits when stdin closes', code);
