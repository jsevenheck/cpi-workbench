import { call, openProject, availableProfiles, DEFAULT_PROFILE, type Profile, type ProfileInfo } from './engine';
import type { Diagnostic, FileEntry, Row, XsltResult } from './types';

export const SAMPLE_XSL = `<?xml version="1.0" encoding="UTF-8"?>
<!-- Headers and exchange properties arrive as xsl:param with the same name, like in Cloud Integration.
     $exchange + hci:setHeader / hci:setProperty change the message. Press Ctrl+Enter to run. -->
<xsl:stylesheet version="3.0"
    xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:hci="http://sap.com/it/"
    exclude-result-prefixes="hci">

  <xsl:param name="exchange"/>
  <xsl:param name="SenderSystem" select="'unknown'"/>
  <xsl:output method="xml" indent="yes"/>

  <xsl:template match="/Order">
    <xsl:value-of select="hci:setHeader($exchange, 'OrderNumber', @id)"/>
    <xsl:value-of select="hci:setProperty($exchange, 'ItemCount', string(count(Item)))"/>
    <xsl:message>Mapping order <xsl:value-of select="@id"/> from <xsl:value-of select="$SenderSystem"/></xsl:message>
    <SalesOrder sender="{$SenderSystem}">
      <xsl:for-each select="Item">
        <Line no="{position()}" material="{@sku}" qty="{@qty}"/>
      </xsl:for-each>
    </SalesOrder>
  </xsl:template>
</xsl:stylesheet>
`;

export const SAMPLE_INPUT = `<Order id="4711">
  <Item sku="M-100" qty="2"/>
  <Item sku="M-200" qty="1"/>
</Order>
`;

export const app = $state({
  project: null as null | { root: string; files: FileEntry[] },
  profiles: [] as ProfileInfo[],
  engineInfo: null as any,
  profile: DEFAULT_PROFILE as Profile,
  tool: 'xslt' as 'xslt' | 'xsd' | 'xpath' | 'tests',
  status: '',
  error: '',
  busy: false,
});

export const xslt = $state({
  mappingPath: null as string | null,
  mapping: SAMPLE_XSL,
  savedMapping: SAMPLE_XSL,
  body: SAMPLE_INPUT,
  headers: [{ name: 'SenderSystem', value: 'ERP_100', type: 'String' }] as Row[],
  properties: [] as Row[],
  paramPrecedence: 'header' as 'header' | 'property',
  missingParameter: 'default' as 'default' | 'error',
  result: null as XsltResult | null,
  running: false,
  live: true,
  casePath: null as string | null,
});

export const xsd = $state({
  schemaPath: null as string | null,
  schema: '',
  savedSchema: '',
  body: '',
  result: null as null | { ok: boolean; valid: boolean; diagnostics: Diagnostic[]; timing: { totalMs: number } },
  running: false,
});

export const xpath = $state({
  expression: '//Item/@sku',
  version: '1.0' as '1.0' | '3.1',
  namespaces: [] as Row[],
  body: SAMPLE_INPUT,
  result: null as null | { ok: boolean; items: any[]; count: number; diagnostics: Diagnostic[]; engine: string },
  running: false,
});

export const tests = $state({
  results: [] as any[],
  summary: null as null | { passed: number; failed: number; skipped: number },
  running: false,
});

/** In-app prompt (window.prompt is not available in every desktop webview). */
export const dialog = $state({
  open: false,
  title: '',
  value: '',
  checkboxLabel: '' as string,
  checked: true,
  resolve: null as null | ((r: { value: string; checked: boolean } | null) => void),
});

export function ask(title: string, value: string, checkboxLabel = ''): Promise<{ value: string; checked: boolean } | null> {
  return new Promise((resolve) => {
    Object.assign(dialog, { open: true, title, value, checkboxLabel, checked: true, resolve });
  });
}

