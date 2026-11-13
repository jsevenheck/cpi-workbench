import { call, openProject, closeProject as closeEngineProject, availableProfiles, DEFAULT_PROFILE, type Profile, type ProfileInfo } from './engine';
import type { Diagnostic, FileEntry, GroovyResult, Row, XsltResult } from './types';
import { argText, mappingArgs } from './queue';
import { scriptFunctions } from './functions';
import { readShared, sharedSummary, type SharedMessage } from './share';
import { attachmentInput, attachmentRows, parsePlatformFile, platformDoc, platformFileText, platformRows, platformRunData, PLATFORM_FILES, PLATFORM_KEYS,
  soapHeaderInput, soapHeaderRows, type AttachmentRow, type PlatformKey, type PlatformRows, type SoapHeaderRow } from './platform';
import { toRows } from './rows';
import { pathKey } from './paths';
import { resolveSimpleFunctions } from './simple';
import { callsJava } from './trust';

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

export type StepTool = 'xslt' | 'groovy' | 'xsd' | 'xpath' | 'flow';

export const app = $state({
  project: null as null | { root: string; files: FileEntry[] },
  profiles: [] as ProfileInfo[],
  engineInfo: null as any,
  profile: DEFAULT_PROFILE as Profile,
  groovyProfile: 'groovy-1.x' as Profile,
  tool: 'xslt' as StepTool | 'file',
  /** The step tool last shown; inputs opened from the tree go to its body. */
  stepTool: 'xslt' as StepTool,
  /** The inputs/ file last loaded or saved as each tool's input body. */
  inputPaths: {} as Partial<Record<StepTool, string>>,
  status: '',
  error: '',
  busy: false,
  iflowSteps: [] as IflowStep[],
});

export interface IflowStep {
  iflow: string;
  id: string;
  name: string;
  type: 'groovy' | 'xslt' | 'xsd' | 'mmap' | FlowStep | 'error';
  /** Message Mapping steps: the Groovy scripts its .mmap uses (the mapping itself is not simulated). */
  scripts?: string[];
  note?: string;
  /** Flow step settings (Content Modifier, Filter, Router, Splitter, Gather) as the iFlow stores them. */
  config?: any;
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
  /** False for a mapping with java: calls that was opened and not yet run by hand (see autoRunXslt). */
  armed: true,
  casePath: null as string | null,
  compareOn: false,
  compare: null as CompareRun<XsltResult> | null,
});

/**
 * Opening a file must not run its code: a Groovy script, and a stylesheet's reflexive java: calls, run with the
 * user's rights, and a file may come from someone else (an imported iFlow, a shared project). Such a file runs
 * for the first time when the user starts it (Run or Ctrl+Enter); from then on live runs follow the edits.
 */
function disarmXslt(path: string) {
  xslt.armed = !callsJava(xslt.mapping);
  if (!xslt.armed) {
    xslt.result = null;
    xslt.compare = null;
    app.status = `Opened ${path}. It calls Java (java: namespace), so it has not run: press Ctrl+Enter to run it.`;
  }
}

function disarmGroovy(what: string) {
  groovy.armed = false;
  groovy.result = null;
  groovy.compare = null;
  app.status = `Opened ${what}. A script runs with your rights, so it has not run: press Ctrl+Enter to run it.`;
}

/** A live run or a run on showing a tab: only for code the user has started once. */
export function autoRunXslt() {
  // An empty editor (a closed mapping) has nothing to run.
  if (xslt.armed && xslt.mapping.trim()) void runXslt();
}

export function autoRunGroovy() {
  if (groovy.armed && groovy.script.trim()) void runGroovy();
}

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
  /** False for a script that was opened and not yet run by hand (see autoRunGroovy). */
  armed: true,
  casePath: null as string | null,
  /** Message attachments and SOAP headers the script step gets (a mapping function gets none). */
  attachments: [] as AttachmentRow[],
  soapHeaders: [] as SoapHeaderRow[],
  /** The open test case's `platform` object: it replaces the project's platform data key by key while the case is open. */
  casePlatform: null as Record<string, unknown> | null,
  compareOn: false,
  compare: null as CompareRun<GroovyResult> | null,
  /** A Message Mapping function, known from the last run of this function; null for processData(message). */
  mapping: null as { function: string; parameters: string[] } | null,
  /** The open script's Message Mapping functions (signatures), from its last run that compiled. */
  functions: [] as string[],
  /** The function the last run called as a script step although the script text reads like a mapping function. */
  stepFunction: null as string | null,
  /** Text of each String / String[] argument of the mapping function (queues: one context per line). */
  args: [] as (string | null)[],
});

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
  version: '3.1' as '1.0' | '3.1',
  namespaces: [] as Row[],
  body: SAMPLE_INPUT,
  result: null as null | { ok: boolean; items: any[]; count: number; diagnostics: Diagnostic[]; engine: string },
  running: false,
});

export type FlowStep = 'contentModifier' | 'filter' | 'router' | 'splitter' | 'gather' | 'converter';

/** A Content Modifier row in the iFlow's encoding (type: constant, header, property, xpath, expression, ...). */
export interface CmRow {
  action: 'Create' | 'Delete';
  name: string;
  type: string;
  value: string;
  datatype: string;
  default: string;
}

export interface Route {
  name: string;
  expressionType: 'xml' | 'nonxml';
  condition: string;
  default: boolean;
}

export interface RouteResult {
  status: 'true' | 'false' | 'error' | 'not evaluated' | 'default';
  message?: string;
  /** The Camel spelling of a Non-XML condition, when CPI's had to be rewritten. */
  camel?: string;
}

/**
 * JSON ↔ XML and XML ↔ CSV converters; mapping and array paths are text, one entry per line. The MCP server uses
 * the same defaults (`converter_defaults` in `mcp/flow.rs`); change both together.
 */
function converterDefaults() {
  return {
    conversion: 'xmlToJson' as 'jsonToXml' | 'xmlToJson' | 'xmlToCsv' | 'csvToXml',
    useNamespaces: false, separator: ':', namespaceMapping: '',
    addRootElement: false, rootElementName: 'root', rootNamespace: '',
    suppressRoot: false, streaming: false, streamingElements: 'all' as 'all' | 'specific', arrayPaths: '', outputEncoding: '',
    sourcePath: '/Order/Item', fieldSeparator: ',', csvHeader: 'xml' as 'none' | 'xml' | 'xsd', includeParent: false, parentPath: '',
    includeAttributes: false,
    schema: '', targetPath: '', recordMarker: '', firstLineHeader: true, headerMapping: 'xsd' as 'xsd' | 'csv',
  };
}

