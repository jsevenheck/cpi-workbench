import { call, openProject, availableProfiles, DEFAULT_PROFILE, type Profile, type ProfileInfo } from './engine';
import type { Diagnostic, FileEntry, GroovyResult, Row, XsltResult } from './types';

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
  groovyProfile: 'groovy-1.x' as Profile,
  tool: 'xslt' as 'xslt' | 'groovy' | 'xsd' | 'xpath' | 'tests',
  status: '',
  error: '',
  busy: false,
  iflowSteps: [] as IflowStep[],
});

export interface IflowStep {
  iflow: string;
  id: string;
  name: string;
  type: 'groovy' | 'xslt' | 'xsd' | 'error';
  note?: string;
  componentVersion?: string;
  file?: string;
  fileFound?: boolean;
  function?: string;
  profile?: Profile;
}

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
  compareOn: false,
  compare: null as CompareRun<XsltResult> | null,
});

/** The same run on the other runtime of the same kind (Saxon 9.9 vs 12, Groovy 1.x vs 2.x). */
export interface CompareRun<R> {
  profile: Profile;
  result: R | null;
  error: string;
}

export const SAMPLE_GROOVY_V1 = `import com.sap.gateway.ip.core.customdev.util.Message

// Groovy Script step 1.x (Script API v1). Ctrl+Enter runs the script with the input message.
def Message processData(Message message) {
    def body = message.getBody(String)
    def order = new XmlSlurper().parseText(body)

    message.setHeader('OrderNumber', order.@id.text())
    message.setProperty('ItemCount', order.Item.size())

    def log = messageLogFactory.getMessageLog(message)
    log?.setStringProperty('Order', order.@id.text())

    println "Processing order \${order.@id}"
    message.setBody(body.replace('<Order', '<ProcessedOrder').replace('</Order>', '</ProcessedOrder>'))
    return message
}
`;

export const SAMPLE_GROOVY_V2 = `import com.sap.it.script.v2.api.Message
import groovy.xml.XmlSlurper

// Groovy Script step 2.x (Groovy 4). Note: XmlSlurper now lives in groovy.xml.
def Message processData(Message message) {
    def body = message.getBody(String)
    def order = new XmlSlurper().parseText(body)

    message.setHeader('OrderNumber', order.@id.text())
    message.setProperty('ItemCount', order.Item.size())

    def log = messageLogFactory.getMessageLog(message)
    log?.setStringProperty('Order', order.@id.text())

    println "Processing order \${order.@id}"
    message.setBody(body.replace('<Order', '<ProcessedOrder').replace('</Order>', '</ProcessedOrder>'))
    return message
}
`;

export const groovy = $state({
  scriptPath: null as string | null,
  script: SAMPLE_GROOVY_V1,
  savedScript: SAMPLE_GROOVY_V1,
  functionName: 'processData',
  body: SAMPLE_INPUT,
  headers: [] as Row[],
  properties: [] as Row[],
  bodyType: 'String' as 'String' | 'byte[]' | 'InputStream',
  result: null as GroovyResult | null,
  running: false,
  live: true,
  casePath: null as string | null,
  /** Inputs only a test case can hold (attachments, SOAP headers, platform overrides); kept while it is open. */
  caseExtras: null as CaseExtras | null,
  compareOn: false,
  compare: null as CompareRun<GroovyResult> | null,
});

export interface CaseExtras {
  /** Project-relative folder of the case; attachment files are relative to it. */
  dir: string;
  attachments?: any[];
  soapHeaders?: any[];
  platform?: any;
}