export function closeDialog(ok: boolean) {
  const r = dialog.resolve;
  dialog.open = false;
  dialog.resolve = null;
  r?.(ok && dialog.value.trim() ? { value: dialog.value.trim(), checked: dialog.checked } : null);
}

function fail(e: unknown) {
  app.error = e instanceof Error ? e.message : String(e);
}

export async function init() {
  try {
    const { profiles, info } = await availableProfiles();
    app.profiles = profiles;
    app.engineInfo = info;
    const first = profiles.find((p) => p.available);
    if (first && !profiles.find((p) => p.id === app.profile)?.available) app.profile = first.id;
    const saved = safeGet('cpiwb.project');
    if (saved) await open(saved).catch(() => undefined);
  } catch (e) {
    fail(e);
  }
}

function safeGet(key: string): string | null {
  try { return localStorage.getItem(key); } catch { return null; }
}
function safeSet(key: string, v: string | null) {
  try { v === null ? localStorage.removeItem(key) : localStorage.setItem(key, v); } catch { /* ignore */ }
}

// ------------------------------------------------------------------ project

export async function open(dir: string, initProject = false) {
  app.error = '';
  const r = await openProject(dir, initProject);
  app.project = { root: r.root, files: r.files };
  safeSet('cpiwb.project', r.root);
  if (!r.isProject && !initProject) app.status = 'Folder has no cpi-workbench.json – it still works; use "Init" to add the standard folders.';
  else app.status = 'Project opened: ' + r.root;
}

export function closeProject() {
  app.project = null;
  safeSet('cpiwb.project', null);
  xslt.mappingPath = null;
  xslt.casePath = null;
}

export async function refreshFiles() {
  if (!app.project) return;
  const r = await call('project.list');
  app.project.files = r.files;
}

export async function readFile(path: string): Promise<string> {
  return (await call('file.read', { path })).content;
}

export async function writeFile(path: string, content: string) {
  await call('file.write', { path, content });
}

export async function openFile(path: string) {
  try {
    app.error = '';
    const lower = path.toLowerCase();
    if (lower.endsWith('case.json')) return await loadCase(path);
    const content = await readFile(path);
    if (lower.endsWith('.xsl') || lower.endsWith('.xslt')) {
      xslt.mappingPath = path;
      xslt.mapping = content;
      xslt.savedMapping = content;
      xslt.casePath = null;
      app.tool = 'xslt';
    } else if (lower.endsWith('.xsd')) {
      xsd.schemaPath = path;
      xsd.schema = content;
      xsd.savedSchema = content;
      app.tool = 'xsd';
    } else {
      if (app.tool === 'xsd') xsd.body = content;
      else if (app.tool === 'xpath') xpath.body = content;
      else xslt.body = content;
      app.status = `Loaded ${path} as input body`;
    }
  } catch (e) {
    fail(e);
  }
}

export async function saveMapping(): Promise<boolean> {
  if (!app.project) {
    app.error = 'Open a project folder to save files.';
    return false;
  }
  let path = xslt.mappingPath;
  if (!path) {
    path = (await ask('Save mapping as (path inside the project)', 'mappings/mapping.xsl'))?.value ?? null;
    if (!path) return false;
  }
  await writeFile(path, xslt.mapping);
  xslt.mappingPath = path;
  xslt.savedMapping = xslt.mapping;
  app.status = 'Saved ' + path;
  await refreshFiles();
  return true;
}

export async function saveSchema(): Promise<boolean> {
  if (!app.project || !xsd.schemaPath) {
    const p = app.project ? (await ask('Save schema as (path inside the project)', 'schemas/schema.xsd'))?.value : null;
    if (!p) return false;
    xsd.schemaPath = p;
  }
  await writeFile(xsd.schemaPath!, xsd.schema);
  xsd.savedSchema = xsd.schema;
  app.status = 'Saved ' + xsd.schemaPath;
  await refreshFiles();
  return true;
}

// ------------------------------------------------------------------ runs

let runSeq = 0;