export const flow = $state({
  step: 'contentModifier' as FlowStep,
  /**
   * The Groovy engine whose Camel runs Camel Simple and the Content Modifier. Both carry Camel 3.14.7, as a
   * tenant does for both script versions (F48), so any installed one will do.
   */
  camel: 'groovy-2.x' as Profile,
  /** The iFlow step each step's settings came from, if any. */
  sources: {} as Partial<Record<FlowStep, string>>,
  body: SAMPLE_INPUT,
  headers: [{ name: 'SenderId', value: 'ERP_100', type: 'String' }] as Row[],
  properties: [] as Row[],
  cm: {
    headers: [{ action: 'Create', name: 'OrderId', type: 'xpath', value: '/Order/@id', datatype: 'java.lang.String', default: '' }] as CmRow[],
    properties: [{ action: 'Create', name: 'Key', type: 'expression', value: '${header.SenderId}-${header.OrderId}', datatype: '', default: '' }] as CmRow[],
    bodyType: 'constant' as 'constant' | 'expression',
    body: '',
  },
  router: {
    routes: [
      // SAP: all routes use the same condition type; XML and Non-XML mixed make the evaluation fail.
      { name: 'ERP', expressionType: 'nonxml', condition: "${header.SenderId} = 'ERP_100'", default: false },
      { name: 'Web shop', expressionType: 'nonxml', condition: "${header.SenderId} = 'WEBSHOP' and ${header.Priority} = 'high'", default: false },
      { name: 'Default', expressionType: 'nonxml', condition: '', default: true },
    ] as Route[],
    throwException: false,
  },
  splitter: { splitter: 'general' as 'general' | 'iterating', expressionType: 'xpath' as 'xpath' | 'token' | 'lineBreak',
    expression: '/Order/Item', grouping: '', streaming: true },
  filter: { expression: "/Order/Item[@qty > 1]", valueType: 'nodelist' },
  /** The messages to gather: a splitter's result or bodies added by hand. */
  gather: { messageType: 'sameXml', algorithm: 'combine', sourceXPath: '', targetXPath: '', messages: [] as string[] },
  converter: converterDefaults(),
  cmResult: null as any,
  routerResult: null as null | { routes: RouteResult[]; taken: string; diagnostics: string[] },
  splitResult: null as any,
  filterResult: null as any,
  gatherResult: null as any,
  converterResult: null as any,
  running: false,
});

/** In-app prompt (window.prompt is not available in every desktop webview). */
export const dialog = $state({
  open: false,
  title: '',
  value: '',
  checkboxLabel: '' as string,
  checked: true,
  /** false: a yes/no question without a text field. */
  input: true,
  resolve: null as null | ((r: { value: string; checked: boolean } | null) => void),
});

export function ask(title: string, value: string, checkboxLabel = ''): Promise<{ value: string; checked: boolean } | null> {
  return new Promise((resolve) => {
    Object.assign(dialog, { open: true, title, value, checkboxLabel, checked: true, input: true, resolve });
  });
}

/** In-app confirmation (window.confirm is not available in every desktop webview). */
export function confirm(title: string): Promise<boolean> {
  return new Promise((resolve) => {
    Object.assign(dialog, { open: true, title, value: '', checkboxLabel: '', checked: true, input: false,
      resolve: (r: unknown) => resolve(r !== null) });
  });
}