export const xsd = $state({
  schemaPath: null as string | null,
  casePath: null as string | null,
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
    const firstXslt = profiles.find((p) => p.available && p.kind === 'xslt');
    const firstGroovy = profiles.find((p) => p.available && p.kind === 'groovy');
    if (firstXslt && !profiles.find((p) => p.id === app.profile)?.available) app.profile = firstXslt.id;
    if (firstGroovy && !profiles.find((p) => p.id === app.groovyProfile)?.available) app.groovyProfile = firstGroovy.id;
    if (!firstXslt && firstGroovy) app.tool = 'groovy';
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
  await loadIflowSteps();
  if (!r.isProject && !initProject) app.status = 'Folder has no cpi-workbench.json – it still works; use "Init" to add the standard folders.';
  else app.status = 'Project opened: ' + r.root;
}

export function closeProject() {
  app.project = null;
  safeSet('cpiwb.project', null);
  xslt.mappingPath = null;
  xslt.casePath = null;
  groovy.scriptPath = null;
  groovy.casePath = null;
  groovy.caseExtras = null;
  xsd.schemaPath = null;
  xsd.casePath = null;
  app.iflowSteps = [];
}

export async function refreshFiles() {
  if (!app.project) return;
  const r = await call('project.list');
  app.project.files = r.files;
}

export async function loadIflowSteps() {
  if (!app.project) return;
  try {
    app.iflowSteps = (await call('iflow.steps')).steps;
  } catch {
    app.iflowSteps = [];
  }
}

export async function importIflow(zip: string) {
  const r = await call('iflow.import', { zip });
  if (app.project) app.project.files = r.fileList;
  app.iflowSteps = r.steps;
  app.status = `Imported ${r.imported} (${r.files} files, ${r.steps.length} script/XSLT steps found)`;
}

/** Opens the file of an imported iFlow step with the step's settings (function, script version). */
export async function openIflowStep(step: IflowStep) {
  if (!step.file || !step.fileFound) {
    app.error = step.note ? `Step "${step.name}": ${step.note}`
      : `The file of step "${step.name}" was not found in the export (${step.file ?? 'no reference'}).`;
    return;
  }
  await openFile(step.file);
  if (step.type === 'groovy') {
    groovy.functionName = step.function ?? 'processData';
    if (step.profile && hasProfile(step.profile)) app.groovyProfile = step.profile;
  }
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
    } else if (lower.endsWith('.groovy') || lower.endsWith('.gsh')) {
      groovy.scriptPath = path;
      groovy.script = content;
      groovy.savedScript = content;
      groovy.casePath = null;
      groovy.caseExtras = null;
      if (content.includes('com.sap.it.script.v2.api') && hasProfile('groovy-2.x')) app.groovyProfile = 'groovy-2.x';
      else if (content.includes('com.sap.gateway.ip.core.customdev.util') && hasProfile('groovy-1.x')) app.groovyProfile = 'groovy-1.x';
      app.tool = 'groovy';
    } else if (lower.endsWith('.xsd')) {
      xsd.schemaPath = path;
      xsd.schema = content;
      xsd.savedSchema = content;
      xsd.casePath = null;
      app.tool = 'xsd';
    } else {
      if (app.tool === 'xsd') xsd.body = content;
      else if (app.tool === 'xpath') xpath.body = content;
      else if (app.tool === 'groovy') groovy.body = content;
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

function hasProfile(id: Profile) {
  return !!app.profiles.find((p) => p.id === id && p.available);
}

export async function saveScript(): Promise<boolean> {
  if (!app.project) {
    app.error = 'Open a project folder to save files.';
    return false;
  }
  let path = groovy.scriptPath;
  if (!path) {
    path = (await ask('Save script as (path inside the project)', 'scripts/script.groovy'))?.value ?? null;
    if (!path) return false;
  }
  await writeFile(path, groovy.script);
  groovy.scriptPath = path;
  groovy.savedScript = groovy.script;
  app.status = 'Saved ' + path;
  await refreshFiles();
  return true;
}

/** Switching 1.x <-> 2.x swaps the untouched sample script to the matching API. */
export function onGroovyProfileChange() {
  if (groovy.script === SAMPLE_GROOVY_V1 && app.groovyProfile === 'groovy-2.x') groovy.script = groovy.savedScript = SAMPLE_GROOVY_V2;
  if (groovy.script === SAMPLE_GROOVY_V2 && app.groovyProfile === 'groovy-1.x') groovy.script = groovy.savedScript = SAMPLE_GROOVY_V1;
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

function xsltRequest() {
  return {
    xslt: { content: xslt.mapping, path: xslt.mappingPath ?? 'mapping.xsl' },
    input: { body: xslt.body, headers: clean(xslt.headers), properties: clean(xslt.properties) },
    options: { paramPrecedence: xslt.paramPrecedence, missingParameter: xslt.missingParameter },
  };
}

function groovyRequest() {
  return {
    script: { content: groovy.script, path: groovy.scriptPath ?? 'script.groovy' },
    function: groovy.functionName || 'processData',
    input: {
      body: groovy.body, headers: clean(groovy.headers), properties: clean(groovy.properties),
      attachments: groovy.caseExtras?.attachments, soapHeaders: groovy.caseExtras?.soapHeaders,
    },
    options: { bodyType: groovy.bodyType },
    platform: groovy.caseExtras?.platform,
    caseDir: groovy.caseExtras?.dir,
  };
}

/** The other available runtime of the same kind, if any. */
export function otherProfile(current: Profile): Profile | null {
  const kind = app.profiles.find((p) => p.id === current)?.kind;
  return app.profiles.find((p) => p.available && p.kind === kind && p.id !== current)?.id ?? null;
}

async function compareRun<R>(method: string, params: any, current: Profile): Promise<CompareRun<R> | null> {
  const other = otherProfile(current);
  if (!other) return null;
  try {
    return { profile: other, result: await call<R>(method, params, other), error: '' };
  } catch (e) {
    return { profile: other, result: null, error: (e as Error).message ?? String(e) };
  }
}

export async function runXslt() {
  const seq = ++runSeq;
  xslt.running = true;
  app.error = '';
  try {
    const params = xsltRequest();
    const [r, c] = await Promise.all([
      call<XsltResult>('xslt.run', params, app.profile),
      xslt.compareOn ? compareRun<XsltResult>('xslt.run', params, app.profile) : Promise.resolve(null),
    ]);
    if (seq === runSeq) {
      xslt.result = r;
      xslt.compare = c;
    }
  } catch (e) {
    if (seq === runSeq) fail(e);
  } finally {
    if (seq === runSeq) xslt.running = false;
  }
}

let groovySeq = 0;

export async function runGroovy() {
  const seq = ++groovySeq;
  groovy.running = true;
  app.error = '';
  try {
    const params = groovyRequest();
    const [r, c] = await Promise.all([
      call<GroovyResult>('groovy.run', params, app.groovyProfile),
      groovy.compareOn ? compareRun<GroovyResult>('groovy.run', params, app.groovyProfile) : Promise.resolve(null),
    ]);
    if (seq === groovySeq) {
      groovy.result = r;
      groovy.compare = c;
    }
  } catch (e) {
    if (seq === groovySeq) fail(e);
  } finally {
    if (seq === groovySeq) groovy.running = false;
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
  if (withExpected) {
    const compare = xslt.result?.outputMethod === 'xml' || !xslt.result?.outputMethod ? 'xml' : 'text';
    tc.expect = await expectations(dir, xslt.result, compare);
  }
  await writeFile(dir + '/case.json', JSON.stringify(tc, null, 2) + '\n');
  xslt.casePath = dir + '/case.json';
  app.status = 'Saved test case ' + dir;
  await refreshFiles();
}

/** Expected body/headers/properties from the current result (only changed headers/properties). */
async function expectations(dir: string, result: XsltResult | GroovyResult | null, compare: string): Promise<any> {
  if (!result) return undefined;
  if (!result.ok || !result.output) return { ok: false };
  const ext = compare === 'xml' ? 'xml' : 'txt';
  await writeFile(`${dir}/expected-output.${ext}`, result.output.body);
  const expect: any = { body: { file: `expected-output.${ext}` }, compare };
  const pick = (rows: Row[], ch?: { added: string[]; changed: string[] }) => {
    const names = new Set([...(ch?.added ?? []), ...(ch?.changed ?? [])]);
    const out: Record<string, string> = {};
    for (const r of rows) if (names.has(r.name)) out[r.name] = r.value;
    return Object.keys(out).length ? out : undefined;
  };
  expect.headers = pick(result.output.headers, result.changes?.headers);
  expect.properties = pick(result.output.properties, result.changes?.properties);
  return expect;
}

export async function saveGroovyCase(name: string, withExpected: boolean) {
  if (!app.project) throw new Error('Open a project folder to save test cases.');
  if (!groovy.scriptPath || groovy.script !== groovy.savedScript) {
    if (!(await saveScript())) return;
  }
  const dir = 'tests/' + slug(name);
  const tc: any = {
    name,
    step: 'groovy',
    profile: app.groovyProfile,
    script: relative(dir, groovy.scriptPath!),
    input: { body: { file: 'input.xml' }, headers: clean(groovy.headers), properties: clean(groovy.properties) },
  };
  if (groovy.functionName && groovy.functionName !== 'processData') tc.function = groovy.functionName;
  if (groovy.bodyType !== 'String') tc.options = { bodyType: groovy.bodyType };
  const extras = groovy.caseExtras;
  if (extras) {
    // Keep what the opened case held; attachment files stay where they are.
    if (extras.attachments) tc.input.attachments = extras.attachments.map((a) =>
      a?.file ? { ...a, file: relative(dir, joinPath(extras.dir, a.file)) } : a);
    if (extras.soapHeaders) tc.input.soapHeaders = extras.soapHeaders;
    if (extras.platform) tc.platform = extras.platform;
  }
  await writeFile(dir + '/input.xml', groovy.body);
  if (withExpected) {
    const out = groovy.result?.output?.body ?? '';
    tc.expect = await expectations(dir, groovy.result, out.trimStart().startsWith('<') ? 'xml' : 'text');
  }
  await writeFile(dir + '/case.json', JSON.stringify(tc, null, 2) + '\n');
  groovy.casePath = dir + '/case.json';
  if (extras) groovy.caseExtras = { ...extras, dir };
  app.status = 'Saved test case ' + dir;
  await refreshFiles();
}

async function loadGroovyCase(path: string, dir: string, tc: any) {
  const scriptPath = joinPath(dir, tc.script);
  groovy.scriptPath = scriptPath;
  groovy.script = await readFile(scriptPath);
  groovy.savedScript = groovy.script;
  const body = tc.input?.body;
  groovy.body = typeof body === 'string' ? body : body?.file ? await readFile(joinPath(dir, body.file)) : body?.text ?? '';
  groovy.headers = toRows(tc.input?.headers);
  groovy.properties = toRows(tc.input?.properties);
  groovy.functionName = tc.function ?? 'processData';
  groovy.bodyType = tc.options?.bodyType ?? 'String';
  if (tc.profile && hasProfile(tc.profile)) app.groovyProfile = tc.profile;
  const extras: CaseExtras = { dir, attachments: tc.input?.attachments, soapHeaders: tc.input?.soapHeaders, platform: tc.platform };
  groovy.caseExtras = extras.attachments || extras.soapHeaders || extras.platform ? extras : null;
  groovy.casePath = path;
  app.tool = 'groovy';
  app.status = 'Loaded test case ' + (tc.name ?? path);
  await runGroovy();
}

export async function saveXsdCase(name: string, withExpected: boolean) {
  if (!app.project) throw new Error('Open a project folder to save test cases.');
  if (!xsd.schemaPath || xsd.schema !== xsd.savedSchema) {
    if (!(await saveSchema())) return;
  }
  const dir = 'tests/' + slug(name);
  const tc: any = {
    name,
    step: 'xsd',
    schema: relative(dir, xsd.schemaPath!),
    input: { body: { file: 'input.xml' } },
  };
  await writeFile(dir + '/input.xml', xsd.body);
  if (withExpected && xsd.result) {
    // Only the verdict: which part of a message is stable enough for errorContains is up to the user.
    tc.expect = xsd.result.ok ? { valid: xsd.result.valid } : { ok: false };
  }
  await writeFile(dir + '/case.json', JSON.stringify(tc, null, 2) + '\n');
  xsd.casePath = dir + '/case.json';
  app.status = 'Saved test case ' + dir;
  await refreshFiles();
}

async function loadXsdCase(path: string, dir: string, tc: any) {
  const schemaPath = joinPath(dir, tc.schema);
  xsd.schemaPath = schemaPath;
  xsd.schema = await readFile(schemaPath);
  xsd.savedSchema = xsd.schema;
  const body = tc.input?.body;
  xsd.body = typeof body === 'string' ? body : body?.file ? await readFile(joinPath(dir, body.file)) : body?.text ?? '';
  xsd.casePath = path;
  app.tool = 'xsd';
  app.status = 'Loaded test case ' + (tc.name ?? path);
  await runXsd();
}

export async function loadCase(path: string) {
  const tc = JSON.parse(await readFile(path));
  const dir = path.split('/').slice(0, -1).join('/');
  if (tc.step === 'groovy') return loadGroovyCase(path, dir, tc);
  if (tc.step === 'xsd') return loadXsdCase(path, dir, tc);
  if ((tc.step ?? 'xslt') !== 'xslt') {
    app.error = `Step '${tc.step}' cannot be opened in an editor view.`;
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