export async function runXslt() {
  const seq = ++runSeq;
  xslt.running = true;
  app.error = '';
  try {
    const r = await call<XsltResult>('xslt.run', {
      xslt: { content: xslt.mapping, path: xslt.mappingPath ?? 'mapping.xsl' },
      input: { body: xslt.body, headers: clean(xslt.headers), properties: clean(xslt.properties) },
      options: { paramPrecedence: xslt.paramPrecedence, missingParameter: xslt.missingParameter },
    }, app.profile);
    if (seq === runSeq) xslt.result = r;
  } catch (e) {
    if (seq === runSeq) fail(e);
  } finally {
    if (seq === runSeq) xslt.running = false;
  }
}

function clean(rows: Row[]) {
  return rows.filter((r) => r.name.trim() !== '').map((r) => ({ ...r, name: r.name.trim() }));
}

export async function runXsd() {
  xsd.running = true;
  app.error = '';
  try {
    xsd.result = await call('xsd.validate', {
      schema: { content: xsd.schema, path: xsd.schemaPath ?? 'schema.xsd' },
      input: { body: xsd.body },
    }, app.profile);
  } catch (e) {
    fail(e);
  } finally {
    xsd.running = false;
  }
}

export async function runXpath() {
  xpath.running = true;
  app.error = '';
  const ns: Record<string, string> = {};
  for (const r of xpath.namespaces) if (r.name.trim() && !r.disabled) ns[r.name.trim()] = r.value;
  try {
    xpath.result = await call('xpath.eval', { expression: xpath.expression, version: xpath.version,
      namespaces: ns, input: { body: xpath.body } }, app.profile);
  } catch (e) {
    fail(e);
  } finally {
    xpath.running = false;
  }
}

export async function formatXml(text: string): Promise<string> {
  const r = await call('xml.format', { text }, app.profile);
  if (!r.ok) throw new Error(r.diagnostics?.[0]?.message ?? 'Not well-formed XML');
  return r.text;
}

// ------------------------------------------------------------------ test cases

function relative(fromDir: string, to: string): string {
  const a = fromDir.split('/').filter(Boolean);
  const b = to.split('/').filter(Boolean);
  let i = 0;
  while (i < a.length && i < b.length && a[i] === b[i]) i++;
  return [...a.slice(i).map(() => '..'), ...b.slice(i)].join('/');
}

function joinPath(dir: string, rel: string): string {
  const parts = [...dir.split('/').filter(Boolean)];
  for (const p of rel.split('/')) {
    if (p === '..') parts.pop();
    else if (p !== '.' && p !== '') parts.push(p);
  }
  return parts.join('/');
}

export function slug(name: string) {
  return name.trim().toLowerCase().replace(/[^a-z0-9._-]+/g, '-').replace(/^-+|-+$/g, '') || 'case';
}

export async function saveCase(name: string, withExpected: boolean) {
  if (!app.project) throw new Error('Open a project folder to save test cases.');
  if (!xslt.mappingPath || xslt.mapping !== xslt.savedMapping) {
    if (!(await saveMapping())) return;
  }
  const dir = 'tests/' + slug(name);
  const tc: any = {
    name,
    step: 'xslt',
    profile: app.profile,
    xslt: relative(dir, xslt.mappingPath!),
    input: { body: { file: 'input.xml' }, headers: clean(xslt.headers), properties: clean(xslt.properties) },
  };
  if (xslt.paramPrecedence !== 'header' || xslt.missingParameter !== 'default') {
    tc.options = { paramPrecedence: xslt.paramPrecedence, missingParameter: xslt.missingParameter };
  }
  await writeFile(dir + '/input.xml', xslt.body);
  if (withExpected && xslt.result?.ok && xslt.result.output) {
    await writeFile(dir + '/expected-output.xml', xslt.result.output.body);
    tc.expect = { body: { file: 'expected-output.xml' }, compare: xslt.result.outputMethod === 'xml' || !xslt.result.outputMethod ? 'xml' : 'text' };
    const added = new Set([...(xslt.result.changes?.headers.added ?? []), ...(xslt.result.changes?.headers.changed ?? [])]);
    const headers: Record<string, string> = {};
    for (const h of xslt.result.output.headers) if (added.has(h.name)) headers[h.name] = h.value;
    if (Object.keys(headers).length) tc.expect.headers = headers;
    const addedP = new Set([...(xslt.result.changes?.properties.added ?? []), ...(xslt.result.changes?.properties.changed ?? [])]);
    const props: Record<string, string> = {};
    for (const p of xslt.result.output.properties) if (addedP.has(p.name)) props[p.name] = p.value;
    if (Object.keys(props).length) tc.expect.properties = props;
  } else if (withExpected && xslt.result && !xslt.result.ok) {
    tc.expect = { ok: false };
  }
  await writeFile(dir + '/case.json', JSON.stringify(tc, null, 2) + '\n');
  xslt.casePath = dir + '/case.json';
  app.status = 'Saved test case ' + dir;
  await refreshFiles();
}