export function closeDialog(ok: boolean) {
  const r = dialog.resolve;
  dialog.open = false;
  dialog.resolve = null;
  r?.(ok && (!dialog.input || dialog.value.trim()) ? { value: dialog.value.trim(), checked: dialog.checked } : null);
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
    if (firstGroovy && !profiles.find((p) => p.id === flow.camel)?.available) flow.camel = firstGroovy.id;
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

// ------------------------------------------------------------------ a message handed over
// From CPI Helper's GroovyDebugX and xsltDebugX plugins (share.ts): a traced step's message, with the step's
// script or mapping if the user sent it along. The app connects to nothing for this; the text comes in pasted,
// or in the address the browser mode was opened with.

/** Takes the message of a pasted link or JSON into its tab. Code that comes along is not run: it is someone's script. */
export async function importMessage(pasted: string): Promise<boolean> {
  try {
    app.error = '';
    const m = await readShared(pasted);
    const what = sharedSummary(m);
    if (what === '') throw new Error('The link holds no body, headers, properties or script: nothing was sent along.');
    const tool: StepTool = m.kind === 'message' ? app.stepTool : m.kind;
    if (m.kind !== 'message' && !app.profiles.some((p) => p.kind === m.kind && p.available) && app.profiles.length > 0) {
      throw new Error(`The link is for a ${m.kind === 'groovy' ? 'Groovy script' : 'XSLT mapping'}, and no engine for it is installed or running.`);
    }
    if (m.source !== '' && !(await mayDiscard(tool === 'groovy' ? 'groovy' : 'xslt'))) return false;
    if (tool === 'groovy') importGroovy(m);
    else if (tool === 'xslt') importXslt(m);
    else {
      // XSD, XPath and Flow Steps take a message typed as JSON: the body, and for Flow Steps the rows.
      if (m.body !== '') {
        if (tool === 'xsd') xsd.body = m.body;
        else if (tool === 'xpath') xpath.body = m.body;
        else flow.body = m.body;
        delete app.inputPaths[tool];
      }
      if (tool === 'flow') {
        if (m.headers.length) flow.headers = m.headers;
        if (m.properties.length) flow.properties = m.properties;
      }
      app.tool = tool;
      app.status = `Imported: ${what}.`;
    }
    return true;
  } catch (e) {
    fail(e);
    return false;
  }
}

function importGroovy(m: SharedMessage) {
  if (m.source !== '') {
    rememberScript();
    groovy.scriptPath = groovy.casePath = null;
    groovy.casePlatform = null;
    groovy.script = m.source;
    groovy.savedScript = ''; // not a file of the project yet: Save asks for a path
    groovy.functionName = m.function || 'processData';
    groovy.mapping = null;
    groovy.functions = [];
    groovy.stepFunction = null;
    groovy.args = [];
    forgetGroovyRun();
    if (m.source.includes('com.sap.it.script.v2.api') && hasProfile('groovy-2.x')) app.groovyProfile = 'groovy-2.x';
    else if (m.source.includes('com.sap.gateway.ip.core.customdev.util') && hasProfile('groovy-1.x')) app.groovyProfile = 'groovy-1.x';
  }
  if (m.body !== '') {
    groovy.body = m.body;
    delete app.inputPaths.groovy;
  }
  if (m.headers.length) groovy.headers = m.headers;
  if (m.properties.length) groovy.properties = m.properties;
  app.tool = 'groovy';
  if (m.source !== '') {
    disarmGroovy('the imported script');
    app.status = `Imported from CPI Helper: ${sharedSummary(m)}. A script runs with your rights, so it has not run: press Ctrl+Enter to run it.`;
  } else {
    app.status = `Imported: ${sharedSummary(m)}.`;
    rerunIfLive('groovy');
  }
}

function importXslt(m: SharedMessage) {
  if (m.source !== '') {
    xslt.mappingPath = xslt.casePath = null;
    xslt.mapping = m.source;
    xslt.savedMapping = '';
  }
  if (m.body !== '') {
    xslt.body = m.body;
    delete app.inputPaths.xslt;
  }
  if (m.headers.length) xslt.headers = m.headers;
  if (m.properties.length) xslt.properties = m.properties;
  app.tool = 'xslt';
  app.status = `Imported${m.source !== '' ? ' from CPI Helper' : ''}: ${sharedSummary(m)}.`;
  if (m.source !== '') {
    const status = app.status;
    disarmXslt('the imported mapping'); // a mapping with java: calls waits for a run by hand, and says so
    if (xslt.armed) app.status = status;
  }
  rerunIfLive('xslt');
}

/** Asks for the link or text to import, for the Import… buttons. */
export async function askImportMessage() {
  const r = await ask('Import a message: paste the link CPI Helper opened (GroovyDebugX, xsltDebugX), or a message as JSON', '');
  if (r && r.value.trim() !== '') await importMessage(r.value);
}

/**
 * Browser mode: a link from CPI Helper that points at this app (`…/#share/<data>`). The data leaves the
 * address at once, so a reload does not import it again and it is not kept in the history.
 */
export async function importFromAddress() {
  if (typeof location === 'undefined' || !location.hash.startsWith('#share/')) return;
  const link = location.href;
  history.replaceState(null, '', location.pathname + location.search);
  await importMessage(link);
}

// ------------------------------------------------------------------ platform data
// The mocked platform data of the project (platform/*.json) as tables in the Groovy tab. The files stay the
// source of truth: a table is read from its file and saved into it; until it is saved, its rows go with each
// run as the `platform` override a test case uses too, so a run always sees what the table shows.

interface PlatformSection<K extends PlatformKey> {
  rows: PlatformRows[K];
  /** The file's document as last read or saved; undefined without a file. */
  doc: unknown;
  /** JSON of the document the rows gave when they were read or saved: what "unsaved changes" compares with. */
  saved: string;
  /** Why the file could not be read as a table (not JSON, another shape); the table is off then. */
  loadError: string;
  exists: boolean;
}

function emptySection<K extends PlatformKey>(key: K): PlatformSection<K> {
  return { rows: [] as unknown as PlatformRows[K], doc: undefined, saved: JSON.stringify(platformDoc(key, [] as unknown as PlatformRows[K], undefined)), loadError: '', exists: false };
}

export const platform = $state({
  valueMappings: emptySection('valueMappings'),
  credentials: emptySection('credentials'),
  partnerDirectory: emptySection('partnerDirectory'),
  numberRanges: emptySection('numberRanges'),
  /** The keystore is a file next to keystore.json: shown, not edited as a table. */
  keystoreExists: false,
});

/** The document a section's rows give, or what is wrong with them (a number range's "next" that is no number). */
export function platformSectionDoc(key: PlatformKey): { doc: unknown; error: string } {
  const sec = platform[key] as PlatformSection<PlatformKey>;
  try {
    return { doc: platformDoc(key, sec.rows, sec.doc), error: '' };
  } catch (e) {
    return { doc: undefined, error: (e as Error).message };
  }
}

export function platformDirty(key: PlatformKey): boolean {
  const sec = platform[key];
  if (sec.loadError) return false;
  const { doc, error } = platformSectionDoc(key);
  return error !== '' || JSON.stringify(doc) !== sec.saved;
}

function dirtyPlatformFiles(): string[] {
  return PLATFORM_KEYS.filter(platformDirty).map((k) => PLATFORM_FILES[k]);
}

/**
 * Reads the project's platform files into the tables. Tables with unsaved changes are left alone unless
 * `force` (a project that opens). Without a project there is nothing to read: the rows are in memory only.
 */
export async function loadPlatform(force = false) {
  if (!app.project) return;
  platform.keystoreExists = fileExists(PLATFORM_FILES.keystore);
  for (const key of PLATFORM_KEYS) {
    const sec = platform[key] as PlatformSection<PlatformKey>;
    if (!force && platformDirty(key)) {
      sec.exists = fileExists(PLATFORM_FILES[key]); // the file may be gone while the table has changes
      continue;
    }
    const before = JSON.stringify(sec.rows);
    const next = emptySection(key) as PlatformSection<PlatformKey>;
    next.exists = fileExists(PLATFORM_FILES[key]);
    if (next.exists) {
      try {
        next.doc = parsePlatformFile(await readFile(PLATFORM_FILES[key])); // numbers keep their text
        next.rows = platformRows(key, next.doc);
        next.saved = JSON.stringify(platformDoc(key, next.rows, next.doc));
      } catch (e) {
        next.doc = undefined;
        next.rows = [];
        next.loadError = (e as Error).message;
      }
    }
    // Rows typed while the file was read stay.
    if (force || JSON.stringify(sec.rows) === before) Object.assign(sec, next);
  }
}

function resetPlatform() {
  for (const key of PLATFORM_KEYS) Object.assign(platform[key], emptySection(key));
  platform.keystoreExists = false;
}

export async function savePlatform(key: PlatformKey): Promise<boolean> {
  if (!app.project) {
    app.error = 'Open a project folder to save platform data.';
    return false;
  }
  const sec = platform[key] as PlatformSection<PlatformKey>;
  const { doc, error } = platformSectionDoc(key);
  if (error) {
    app.error = error;
    return false;
  }
  const path = PLATFORM_FILES[key];
  try {
    const content = platformFileText(doc);
    await writeFile(path, content);
    sec.doc = doc;
    sec.saved = JSON.stringify(doc);
    sec.exists = true;
    // The same file open in the File tab follows, unless it has changes of its own.
    if (fileView.path === path && fileView.content === fileView.saved) fileView.content = fileView.saved = content;
    app.status = 'Saved ' + path;
    await refreshFiles();
    return true;
  } catch (e) {
    fail(e);
    return false;
  }
}

/**
 * The run's `platform` object: every table with unsaved changes (without a project: every table with rows),
 * then the open test case's own override, which wins key by key as in the test runner.
 */
function platformOverride(): Record<string, unknown> | undefined {
  const out: Record<string, unknown> = {};
  for (const key of PLATFORM_KEYS) {
    if (!platformDirty(key)) continue;
    const { doc, error } = platformSectionDoc(key);
    if (error) throw new Error(error);
    out[key] = platformRunData(doc);
  }
  Object.assign(out, groovy.casePlatform ?? {});
  return Object.keys(out).length ? out : undefined;
}

// ------------------------------------------------------------------ project

export async function open(dir: string, initProject = false) {
  app.error = '';
  // The project's platform files take the tables' place; rows typed without a project would otherwise look
  // like unsaved changes of files they never came from.
  const fresh = !app.project;
  if (fresh && dirtyPlatformFiles().length
    && !(await confirm('The project\'s platform files replace the platform data entered without a project. Open it?'))) return;
  const r = await openProject(dir, initProject);
  if (app.project?.root !== r.root) scriptMemory.clear(); // its paths belong to the other project
  app.project = { root: r.root, files: r.files };
  if (fresh) resetPlatform(); // at once: no run of the opened project uses the rows typed before it
  safeSet('cpiwb.project', r.root);
  await loadIflowSteps();
  if (!r.isProject && !initProject) app.status = 'Folder has no cpi-workbench.json – it still works; use "Init" to add the standard folders.';
  else app.status = 'Project opened: ' + r.root;
  // Last: the file tree is up by now, and a file opened from it sets the status line after this one.
  await loadPlatform();
}

export async function closeProject() {
  // Mappings and scripts stay in their editors as unsaved text; the File tab and the platform tables close
  // with the project.
  if (!(await mayDiscard('file'))) return;
  const unsaved = dirtyPlatformFiles();
  if (unsaved.length && !(await confirm(`Discard the unsaved platform data (${unsaved.join(', ')})?`))) return;
  await closeEngineProject();
  app.project = null;
  scriptMemory.clear();
  safeSet('cpiwb.project', null);
  xslt.mappingPath = null;
  xslt.casePath = null;
  groovy.scriptPath = null;
  groovy.casePath = null;
  groovy.casePlatform = null;
  xsd.schemaPath = null;
  xsd.casePath = null;
  closeFileView();
  resetPlatform();
  app.inputPaths = {};
  app.iflowSteps = [];
}

export async function refreshFiles() {
  if (!app.project) return;
  const r = await call('project.list');
  app.project.files = r.files;
  await loadPlatform(); // a platform file may have been saved, renamed or deleted
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
  app.status = `Imported ${r.imported} (${r.files} files, ${r.steps.length} steps found)`;
}

/** Opens the file of an imported iFlow step with the step's settings (function, script version). */
export async function openIflowStep(step: IflowStep) {
  if (step.config) return openFlowStep(step);
  if (step.type === 'mmap') {
    const [first, ...rest] = step.scripts ?? [];
    if (!first) {
      app.status = `Message Mapping "${step.name}": ${step.note ?? 'not simulated.'}`;
      return;
    }
    // The script opens with its first Message Mapping function (see runGroovy), on 1.x (F41).
    pickMappingFunction = true;
    await openFile(first, { profile: step.profile });
    app.status = `Message Mapping "${step.name}" is not simulated; opened its script ${first.split('/').pop()}`
      + (rest.length ? ` (also: ${rest.map((p) => p.split('/').pop()).join(', ')})` : '') + '. Press Ctrl+Enter to run it.';
    return;
  }
  if (!step.file || !step.fileFound) {
    app.error = step.note ? `Step "${step.name}": ${step.note}`
      : `The file of step "${step.name}" was not found in the export (${step.file ?? 'no reference'}).`;
    return;
  }
  // The step's function and runtime go in with the script, so a live run uses them from the start.
  await openFile(step.file, step.type === 'groovy' ? { functionName: step.function ?? 'processData', profile: step.profile } : {});
}

export async function readFile(path: string): Promise<string> {
  return (await call('file.read', { path })).content;
}

export async function writeFile(path: string, content: string) {
  await call('file.write', { path, content });
}

/** The editor a file opens in, or null for inputs (they replace the input body, not an editor). */
function editorFor(path: string): Editor | null {
  const lower = path.toLowerCase();
  if (lower.endsWith('.xsl') || lower.endsWith('.xslt')) return 'xslt';
  if (lower.endsWith('.groovy') || lower.endsWith('.gsh')) return 'groovy';
  if (lower.endsWith('.xsd')) return 'xsd';
  return path.startsWith('inputs/') ? null : 'file';
}

type Editor = 'xslt' | 'groovy' | 'xsd' | 'file';

/** Asks before an editor's unsaved changes are replaced; true when nothing would be lost. */
export async function mayDiscard(editor: Editor | null): Promise<boolean> {
  const [dirty, name] = editor === 'xslt' ? [xslt.mapping !== xslt.savedMapping, xslt.mappingPath ?? 'the unsaved mapping']
    : editor === 'groovy' ? [groovy.script !== groovy.savedScript, groovy.scriptPath ?? 'the unsaved script']
    : editor === 'xsd' ? [xsd.schema !== xsd.savedSchema, xsd.schemaPath ?? 'the unsaved schema']
    : editor === 'file' ? [fileView.path !== null && fileView.content !== fileView.saved, fileView.path]
    : [false, null];
  return !dirty || confirm(`Discard the unsaved changes to ${name}?`);
}

export interface OpenOptions {
  /** The caller already asked about unsaved changes. */
  discardChecked?: boolean;
  /** Groovy step settings (from an iFlow step); a plain script runs processData. */
  functionName?: string;
  profile?: Profile;
}

/**
 * The function and arguments each Groovy script had when another one was opened, so that opening it again
 * (e.g. from the file tree) brings them back. Kept for the session only.
 */
const scriptMemory = new Map<string, { functionName: string; args: (string | null)[] }>();

function rememberScript() {
  if (groovy.scriptPath && !groovy.casePath) {
    scriptMemory.set(groovy.scriptPath, { functionName: groovy.functionName, args: [...groovy.args] });
  }
}

function isOpenInItsTab(path: string): boolean {
  const lower = path.toLowerCase();
  if (lower.endsWith('.groovy') || lower.endsWith('.gsh')) return groovy.scriptPath === path && !groovy.casePath;
  if (lower.endsWith('.xsl') || lower.endsWith('.xslt')) return xslt.mappingPath === path && !xslt.casePath;
  if (lower.endsWith('.xsd')) return xsd.schemaPath === path && !xsd.casePath;
  return false;
}

async function reopen(path: string) {
  const lower = path.toLowerCase();
  const content = await readFile(path);
  if (lower.endsWith('.groovy') || lower.endsWith('.gsh')) {
    if (groovy.script === groovy.savedScript && content !== groovy.savedScript) {
      groovy.script = content;
      groovy.savedScript = content;
      disarmGroovy(path); // changed on disk: code the user has not started yet
    }
    app.tool = 'groovy';
    if (!groovy.result) rerunIfLive('groovy');
  } else if (lower.endsWith('.xsd')) {
    if (xsd.schema === xsd.savedSchema && content !== xsd.savedSchema) {
      xsd.schema = content;
      xsd.savedSchema = content;
    }
    app.tool = 'xsd';
  } else {
    if (xslt.mapping === xslt.savedMapping && content !== xslt.savedMapping) {
      xslt.mapping = content;
      xslt.savedMapping = content;
      disarmXslt(path); // changed on disk: java: calls the user has not started yet
    }
    app.tool = 'xslt';
    if (!xslt.result) rerunIfLive('xslt');
  }
}

export async function openFile(path: string, opts: OpenOptions = {}) {
  try {
    app.error = '';
    const lower = path.toLowerCase();
    if (lower.endsWith('case.json')) return await loadCase(path);
    // The file that is open in its tab (not as part of a test case): go back to it with its settings, and
    // take the file's content if it changed on disk while the editor has no unsaved changes.
    if (!opts.functionName && !opts.profile && isOpenInItsTab(path)) return await reopen(path);
    if (!opts.discardChecked && !(await mayDiscard(editorFor(path)))) return;
    const content = await readFile(path);
    if (lower.endsWith('.xsl') || lower.endsWith('.xslt')) {
      xslt.mappingPath = path;
      xslt.mapping = content;
      xslt.savedMapping = content;
      xslt.casePath = null;
      app.tool = 'xslt';
      disarmXslt(path);
      rerunIfLive('xslt');
    } else if (lower.endsWith('.groovy') || lower.endsWith('.gsh')) {
      rememberScript(); // the script that was open, before its path changes
      groovy.scriptPath = path;
      groovy.script = content;
      groovy.savedScript = content;
      groovy.casePath = null;
      groovy.casePlatform = null;
      const last = opts.functionName ? undefined : scriptMemory.get(path);
      groovy.functionName = opts.functionName ?? last?.functionName ?? 'processData';
      groovy.mapping = null;
      groovy.functions = [];
      groovy.stepFunction = null;
      groovy.args = last ? [...last.args] : [];
      forgetGroovyRun();
      // A script with only mapping functions opens with its first one (read from the text, nothing runs); if the
      // text says nothing, the first run picks one instead of failing on processData.
      if (!opts.functionName && !last) {
        const fns = scriptFunctions(content);
        const first = fns.find((f) => f.kind === 'mapping');
        if (first && !fns.some((f) => f.name === 'processData')) {
          groovy.functionName = first.name;
          pickMappingFunction = false;
        } else {
          pickMappingFunction = true;
        }
      }
      if (opts.profile && hasProfile(opts.profile)) app.groovyProfile = opts.profile;
      else if (content.includes('com.sap.it.script.v2.api') && hasProfile('groovy-2.x')) app.groovyProfile = 'groovy-2.x';
      else if (content.includes('com.sap.gateway.ip.core.customdev.util') && hasProfile('groovy-1.x')) app.groovyProfile = 'groovy-1.x';
      app.tool = 'groovy';
      disarmGroovy(path);
    } else if (lower.endsWith('.xsd')) {
      xsd.schemaPath = path;
      xsd.schema = content;
      xsd.savedSchema = content;
      xsd.casePath = null;
      app.tool = 'xsd';
    } else if (path.startsWith('inputs/')) {
      setInput(path, content);
    } else {
      fileView.path = path;
      fileView.content = content;
      fileView.saved = content;
      app.tool = 'file';
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
  const path = xslt.mappingPath ?? await askNewPath('mapping', null, 'mappings/mapping.xsl');
  if (!path) return false;
  await writeFile(path, xslt.mapping);
  xslt.mappingPath = path;
  xslt.savedMapping = xslt.mapping;
  app.status = 'Saved ' + path;
  await refreshFiles();
  return true;
}

/** A project file that is not a step (platform data, configuration, iFlow files) open in the File tab. */
export const fileView = $state({ path: null as string | null, content: '', saved: '' });

export function closeFileView() {
  fileView.path = null;
  fileView.content = fileView.saved = '';
  if (app.tool === 'file') app.tool = app.stepTool;
}

/**
 * Closes the file open in a step editor, and the test case it came from: the editor is left empty and without a
 * path, so Save asks for a new one. The file on disk stays as it is. Asks first when there are unsaved changes.
 */
export async function closeEditorFile(editor: 'xslt' | 'groovy' | 'xsd'): Promise<boolean> {
  if (!(await mayDiscard(editor))) return false;
  if (editor === 'xslt') {
    const path = xslt.casePath ?? xslt.mappingPath;
    if (xslt.casePath) {
      // The input came with the case: it goes with it.
      xslt.body = '';
      xslt.headers = [];
      xslt.properties = [];
    }
    runSeq++; // a run still under way belongs to the closed file
    xslt.running = false;
    xslt.mappingPath = xslt.casePath = null;
    xslt.mapping = xslt.savedMapping = '';
    xslt.result = xslt.compare = null;
    xslt.armed = true;
    app.status = path ? 'Closed ' + path : 'Closed the unsaved mapping';
  } else if (editor === 'groovy') {
    const path = groovy.casePath ?? groovy.scriptPath;
    if (groovy.casePath) {
      groovy.body = '';
      groovy.headers = [];
      groovy.properties = [];
      groovy.attachments = [];
      groovy.soapHeaders = [];
    } else {
      rememberScript(); // opening it again brings back its function and arguments
    }
    forgetGroovyRun();
    groovy.scriptPath = groovy.casePath = null;
    groovy.casePlatform = null;
    groovy.script = groovy.savedScript = '';
    groovy.functionName = 'processData';
    groovy.mapping = null;
    groovy.functions = [];
    groovy.stepFunction = null;
    groovy.args = [];
    groovy.result = groovy.compare = null;
    groovy.armed = true;
    app.status = path ? 'Closed ' + path : 'Closed the unsaved script';
  } else {
    const path = xsd.casePath ?? xsd.schemaPath;
    if (xsd.casePath) xsd.body = '';
    xsd.schemaPath = xsd.casePath = null;
    xsd.schema = xsd.savedSchema = '';
    xsd.result = null;
    app.status = path ? 'Closed ' + path : 'Closed the unsaved schema';
  }
  return true;
}

/**
 * Closes the file a step tool's input body was loaded from: the body is emptied and no longer names the file.
 * The body is a copy, so the file on disk stays as it is; headers and properties do not come from the file and stay.
 */
export function closeInput(tool: StepTool) {
  const path = app.inputPaths[tool];
  if (tool === 'xsd') {
    xsd.body = '';
    xsd.result = null;
  } else if (tool === 'xpath') {
    xpath.body = '';
    xpath.result = null;
  } else if (tool === 'flow') {
    flow.body = '';
    flow.cmResult = flow.routerResult = flow.splitResult = flow.filterResult = flow.gatherResult = flow.converterResult = null;
  } else if (tool === 'groovy') {
    forgetGroovyRun();
    groovy.body = '';
    groovy.result = groovy.compare = null;
  } else {
    runSeq++;
    xslt.running = false;
    xslt.body = '';
    xslt.result = xslt.compare = null;
  }
  delete app.inputPaths[tool];
  if (path) app.status = 'Closed input ' + path;
}

/** Closes the File tab, asking first when it has unsaved changes; the file on disk stays as it is. */
export async function closeFileTab(): Promise<boolean> {
  if (!(await mayDiscard('file'))) return false;
  closeFileView();
  return true;
}

export async function saveFileView(): Promise<boolean> {
  if (!fileView.path) return false;
  await writeFile(fileView.path, fileView.content);
  fileView.saved = fileView.content;
  app.status = 'Saved ' + fileView.path;
  if (fileView.path.startsWith('platform/')) await loadPlatform(); // the Groovy tab's tables follow the file
  return true;
}

function stepBody(tool: StepTool) {
  return tool === 'xsd' ? xsd.body : tool === 'xpath' ? xpath.body : tool === 'flow' ? flow.body
    : tool === 'groovy' ? groovy.body : xslt.body;
}

function setInput(path: string, content: string) {
  // XSLT, XSD and XPath read XML only; a JSON body can only feed a Groovy script (or the Flow Steps, on the same engine).
  const json = /\.json$/i.test(path) || /^\s*[[{]/.test(content);
  if (json && !app.profiles.some((p) => p.kind === 'groovy' && p.available)) {
    app.error = `${path} is JSON: only a Groovy script takes a JSON body, and no Groovy engine is available.`;
    return;
  }
  const tool: StepTool = json && app.stepTool !== 'flow' ? 'groovy' : app.stepTool;
  if (tool === 'xsd') xsd.body = content;
  else if (tool === 'xpath') xpath.body = content;
  else if (tool === 'flow') flow.body = content;
  else if (tool === 'groovy') groovy.body = content;
  else xslt.body = content;
  app.inputPaths[tool] = path;
  app.tool = tool;
  rerunIfLive(tool);
  app.status = `Loaded ${path} as input body`;
}

/** Loads any file as the input body of the current step tool. */
export async function useAsInput(path: string) {
  try {
    setInput(path, await readFile(path));
  } catch (e) {
    fail(e);
  }
}

/** Saves the input body of the current step tool under inputs/, so it survives opening other files. */
export async function saveInput() {
  if (!app.project) {
    app.error = 'Open a project folder to save files.';
    return;
  }
  const body = stepBody(app.stepTool);
  const ext = /^\s*[[{]/.test(body) ? 'json' : /^\s*</.test(body) ? 'xml' : 'txt';
  const r = await ask('Save input body as (path inside the project)', `inputs/input.${ext}`);
  if (!r) return;
  try {
    if (!(await mayOverwrite(r.value))) return;
    await writeFile(r.value, body);
    app.inputPaths[app.stepTool] = r.value;
    app.status = 'Saved input as ' + r.value;
    await refreshFiles();
  } catch (e) {
    fail(e);
  }
}

/** Points the editors that show `from` to `to`; with `to` null the editor keeps the text as unsaved. */
function movePaths(from: string, to: string | null) {
  if (xslt.mappingPath === from) {
    xslt.mappingPath = to;
    if (!to) xslt.savedMapping = '';
  }
  if (groovy.scriptPath === from) {
    groovy.scriptPath = to;
    if (!to) groovy.savedScript = '';
  }
  if (xsd.schemaPath === from) {
    xsd.schemaPath = to;
    if (!to) xsd.savedSchema = '';
  }
  if (xslt.casePath === from) xslt.casePath = to;
  if (groovy.casePath === from) groovy.casePath = to;
  if (xsd.casePath === from) xsd.casePath = to;
  if (fileView.path === from) {
    if (to) fileView.path = to;
    else closeFileView();
  }
}

export async function deleteFile(path: string) {
  if (!(await confirm(`Delete ${path}?`))) return;
  try {
    await call('file.delete', { path });
    movePaths(path, null);
    app.status = 'Deleted ' + path;
    await refreshFiles();
  } catch (e) {
    fail(e);
  }
}

/** Rename and copy go through read and write: the engines have no move call, and the tree lists text files only. */
export async function copyFile(path: string, move: boolean) {
  const r = await ask(move ? `Rename ${path} to` : `Copy ${path} to`, path);
  if (!r || r.value === path) return;
  // The same file under another spelling (./a.xsl, or A.xsl where the file system ignores case): writing it and
  // then deleting the "old" name would delete the file itself.
  if (pathKey(r.value) === pathKey(path)) {
    app.error = `${r.value} is the same file as ${path} (names that differ only in upper and lower case or in ./ are not ${move ? 'renamed' : 'copied'} here).`;
    return;
  }
  try {
    if (!(await mayOverwrite(r.value))) return;
    await writeFile(r.value, await readFile(path));
    if (move) {
      await call('file.delete', { path });
      movePaths(path, r.value);
    }
    app.status = (move ? 'Renamed ' : 'Copied ') + path + ' to ' + r.value;
    await refreshFiles();
  } catch (e) {
    fail(e);
  }
}

function hasProfile(id: Profile) {
  return !!app.profiles.find((p) => p.id === id && p.available);
}

export async function saveScript(): Promise<boolean> {
  if (!app.project) {
    app.error = 'Open a project folder to save files.';
    return false;
  }
  const path = groovy.scriptPath ?? await askNewPath('script', null, 'scripts/script.groovy');
  if (!path) return false;
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
    const p = await askNewPath('schema', null, 'schemas/schema.xsd');
    if (!p) return false;
    xsd.schemaPath = p;
  }
  await writeFile(xsd.schemaPath!, xsd.schema);
  xsd.savedSchema = xsd.schema;
  app.status = 'Saved ' + xsd.schemaPath;
  await refreshFiles();
  return true;
}

function fileExists(path: string): boolean {
  const key = pathKey(path);
  return !!app.project?.files.some((f) => pathKey(f.path) === key);
}

/**
 * A name no file has yet: `path` itself (or path + suffix), else -2, -3, ...
 * mappings/x.xsl with suffix '-copy' -> mappings/x-copy.xsl, mappings/x-copy-2.xsl, ...
 */
export function freePath(path: string, suffix = ''): string {
  const dot = path.lastIndexOf('.');
  const [stem, ext] = dot > path.lastIndexOf('/') ? [path.slice(0, dot), path.slice(dot)] : [path, ''];
  let name = `${stem}${suffix}${ext}`;
  for (let n = 2; fileExists(name); n++) name = `${stem}${suffix}-${n}${ext}`;
  return name;
}

/** True if no file has this path or the user agreed to overwrite it. */
export async function mayOverwrite(path: string): Promise<boolean> {
  // Files may have appeared outside the app since the tree was listed.
  await refreshFiles().catch(() => undefined);
  return !fileExists(path) || confirm(`${path} exists. Overwrite it?`);
}

/** Asks for a new path; an existing file is only overwritten after a second question. */
async function askNewPath(what: string, path: string | null, fallback: string): Promise<string | null> {
  if (!app.project) {
    app.error = 'Open a project folder to save files.';
    return null;
  }
  const r = await ask(`Save ${what} as (path inside the project)`, path ? freePath(path, '-copy') : freePath(fallback));
  if (!r) return null;
  if ((!path || pathKey(r.value) !== pathKey(path)) && !(await mayOverwrite(r.value))) return null;
  return r.value;
}

export async function saveMappingAs(): Promise<boolean> {
  const path = await askNewPath('mapping', xslt.mappingPath, 'mappings/mapping.xsl');
  if (!path) return false;
  await writeFile(path, xslt.mapping);
  xslt.mappingPath = path;
  xslt.savedMapping = xslt.mapping;
  xslt.casePath = null;
  app.status = 'Saved ' + path;
  await refreshFiles();
  return true;
}

export async function saveScriptAs(): Promise<boolean> {
  const path = await askNewPath('script', groovy.scriptPath, 'scripts/script.groovy');
  if (!path) return false;
  await writeFile(path, groovy.script);
  groovy.scriptPath = path;
  groovy.savedScript = groovy.script;
  groovy.casePath = null;
  app.status = 'Saved ' + path;
  await refreshFiles();
  return true;
}

export async function saveSchemaAs(): Promise<boolean> {
  const path = await askNewPath('schema', xsd.schemaPath, 'schemas/schema.xsd');
  if (!path) return false;
  await writeFile(path, xsd.schema);
  xsd.schemaPath = path;
  xsd.savedSchema = xsd.schema;
  xsd.casePath = null;
  app.status = 'Saved ' + path;
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

/** The selected Groovy function if it is a Message Mapping function, else null (a script step). */
export interface SelectedMapping {
  function: string;
  /** Engine spelling: String, String[], Output, MappingContext. */
  parameters: string[];
  /** Parameter names from the script text, '' where unknown. */
  names: string[];
  /** Known from a run (engine) or only from the text. */
  source: 'engine' | 'text';
}

/**
 * The function the Groovy tab runs, as a Message Mapping function: from the last run when that called it,
 * otherwise from the script text (nothing has to run for that). The engine's answer wins where they differ.
 */
export function selectedMapping(): SelectedMapping | null {
  const fn = groovy.functionName || 'processData';
  const parsed = scriptFunctions(groovy.script).filter((f) => f.name === fn);
  if (groovy.mapping?.function === fn) {
    const parameters = groovy.mapping.parameters;
    const match = parsed.find((f) => f.parameters.join() === parameters.join());
    return { function: fn, parameters, names: match ? match.params.map((p) => p.name) : parameters.map(() => ''), source: 'engine' };
  }
  if (groovy.stepFunction === fn) return null;
  const f = parsed.find((p) => p.kind === 'mapping');
  return f ? { function: fn, parameters: f.parameters, names: f.params.map((p) => p.name), source: 'text' } : null;
}

function mappingArgsFor(m: SelectedMapping | null): unknown[] {
  return m ? mappingArgs(m.parameters, groovy.args) : [];
}

function orNone<T>(list: T[]): T[] | undefined {
  return list.length ? list : undefined;
}

function groovyRequest() {
  const fn = groovy.functionName || 'processData';
  const fileRow = app.project ? undefined : groovy.attachments.find((a) => a.kind === 'file' && a.file.trim() !== '');
  if (fileRow) throw new Error(`The attachment file ${fileRow.file} can only be read with a project open; open the project, or give the content as text.`);
  return {
    script: { content: groovy.script, path: groovy.scriptPath ?? 'script.groovy' },
    function: fn,
    args: mappingArgsFor(selectedMapping()),
    input: {
      body: groovy.body, headers: clean(groovy.headers), properties: clean(groovy.properties),
      // Files of attachments are project-relative here; the engine reads them (inside the project only).
      attachments: orNone(attachmentInput(groovy.attachments)), soapHeaders: orNone(soapHeaderInput(groovy.soapHeaders)),
    },
    options: { bodyType: groovy.bodyType },
    platform: platformOverride(),
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

/** A mapping, script or input loaded from a file re-runs like a change typed in the editor. */
function rerunIfLive(tool: StepTool) {
  if (tool === 'xslt' && xslt.live) autoRunXslt();
  else if (tool === 'groovy' && groovy.live) autoRunGroovy();
}

export async function runXslt() {
  const seq = ++runSeq;
  xslt.armed = true;
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

/** Another script or test case is opened: a run still under way belongs to the old one and must not land here. */
function forgetGroovyRun() {
  groovySeq++;
  groovy.running = false;
}
/** Set when a Message Mapping step opens its script: the first run picks a mapping function if there is no processData. */
let pickMappingFunction = false;

export async function runGroovy() {
  const seq = ++groovySeq;
  groovy.armed = true;
  groovy.running = true;
  app.error = '';
  try {
    // The arguments go with the parameters known before the run (the engine's or the text's); if the run finds
    // others, it runs again with them.
    const sent = selectedMapping();
    const params = groovyRequest();
    const [r, c] = await Promise.all([
      call<GroovyResult>('groovy.run', params, app.groovyProfile),
      groovy.compareOn ? compareRun<GroovyResult>('groovy.run', params, app.groovyProfile) : Promise.resolve(null),
    ]);
    if (seq === groovySeq) {
      groovy.result = r;
      groovy.compare = c;
      const pick = pickMappingFunction;
      pickMappingFunction = false;
      if (pick && !r.ok && r.failedPhase === 'compile' && r.functions?.length && !r.mapping) {
        groovy.functionName = r.functions[0].slice(0, r.functions[0].indexOf('('));
        return void runGroovy();
      }
      // A compile error says nothing about the function; keep what the last run found.
      if (r.functions) groovy.functions = r.functions;
      if (r.mapping) {
        // Arguments typed for another function do not belong to this one (a test case's do: mapping is null then).
        if (groovy.mapping && groovy.mapping.function !== r.mapping.function) groovy.args = [];
        groovy.mapping = { function: r.mapping.function, parameters: r.mapping.parameters };
        groovy.stepFunction = null;
        const same = sent?.parameters.join() === r.mapping.parameters.join();
        if (!same && groovy.args.some((a) => a === null || a.trim() !== '')) return void runGroovy();
      } else if (r.failedPhase !== 'compile') {
        if (groovy.mapping) groovy.args = [];
        groovy.mapping = null;
        // The engine called it as a script step: that wins over the reading of the text.
        groovy.stepFunction = params.function;
      }
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

// Ctrl+Enter can start a run while one is open; only the latest run's answer is shown, as for XSLT and Groovy.
let xsdSeq = 0;

export async function runXsd() {
  if (!xsd.schema.trim()) {
    app.error = 'Open an .xsd from the project or paste a schema first.';
    return;
  }
  const seq = ++xsdSeq;
  xsd.running = true;
  app.error = '';
  try {
    const r = await call('xsd.validate', {
      schema: { content: xsd.schema, path: xsd.schemaPath ?? 'schema.xsd' },
      input: { body: xsd.body },
    }, app.profile);
    if (seq === xsdSeq) xsd.result = r;
  } catch (e) {
    if (seq === xsdSeq) fail(e);
  } finally {
    if (seq === xsdSeq) xsd.running = false;
  }
}

let xpathSeq = 0;

export async function runXpath() {
  const seq = ++xpathSeq;
  xpath.running = true;
  app.error = '';
  const ns: Record<string, string> = {};
  for (const r of xpath.namespaces) if (r.name.trim() && !r.disabled) ns[r.name.trim()] = r.value;
  try {
    const r = await call('xpath.eval', { expression: xpath.expression, version: xpath.version,
      namespaces: ns, input: { body: xpath.body } }, app.profile);
    if (seq === xpathSeq) xpath.result = r;
  } catch (e) {
    if (seq === xpathSeq) fail(e);
  } finally {
    if (seq === xpathSeq) xpath.running = false;
  }
}

// ------------------------------------------------------------------ flow steps
// XPath runs in the XML engine (Saxon, XPath 3.1 as SAP documents for the Content Modifier), Camel Simple and
// the Content Modifier in the Groovy engine's Camel. The Router is evaluated here, route by route.
// The MCP server repeats these calls (app/src-tauri/src/mcp/flow.rs); change the two together.

function namespaceMap() {
  const ns: Record<string, string> = {};
  for (const r of xpath.namespaces) if (r.name.trim() && !r.disabled) ns[r.name.trim()] = r.value;
  return ns;
}

/** XPath and the splitter use CPI's Saxon line (9.9) when it is installed, whatever the XSLT tab is set to. */
function flowXmlProfile(): Profile {
  return hasProfile('saxon-he-9.9') ? 'saxon-he-9.9' : app.profile;
}

function flowInput() {
  return { body: flow.body, headers: clean(flow.headers), properties: clean(flow.properties) };
}

/**
 * Everything a flow run reads, taken when it starts: the message, the namespaces, the engines and the step's
 * settings. Edits while the run waits for the engines do not leak into it (the MCP server's Ctx does the same).
 */
interface FlowRun {
  input: ReturnType<typeof flowInput>;
  namespaces: Record<string, string>;
  xml: Profile;
  camel: Profile;
}

async function xpathValue(run: FlowRun, expression: string, as: 'boolean' | 'string' | 'nodes', input = run.input) {
  // Headers and properties are XPath variables ($name), as Camel's XPath resolver provides them (F39).
  const r = await call('xpath.eval', { expression, version: '3.1', as, namespaces: run.namespaces, input }, run.xml);
  if (!r.ok) throw new Error(r.diagnostics?.[0]?.message ?? 'XPath failed');
  return r.value as string;
}

async function simpleEval(run: FlowRun, expression: string, mode: 'condition' | 'expression') {
  const r = await call('simple.eval', { expression, mode, input: run.input }, run.camel);
  if (!r.ok) throw new Error(r.diagnostics?.[0]?.message ?? 'Camel Simple failed');
  return r as { value: string | null; evaluated?: string };
}

// A run's result goes to the step it was started for, and only if no newer run or newly opened step came since.
let flowSeq = 0;
const flowResultKey = { contentModifier: 'cmResult', filter: 'filterResult', router: 'routerResult', splitter: 'splitResult', gather: 'gatherResult',
  converter: 'converterResult' } as const;
const flowConfigKey = { contentModifier: 'cm', filter: 'filter', router: 'router', splitter: 'splitter', gather: 'gather', converter: 'converter' } as const;

export async function runFlowStep() {
  const seq = ++flowSeq;
  const step = flow.step;
  const run: FlowRun = { input: $state.snapshot(flowInput()), namespaces: namespaceMap(), xml: flowXmlProfile(), camel: flow.camel };
  const config: any = $state.snapshot(flow[flowConfigKey[step]]);
  flow.running = true;
  app.error = '';
  try {
    const r = step === 'contentModifier' ? await runContentModifier(run, config)
      : step === 'filter' ? await runFilter(run, config)
      : step === 'router' ? await runRouter(run, config)
      : step === 'splitter' ? await runSplitter(run, config)
      : step === 'converter' ? await runConverter(run, config)
      : await runGather(run, config);
    if (seq === flowSeq) flow[flowResultKey[step]] = r;
  } catch (e) {
    if (seq === flowSeq) fail(e);
  } finally {
    if (seq === flowSeq) flow.running = false;
  }
}

async function runContentModifier(run: FlowRun, cm: typeof flow.cm) {
  // XPath rows read the incoming body, which the step changes only after all header and property rows. Rows run
  // top to bottom (SAP), so an XPath that uses $name sees the headers and properties of the rows above it.
  const input = run.input;
  let state = input;
  const done: Record<'headers' | 'properties', any[]> = { headers: [], properties: [] };
  let pending: Record<'headers' | 'properties', any[]> = { headers: [], properties: [] };
  for (const table of ['headers', 'properties'] as const) {
    for (const r of cm[table]) {
      let row: any = { ...r };
      if (r.action === 'Create' && r.type === 'xpath') {
        if (r.value.includes('$') && (pending.headers.length || pending.properties.length)) {
          const s = await call('contentModifier.run', { ...pending, bodyType: 'constant', body: '', input: state }, run.camel);
          if (s.ok) state = { body: input.body, headers: s.output.headers, properties: s.output.properties };
          pending = { headers: [], properties: [] };
        }
        try {
          row = { ...r, resolved: await xpathValue(run, r.value, r.datatype.startsWith('org.w3c.dom.') ? 'nodes' : 'string', state) };
        } catch (e) {
          throw new Error(`XPath of ${r.name}: ${(e as Error).message}`);
        }
      }
      done[table].push(row);
      pending[table].push(row);
    }
  }
  return await call('contentModifier.run', {
    ...done, bodyType: cm.bodyType, body: cm.body, input,
  }, run.camel);
}

async function runRouter(run: FlowRun, router: typeof flow.router) {
  const routes: RouteResult[] = router.routes.map((r) => ({ status: r.default ? 'default' : 'not evaluated' }));
  if (new Set(router.routes.filter((r) => !r.default).map((r) => r.expressionType)).size > 1) {
    return { routes, diagnostics: [],
      taken: 'Error: the routes mix XML and Non-XML conditions; SAP: "the evaluation fails" (Define Router).' };
  }
  let taken = '';
  for (const [i, r] of router.routes.entries()) {
    if (r.default) continue;
    try {
      const s = r.expressionType === 'xml' ? { value: await xpathValue(run, r.condition, 'boolean') } : await simpleEval(run, r.condition, 'condition');
      routes[i] = { status: s.value === 'true' ? 'true' : 'false', camel: (s as { evaluated?: string }).evaluated };
    } catch (e) {
      routes[i] = { status: 'error', message: (e as Error).message };
      taken = 'Error: the route condition failed, so CPI raises an exception.';
      break;
    }
    if (routes[i].status === 'true') {
      taken = r.name;
      break;
    }
  }
  if (!taken) {
    const d = router.routes.find((r) => r.default);
    taken = router.throwException ? 'No route matched: Throw Exception is set, so the Router raises an exception.'
      : d ? d.name + ' (default route)' : 'No route matched and there is no default route.';
  }
  return { routes, taken, diagnostics: [] };
}

async function runSplitter(run: FlowRun, s: typeof flow.splitter) {
  // XPath and grouping may refer to a header or property (SAP); Camel Simple resolves the reference first.
  const resolve = async (v: string) => (v.includes('${') ? (await simpleEval(run, v, 'expression')).value ?? '' : v);
  return await call('split.run', {
    splitter: s.splitter, expressionType: s.expressionType, expression: await resolve(s.expression),
    grouping: await resolve(String(s.grouping ?? '')), streaming: s.streaming, namespaces: run.namespaces, input: { body: run.input.body },
  }, run.xml);
}

async function runFilter(run: FlowRun, f: typeof flow.filter) {
  return await call('filter.run', {
    expression: await resolveSimpleFunctions(f.expression, async (e) => (await simpleEval(run, e, 'expression')).value ?? ''),
    valueType: f.valueType, namespaces: run.namespaces, input: run.input,
  }, run.xml);
}

async function runGather(run: FlowRun, g: typeof flow.gather) {
  return await call('gather.run', {
    messageType: g.messageType, algorithm: g.algorithm, sourceXPath: g.sourceXPath, targetXPath: g.targetXPath,
    namespaces: run.namespaces, messages: g.messages,
  }, run.xml);
}

async function runConverter(run: FlowRun, c: typeof flow.converter) {
  // SAP (CSV to XML 1.4): schema, target path and record marker may come from a header or property.
  const resolve = async (v: string) => (v.includes('${') ? (await simpleEval(run, v, 'expression')).value ?? '' : v);
  const csv = c.conversion === 'csvToXml';
  return await call('convert.run', {
    ...c,
    schema: csv ? await resolve(c.schema) : c.schema,
    targetPath: csv ? await resolve(c.targetPath) : c.targetPath,
    recordMarker: csv ? await resolve(c.recordMarker) : c.recordMarker,
    namespaces: run.namespaces, input: run.input,
  }, run.xml);
}

/** The splitter's messages go to the Gather step, as in an iFlow's Splitter → … → Gather. */
export function gatherSplitResult() {
  flow.gather.messages = (flow.splitResult?.messages ?? []).map((m: { body: string }) => m.body);
  flow.gatherResult = null;
  flow.step = 'gather';
}

/** A result message becomes the next input, to try steps one after another. */
export function useAsFlowInput(body: string, headers?: Row[], properties?: Row[]) {
  flow.body = body;
  if (headers) flow.headers = headers.map((r) => ({ ...r }));
  if (properties) flow.properties = properties.map((r) => ({ ...r }));
  delete app.inputPaths.flow;
}

function openFlowStep(step: IflowStep) {
  const c = step.config ?? {};
  const row = (r: any): CmRow => ({ action: r.action === 'Delete' ? 'Delete' : 'Create', name: r.name ?? '', type: r.type ?? 'constant',
    value: r.value ?? '', datatype: r.datatype ?? '', default: r.default ?? '' });
  if (step.type === 'contentModifier') {
    flow.cm = { headers: (c.headers ?? []).map(row), properties: (c.properties ?? []).map(row),
      bodyType: c.bodyType === 'expression' ? 'expression' : 'constant', body: c.body ?? '' };
  } else if (step.type === 'router') {
    flow.router = { routes: c.routes ?? [], throwException: !!c.throwException };
  } else if (step.type === 'filter') {
    flow.filter = { expression: c.expression ?? '', valueType: c.valueType ?? 'nodelist' };
  } else if (step.type === 'converter') {
    // The iFlow's lists become the tab's text fields (one entry per line). Settings the step does not have start
    // from the defaults, not from a converter opened before.
    flow.converter = { ...converterDefaults(), ...c,
      namespaceMapping: Array.isArray(c.namespaceMapping) ? c.namespaceMapping.map((m: any) => `${m.prefix} = ${m.namespace}`).join('\n') : c.namespaceMapping ?? '',
      arrayPaths: Array.isArray(c.arrayPaths) ? c.arrayPaths.join('\n') : c.arrayPaths ?? '' };
  } else if (step.type === 'gather') {
    // The messages stay: they come from the splitter, not from the iFlow.
    flow.gather = { ...flow.gather, messageType: c.messageType ?? 'sameXml', algorithm: c.algorithm ?? 'combine',
      sourceXPath: c.sourceXPath ?? '', targetXPath: c.targetXPath ?? '' };
  } else {
    flow.splitter = { splitter: c.splitter, expressionType: c.expressionType, expression: c.expression ?? '',
      grouping: c.grouping ?? '', streaming: c.streaming !== false };
  }
  flow.step = step.type as FlowStep;
  flow.sources[step.type as FlowStep] = `${step.iflow} · ${step.name || step.id}`;
  // Only this step's result is out of date; the other steps keep theirs.
  flow[flowResultKey[step.type as FlowStep]] = null;
  // A run still open for the step this replaces must not write its result here.
  flowSeq++;
  flow.running = false;
  app.tool = 'flow';
  app.status = `Loaded ${step.name || step.id} from ${step.iflow}`;
}

export async function formatXml(text: string): Promise<string> {
  const r = await call('xml.format', { text }, app.profile);
  if (!r.ok) throw new Error(r.diagnostics?.[0]?.message ?? 'Not well-formed XML');
  return r.text;
}

// ------------------------------------------------------------------ test cases

function joinPath(dir: string, rel: string): string {
  const parts = [...dir.split('/').filter(Boolean)];
  for (const p of rel.split('/')) {
    if (p === '..') parts.pop();
    else if (p !== '.' && p !== '') parts.push(p);
  }
  return parts.join('/');
}

async function loadGroovyCase(path: string, dir: string, tc: any) {
  rememberScript();
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
  groovy.args = (tc.args ?? []).map(argText);
  groovy.mapping = null;
  groovy.functions = [];
  groovy.stepFunction = null;
  forgetGroovyRun();
  if (tc.profile && hasProfile(tc.profile)) app.groovyProfile = tc.profile;
  // A case names attachment files relative to its folder.
  groovy.attachments = attachmentRows(tc.input?.attachments, (file) => joinPath(dir, file));
  groovy.soapHeaders = soapHeaderRows(tc.input?.soapHeaders);
  groovy.casePlatform = tc.platform && typeof tc.platform === 'object' ? tc.platform : null;
  groovy.casePath = path;
  app.tool = 'groovy';
  disarmGroovy('test case ' + (tc.name ?? path));
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
  if (!(await mayDiscard(tc.step === 'groovy' ? 'groovy' : tc.step === 'xsd' ? 'xsd' : 'xslt'))) return;
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
  disarmXslt('test case ' + (tc.name ?? path));
  if (xslt.armed) await runXslt();
}