export async function loadCase(path: string) {
  const tc = JSON.parse(await readFile(path));
  const dir = path.split('/').slice(0, -1).join('/');
  if ((tc.step ?? 'xslt') !== 'xslt') {
    app.error = 'Only XSLT cases can be opened in the editor for now; run XSD cases from the test runner.';
    return;
  }
  const mappingPath = joinPath(dir, tc.xslt);
  xslt.mappingPath = mappingPath;
  xslt.mapping = await readFile(mappingPath);
  xslt.savedMapping = xslt.mapping;
  const body = tc.input?.body;
  xslt.body = typeof body === 'string' ? body : body?.file ? await readFile(joinPath(dir, body.file)) : body?.text ?? '';
  xslt.headers = toRows(tc.input?.headers);
  xslt.properties = toRows(tc.input?.properties);
  xslt.paramPrecedence = tc.options?.paramPrecedence ?? 'header';
  xslt.missingParameter = tc.options?.missingParameter ?? 'default';
  if (tc.profile && app.profiles.find((p) => p.id === tc.profile && p.available)) app.profile = tc.profile;
  xslt.casePath = path;
  app.tool = 'xslt';
  app.status = 'Loaded test case ' + (tc.name ?? path);
  await runXslt();
}

function toRows(v: any): Row[] {
  if (!v) return [];
  if (Array.isArray(v)) return v.map((r) => ({ name: r.name, value: r.value ?? '', type: r.type ?? 'String', disabled: !!r.disabled }));
  return Object.entries(v).map(([name, val]: [string, any]) =>
    val !== null && typeof val === 'object' ? { name, value: val.value ?? '', type: val.type ?? 'String' }
      : { name, value: val === null ? '' : String(val), type: val === null ? 'null' : 'String' });
}

export async function runAllTests() {
  tests.running = true;
  app.error = '';
  try {
    const merged = new Map<string, any>();
    for (const p of app.profiles.filter((x) => x.available)) {
      const r = await call('tests.run', { path: 'tests' }, p.id);
      for (const c of r.results) {
        const prev = merged.get(c.dir);
        if (!prev || prev.status === 'skipped') merged.set(c.dir, { ...c, profile: c.status === 'skipped' ? undefined : p.id });
      }
    }
    tests.results = [...merged.values()];
    tests.summary = {
      passed: tests.results.filter((c) => c.status === 'passed').length,
      failed: tests.results.filter((c) => c.status === 'failed' || c.status === 'error').length,
      skipped: tests.results.filter((c) => c.status === 'skipped').length,
    };
  } catch (e) {
    fail(e);
  } finally {
    tests.running = false;
  }
}

export function caseRelPath(absDir: string): string | null {
  if (!app.project) return null;
  const root = app.project.root.replace(/\\/g, '/');
  const d = absDir.replace(/\\/g, '/');
  return d.startsWith(root) ? d.slice(root.length + 1) + '/case.json' : null;
}
