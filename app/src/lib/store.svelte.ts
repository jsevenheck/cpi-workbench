import { EXAMPLES } from './examples';
import type { IflowModel } from './iflowmodel';
import type { IndexEntry, IndexedIflow } from './whereused';
import { addRun, type HistoryEntry } from './history';
import { call, flowEngineCall, openProject, closeProject as closeEngineProject, availableProfiles, DEFAULT_PROFILE, EngineError, type Profile, type ProfileInfo } from './engine';
import type { Diagnostic, FileEntry, GroovyResult, Row, XsltResult } from './types';
import { argText, mappingArgs } from './queue';
import { scriptFunctions } from './functions';
import { bodyWarning, readShared, sharedSummary, type SharedMessage } from './share';
import { attachmentInput, attachmentRows, parsePlatformFile, platformDoc, platformFileText, platformRows, platformRunData, PLATFORM_FILES, PLATFORM_KEYS,
  soapHeaderInput, soapHeaderRows, type AttachmentRow, type PlatformKey, type PlatformRows, type SoapHeaderRow } from './platform';
import { toRows } from './rows';
import { isArchive, pathKey, projectPath } from './paths';
import { isExistsConflict } from './errors';
import { defaultedSettings, flowConfigKey, missingFlowRuntime as missingRuntime, flowStepDefaults, runStep, settingsFromIflowStep, type FlowRun, type FlowStep, type RouteResult } from './flowcalls';

export type StepTool = 'xslt' | 'groovy' | 'xsd' | 'xpath' | 'flow';

export const app = $state({
  project: null as null | { root: string; files: FileEntry[] },
  profiles: [] as ProfileInfo[],
  engineInfo: null as any,
  profile: DEFAULT_PROFILE as Profile,
  groovyProfile: 'groovy-1.x' as Profile,
  tool: 'xslt' as StepTool | 'file' | 'diagram' | 'whereused',
  /** The step tool last shown; inputs opened from the tree go to its body. */
  stepTool: 'xslt' as StepTool,
  /** The inputs/ file last loaded or saved as each tool's input body. */
  inputPaths: {} as Partial<Record<StepTool, string>>,
  status: '',
  error: '',
  busy: false,
  iflowSteps: [] as IflowStep[],
  /** The imported iFlows as a whole (processes, all elements, flows, layout), for the list and the Diagram tab. */
  iflowModels: [] as IflowModel[],
  /** The iFlow the Diagram tab shows and the element chosen in the list or the diagram (id null: none). */
  iflowSelected: null as { iflow: string; id: string | null } | null,
});

/** The Where used tab: a search over the imported iFlows and their parameters (engine `iflow.search`, `iflow.index`). */
export const whereUsed = $state({
  view: 'search' as 'search' | 'parameters',
  query: '',
  kind: '',
  /** Only this iFlow; empty: all. */
  iflow: '',
  hits: [] as IndexEntry[],
  /** The query the hits are for. */
  searched: '',
  index: [] as IndexedIflow[],
  error: '',
});

// Each search and each index load counts up; a late answer whose count is not the latest is dropped (also after a
// project was closed, which counts both up).
let whereUsedSeq = 0;
let indexSeq = 0;

/** Runs the Where used search for the tab's query, kind and iFlow. */
export async function searchIflows() {
  const seq = ++whereUsedSeq;
  const query = whereUsed.query.trim();
  if (!app.project || (!query && !whereUsed.kind)) {
    whereUsed.hits = [];
    whereUsed.searched = '';
    whereUsed.error = '';
    return;
  }
  try {
    const r = await call('iflow.search', { query, kind: whereUsed.kind || undefined, iflow: whereUsed.iflow || undefined });
    if (seq !== whereUsedSeq) return;
    whereUsed.hits = r.hits;
    whereUsed.searched = query;
    whereUsed.error = r.warnings?.join(' ') ?? '';
  } catch (e) {
    if (seq === whereUsedSeq) whereUsed.error = (e as Error).message;
  }
}

/** The parameters of the imported iFlows, for the Where used tab's Parameters view. */
export async function loadIflowIndex() {
  if (!app.project) return;
  const seq = ++indexSeq;
  try {
    const iflows = (await call('iflow.index')).iflows;
    if (seq !== indexSeq) return;
    whereUsed.index = iflows;
    whereUsed.error = '';
  } catch (e) {
    if (seq === indexSeq) whereUsed.error = (e as Error).message;
  }
}

/** Opens the Where used tab searching for a value (from the diagram's details). */
export function showWhereUsed(query: string, iflow = '') {
  whereUsed.view = 'search';
  whereUsed.query = query;
  whereUsed.kind = '';
  whereUsed.iflow = iflow;
  app.tool = 'whereused';
  void searchIflows();
}

export interface IflowStep {
  iflow: string;
  id: string;
  name: string;
  type: 'groovy' | 'xslt' | 'xsd' | 'mmap' | FlowStep | 'error';
  /** Message Mapping steps: the Groovy scripts its .mmap uses (the mapping itself is not simulated). */
  scripts?: string[];
  note?: string;
  /** Flow step settings (Content Modifier, Filter, Router, Splitter, Gather, Converter, Encoder, Message Digest) as the iFlow stores them. */
  config?: any;
  componentVersion?: string;
  file?: string;
  fileFound?: boolean;
  function?: string;
  profile?: Profile;
}

export const xslt = $state({
  mappingPath: null as string | null,
  mapping: '',
  savedMapping: '',
  body: '',
  headers: [] as Row[],
  properties: [] as Row[],
  paramPrecedence: 'header' as 'header' | 'property',
  missingParameter: 'default' as 'default' | 'error',
  result: null as XsltResult | null,
  running: false,
  live: true,
  /** Message imports can require a manual run before even restricted previews resume. */
  armed: true,
  casePath: null as string | null,
  compareOn: false,
  compare: null as CompareRun<XsltResult> | null,
  /** The tab's last runs in this session (in memory only), and the time of the one shown. */
  history: [] as HistoryEntry<XsltResult>[],
  resultAt: null as number | null,
});

function resetXsltRun() {
  clearTimeout(xsltPreviewTimer);
  runSeq++;
  xslt.running = false;
  xslt.result = xslt.compare = null;
}

/** A newly opened mapping can preview, but only an explicit Run can call Java. */
function openXsltPreview() {
  resetXsltRun();
  xslt.armed = true;
}

/** A Groovy script runs with the user's rights. A newly opened script waits for a manual first run. */
function disarmGroovy(what: string) {
  groovy.armed = false;
  groovy.result = null;
  groovy.compare = null;
  app.status = `Opened ${what}. A script runs with your rights, so it has not run: press Ctrl+Enter to run it.`;
}

/** A live run or a run on showing a tab never enables reflexive Java calls. */
export function autoRunXslt() {
  if (xslt.armed && xslt.mapping.trim()) return executeXslt('xslt.preview');
}

let xsltPreviewTimer: ReturnType<typeof setTimeout> | undefined;

export function scheduleXsltPreview() {
  clearTimeout(xsltPreviewTimer);
  if (xslt.live) xsltPreviewTimer = setTimeout(() => { if (xslt.live) void autoRunXslt(); }, 450);
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
  script: '',
  savedScript: '',
  functionName: 'processData',
  body: '',
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
  history: [] as HistoryEntry<GroovyResult>[],
  resultAt: null as number | null,
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
  expression: '',
  version: '3.1' as '1.0' | '3.1',
  namespaces: [] as Row[],
  body: '',
  result: null as null | { ok: boolean; items: any[]; count: number; diagnostics: Diagnostic[]; engine: string },
  /** The document the result is for: its places are marked only while the text is still this. */
  resultBody: '',
  /** Expressions run in this session, newest last (↑ and ↓ in the expression field). */
  history: [] as string[],
  running: false,
});

export type { CmRow, FlowStep, Route, RouteResult } from './flowcalls';


export const flow = $state({
  step: 'contentModifier' as FlowStep,
  /**
   * The Groovy engine whose Camel runs Camel Simple and the Content Modifier. Both carry Camel 3.14.7, as a
   * tenant does for both script versions (F48), so any installed one will do.
   */
  camel: 'groovy-2.x' as Profile,
  /** The iFlow step each step's settings came from, if any. */
  sources: {} as Partial<Record<FlowStep, string>>,
  /** Settings the iFlow step left out, with the default the tab took for each (an assumption). */
  defaulted: {} as Partial<Record<FlowStep, string>>,
  body: '',
  /**
   * A body that is no text (a .zip or .gz from the project, an Encoder's GZIP or ZIP result): its bytes as Base64.
   * While set, it is the input body and `body` is not used; whatever sets `body` clears it.
   */
  binary: null as null | { base64: string; size: number },
  headers: [] as Row[],
  properties: [] as Row[],
  ...flowStepDefaults(),
  /** The messages to gather: a splitter's result or bodies added by hand. */
  gather: { ...flowStepDefaults().gather, messages: [] as string[] },
  cmResult: null as any,
  routerResult: null as null | { routes: RouteResult[]; taken: string; diagnostics: string[] },
  splitResult: null as any,
  filterResult: null as any,
  gatherResult: null as any,
  converterResult: null as any,
  encoderResult: null as any,
  decoderResult: null as any,
  digestResult: null as any,
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
  okLabel: 'OK',
  resolve: null as null | ((r: { value: string; checked: boolean } | null) => void),
});

export function ask(title: string, value: string, checkboxLabel = ''): Promise<{ value: string; checked: boolean } | null> {
  return new Promise((resolve) => {
    Object.assign(dialog, { open: true, title, value, checkboxLabel, checked: true, input: true, okLabel: 'OK', resolve });
  });
}

/** In-app confirmation (window.confirm is not available in every desktop webview). */
export function confirm(title: string, okLabel = 'OK'): Promise<boolean> {
  return new Promise((resolve) => {
    Object.assign(dialog, { open: true, title, value: '', checkboxLabel: '', checked: true, input: false, okLabel,
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
// in the address the browser mode was opened with, or through the desktop app's receiver (receiver.svelte.ts).

/**
 * Takes the message of a pasted link or JSON into its tab. One way only: nothing is sent or called for it.
 * A script or mapping that comes along is not run: it is someone's code until the user starts it.
 */
export async function importMessage(pasted: string | SharedMessage, options: ImportOptions = {}): Promise<boolean> {
  const { rerun = true } = options;
  try {
    app.error = '';
    const m = typeof pasted === 'string' ? await readShared(pasted) : pasted;
    const what = sharedSummary(m);
    if (what === '') throw new Error('The link holds no body, headers, properties or script: nothing was sent along.');
    const tool: StepTool = m.kind === 'message' ? app.stepTool : m.kind;
    if (m.kind !== 'message' && !app.profiles.some((p) => p.kind === m.kind && p.available) && app.profiles.length > 0) {
      throw new Error(`The link is for a ${m.kind === 'groovy' ? 'Groovy script' : 'XSLT mapping'}, and no engine for it is installed or running.`);
    }
    if (m.source !== '' && !(await mayDiscard(tool === 'groovy' ? 'groovy' : 'xslt'))) return false;
    if (tool === 'groovy') importGroovy(m, rerun);
    else if (tool === 'xslt') importXslt(m, rerun);
    else {
      // XSD, XPath and Flow Steps take a message typed as JSON: the body, and for Flow Steps the rows.
      if (m.body !== '') {
        if (tool === 'xsd') xsd.body = m.body;
        else if (tool === 'xpath') xpath.body = m.body;
        else setFlowBody(m.body);
        delete app.inputPaths[tool];
      }
      if (tool === 'flow') {
        if (m.headers.length) flow.headers = m.headers;
        if (m.properties.length) flow.properties = m.properties;
      }
      app.tool = tool;
      app.status = `Imported: ${what}.`;
    }
    const warning = options.warning ?? bodyWarning(m.body);
    if (warning) app.error = 'Imported, but: ' + warning;
    return true;
  } catch (e) {
    fail(e);
    return false;
  }
}

export interface ImportOptions {
  /** false (the receiver): nothing runs after the import, not even the open script or mapping with Live on. */
  rerun?: boolean;
  /** The body's warning, if the caller has it already (bodyWarning). */
  warning?: string;
}

function importGroovy(m: SharedMessage, rerun: boolean) {
  if (m.source !== '') {
    rememberScript();
    setEditorDocument('groovy', null, m.source, ''); // unsaved: Save asks for a path
    groovy.casePath = null;
    groovy.casePlatform = null;
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
    if (rerun) rerunIfLive('groovy');
    else {
      // From the receiver: the open script waits for a run by hand, a live timer already set included.
      groovy.armed = false;
      app.status += ' The open script has not run on it: press Ctrl+Enter to run it.';
    }
  }
}

/**
 * Loads a bundled example (lib/examples.ts) into its tab, without a path: Save asks for one. The mapping runs as
 * one opened from a file would; a script waits for Ctrl+Enter, as a script opened from a file does.
 */
export async function loadExample(id: string) {
  const ex = EXAMPLES.find((e) => e.id === id);
  if (!ex || !(await mayDiscard(ex.tool))) return;
  app.error = '';
  if (ex.tool === 'xslt') {
    setEditorDocument('xslt', null, ex.source, '');
    xslt.casePath = null;
    xslt.body = ex.body;
    delete app.inputPaths.xslt;
    app.tool = 'xslt';
    openXsltPreview();
    app.status = `Loaded the example ${ex.path} with its input. Save asks where to keep it.`;
    rerunIfLive('xslt');
    return;
  }
  rememberScript();
  setEditorDocument('groovy', null, ex.source, '');
  groovy.casePath = null;
  groovy.casePlatform = null;
  groovy.functionName = 'processData';
  groovy.mapping = null;
  groovy.functions = [];
  groovy.stepFunction = null;
  groovy.args = [];
  forgetGroovyRun();
  groovy.body = ex.body;
  delete app.inputPaths.groovy;
  if (ex.profile && hasProfile(ex.profile)) app.groovyProfile = ex.profile;
  app.tool = 'groovy';
  disarmGroovy('the example ' + ex.path);
}

function importXslt(m: SharedMessage, rerun: boolean) {
  if (m.source !== '') {
    setEditorDocument('xslt', null, m.source, '');
    xslt.casePath = null;
  }
  if (m.body !== '') {
    xslt.body = m.body;
    delete app.inputPaths.xslt;
  }
  if (m.headers.length) xslt.headers = m.headers;
  if (m.properties.length) xslt.properties = m.properties;
  app.tool = 'xslt';
  if (m.source !== '') {
    // Stricter than for a file opened from the project: a link can come from any web page, so its mapping
    // waits for a run by hand whether or not it calls Java.
    resetXsltRun();
    xslt.armed = false;
    app.status = `Imported from CPI Helper: ${sharedSummary(m)}. A mapping from a link has not run: press Ctrl+Enter to run it.`;
  } else {
    app.status = `Imported: ${sharedSummary(m)}.`;
    if (rerun) rerunIfLive('xslt');
    else {
      // From the receiver: the open mapping waits for a run by hand, a live timer already set included.
      xslt.armed = false;
      app.status += ' The open mapping has not run on it: press Ctrl+Enter to run it.';
    }
  }
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
  // A newer load, or a closed or other project, makes this one stale: it reads files one by one, and what it
  // read late must not land in the tables of the project open by then.
  const gen = ++platformGen;
  const root = app.project.root;
  const stale = () => gen !== platformGen || app.project?.root !== root;
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
        const text = await readFile(PLATFORM_FILES[key]);
        if (stale()) return;
        next.doc = parsePlatformFile(text); // numbers keep their text
        next.rows = platformRows(key, next.doc);
        next.saved = JSON.stringify(platformDoc(key, next.rows, next.doc));
      } catch (e) {
        if (stale()) return;
        next.doc = undefined;
        next.rows = [];
        next.loadError = (e as Error).message;
      }
    }
    // Rows typed while the file was read stay.
    if (force || JSON.stringify(sec.rows) === before) Object.assign(sec, next);
  }
}

/** Counts loads and resets: a load that started before the latest one of either stops. */
let platformGen = 0;

function resetPlatform() {
  platformGen++;
  for (const key of PLATFORM_KEYS) Object.assign(platform[key], emptySection(key));
  platform.keystoreExists = false;
}

export async function savePlatform(key: PlatformKey): Promise<boolean> {
  const project = app.project;
  if (!project) {
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
  const gen = platformGen;
  const isCurrent = () => gen === platformGen && isWriteProject(project);
  try {
    const content = platformFileText(doc);
    if (sec.doc === undefined) {
      if (!(await writeNewFile(project, path, { content }, isCurrent))) return false;
    } else {
      await writeProjectFile(project, path, { content }, false);
    }
    // The file is written; if the project closed (or another one opened) meanwhile, its tables are not this one's.
    if (!isCurrent()) return false;
    sec.doc = doc;
    sec.saved = JSON.stringify(doc);
    sec.exists = true;
    // The same file open in the File tab follows, unless it has changes of its own. As a new document version:
    // a File tab save still in flight then no longer sets its (older) content as the baseline.
    if (fileView.path === path && fileView.content === fileView.saved) setEditorDocument('file', path, content);
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

let projectChanging = false;

export async function open(dir: string, initProject = false) {
  app.error = '';
  // The opened project's platform files take the tables' place. Unsaved rows typed without a project, or for
  // another project, would otherwise go into this project's runs and, on Save, into its files.
  const before = app.project?.root ?? null;
  const unsaved = dirtyPlatformFiles();
  if (unsaved.length && before !== dir && !(await confirm(before === null
    ? 'The project\'s platform files replace the platform data entered without a project. Open it?'
    : `Discard the unsaved platform data of the open project (${unsaved.join(', ')})?`))) return;
  if (projectChanging) return;
  projectChanging = true;
  let r;
  try {
    r = await openProject(dir, initProject);
    app.project = { root: r.root, files: r.files };
  } finally {
    projectChanging = false;
  }
  const other = before !== r.root;
  if (other) scriptMemory.clear(); // its paths belong to the other project
  if (other) resetPlatform(); // at once: no run of the opened project uses rows typed before it
  if (other) closeIflowFlowSteps(); // their iFlows belong to the other project
  if (other) resetWhereUsed();
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
  if (projectChanging) return;
  closeEngineProject();
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
  app.iflowModels = [];
  app.iflowSelected = null;
  closeIflowFlowSteps();
  if (app.tool === 'diagram' || app.tool === 'whereused') app.tool = app.stepTool;
  resetWhereUsed();
}

/** Empties the Where used tab and drops answers still on their way: they belong to the project before. */
function resetWhereUsed() {
  whereUsedSeq++;
  indexSeq++;
  Object.assign(whereUsed, { view: 'search', query: '', kind: '', iflow: '', hits: [], searched: '', index: [], error: '' });
}

export async function refreshFiles() {
  const project = app.project;
  if (!project) return;
  const r = await call('project.list');
  if (app.project !== project) return;
  project.files = r.files;
  await loadPlatform(); // a platform file may have been saved, renamed or deleted
}

export async function loadIflowSteps() {
  if (!app.project) return;
  try {
    app.iflowSteps = (await call('iflow.steps')).steps;
  } catch {
    app.iflowSteps = [];
  }
  await loadIflowModels();
}

export async function loadIflowModels() {
  if (!app.project) return;
  try {
    app.iflowModels = (await call('iflow.models')).models;
  } catch {
    app.iflowModels = [];
  }
  if (!app.iflowModels.length && (app.tool === 'diagram' || app.tool === 'whereused')) app.tool = app.stepTool;
  // Results of the iFlows before a rescan or import would point at elements that may be gone.
  if (whereUsed.iflow && !app.iflowModels.some((m) => m.iflow === whereUsed.iflow)) whereUsed.iflow = '';
  if (whereUsed.searched || whereUsed.kind) void searchIflows();
  if (whereUsed.index.length) void loadIflowIndex();
}

/** The step of an iFlow element that a tab can open (Groovy, XSLT, a flow step, …), if any. */
export function iflowStepFor(iflow: string, id: string): IflowStep | undefined {
  return app.iflowSteps.find((s) => s.iflow === iflow && s.id === id);
}

/** Selects an element of an imported iFlow; with show, the Diagram tab comes up with it. */
export function selectIflowNode(iflow: string, id: string | null, show = false) {
  app.iflowSelected = { iflow, id };
  if (show) app.tool = 'diagram';
}

/** Imports an exported iFlow, Value Mapping or integration package (the engine tells them apart). */
export async function importIflow(zip: string) {
  const r = await call('iflow.import', { zip });
  if (app.project) app.project.files = r.fileList;
  app.iflowSteps = r.steps;
  await loadIflowModels();
  const vms: { rows: number; replaced: number; notes: string[] }[] = r.valueMappings ?? [];
  const skipped: { name: string; type: string; reason: string }[] = r.skipped ?? [];
  const parts: string[] = [];
  if (r.iflows?.length) parts.push(`${r.files} files, ${r.steps.length} steps found`);
  if (vms.length) {
    const rows = vms.reduce((n, v) => n + v.rows, 0);
    const replaced = vms.reduce((n, v) => n + v.replaced, 0);
    parts.push(`${rows} value mapping rows` + (replaced ? `, ${replaced} replaced` : ''));
  }
  if (skipped.length) parts.push(`skipped: ${skipped.map((s) => `${s.name} (${s.type}, ${s.reason})`).join(', ')}`);
  // An overwrite import deletes nothing: files of the earlier version that the export lacks stay.
  const kept: string[] = r.kept ?? [];
  if (kept.length) parts.push(`${kept.length} earlier files not in the export were kept: ${kept.slice(0, 5).join(', ')}${kept.length > 5 ? ', …' : ''}`);
  const notes = vms.flatMap((v) => v.notes);
  app.status = `Imported ${r.imported} (${parts.join('; ')})` + (notes.length ? `. ${notes.join(' ')}` : '');
  if (vms.length) {
    // Unsaved edits of the table stay, and saving them would write over the imported rows.
    if (platformDirty('valueMappings')) {
      app.error = 'The import changed platform/value-mappings.json, but the Value Mappings table has unsaved changes: '
        + 'saving them replaces the imported rows. Discard them to see the imported rows.';
    }
    await loadPlatform();
  }
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

type Project = NonNullable<typeof app.project>;
type FileContent = { content: string; contentBase64?: never } | { contentBase64: string; content?: never };

function isWriteProject(project: Project): boolean {
  return app.project === project && !projectChanging;
}

async function writeProjectFile(project: Project, path: string, payload: FileContent, createNew: boolean) {
  if (!isWriteProject(project)) throw new Error('The project changed before the file could be written.');
  await call('file.write', { path, ...payload, createNew });
}

/** New destinations use the engine's atomic create-new, not the (possibly incomplete) file tree. */
export async function writeNewFile(project: Project, path: string, payload: FileContent,
  isCurrent: () => boolean = () => true): Promise<boolean> {
  if (!isCurrent()) return false;
  try {
    await writeProjectFile(project, path, payload, true);
  } catch (e) {
    // ProjectFiles reports this conflict through both HTTP and the desktop's string error channel (isExistsConflict).
    if (!(e instanceof EngineError) || !isExistsConflict(e)) throw e;
    if (!isWriteProject(project) || !isCurrent()) return false;
    if (!(await confirm(`${path} exists. Overwrite it?`, 'Overwrite')) || !isCurrent()) return false;
    await writeProjectFile(project, path, payload, false);
  }
  return true;
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

/** A project file that is not a step (platform data, configuration, iFlow files) open in the File tab. */
export const fileView = $state({ path: null as string | null, content: '', saved: '' });

// Typing does not change the document identity. Replacing or moving it does, even at the same path.
const documentVersion: Record<Editor, number> = { xslt: 0, groovy: 0, xsd: 0, file: 0 };

function editorDocument(editor: Editor) {
  if (editor === 'xslt') return { path: xslt.mappingPath, content: xslt.mapping, saved: xslt.savedMapping };
  if (editor === 'groovy') return { path: groovy.scriptPath, content: groovy.script, saved: groovy.savedScript };
  if (editor === 'xsd') return { path: xsd.schemaPath, content: xsd.schema, saved: xsd.savedSchema };
  return { path: fileView.path, content: fileView.content, saved: fileView.saved };
}

function setEditorDocument(editor: Editor, path: string | null, content: string, saved = content) {
  documentVersion[editor]++;
  if (editor === 'xslt') Object.assign(xslt, { mappingPath: path, mapping: content, savedMapping: saved });
  else if (editor === 'groovy') Object.assign(groovy, { scriptPath: path, script: content, savedScript: saved });
  else if (editor === 'xsd') Object.assign(xsd, { schemaPath: path, schema: content, savedSchema: saved });
  else Object.assign(fileView, { path, content, saved });
}

/** Asks before an editor's unsaved changes are replaced; true when nothing would be lost. */
export async function mayDiscard(editor: Editor | null): Promise<boolean> {
  if (!editor) return true;
  const { path, content, saved } = editorDocument(editor);
  if (content === saved) return true;
  const name = path ?? (editor === 'file' ? null : `the unsaved ${editorSaveNames[editor][0]}`);
  if (!name) return true;
  return confirm(`Discard the unsaved changes to ${name}?`);
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
  const editor = editorFor(path);
  if (!editor || editor === 'file') return;
  const project = app.project;
  const version = documentVersion[editor];
  const content = await readFile(path);
  if (app.project !== project || documentVersion[editor] !== version) return;
  const current = editorDocument(editor);
  if (current.content === current.saved && content !== current.saved) {
    setEditorDocument(editor, path, content);
    if (editor === 'groovy') disarmGroovy(path); // changed on disk: code the user has not started yet
    if (editor === 'xslt') openXsltPreview();
  }
  app.tool = editor;
  if (editor === 'groovy' && !groovy.result) rerunIfLive('groovy');
  if (editor === 'xslt' && !xslt.result) rerunIfLive('xslt');
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
    const content = path.startsWith('inputs/') || isArchive(path) ? await readInputFile(path) : await readFile(path);
    if (content === null) return await setBinaryInput(path);
    if (lower.endsWith('.xsl') || lower.endsWith('.xslt')) {
      setEditorDocument('xslt', path, content);
      xslt.casePath = null;
      app.tool = 'xslt';
      openXsltPreview();
      rerunIfLive('xslt');
    } else if (lower.endsWith('.groovy') || lower.endsWith('.gsh')) {
      rememberScript(); // the script that was open, before its path changes
      setEditorDocument('groovy', path, content);
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
      setEditorDocument('xsd', path, content);
      xsd.casePath = null;
      app.tool = 'xsd';
    } else if (path.startsWith('inputs/')) {
      setInput(path, content);
    } else {
      setEditorDocument('file', path, content);
      app.tool = 'file';
    }
  } catch (e) {
    fail(e);
  }
}

export function closeFileView() {
  setEditorDocument('file', null, '');
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
    openXsltPreview(); // a run still under way belongs to the closed file
    setEditorDocument('xslt', null, '');
    xslt.casePath = null;
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
    setEditorDocument('groovy', null, '');
    groovy.casePath = null;
    groovy.casePlatform = null;
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
    xsdSeq++; // a validation still under way belongs to the closed schema
    xsd.running = false;
    if (xsd.casePath) xsd.body = '';
    setEditorDocument('xsd', null, '');
    xsd.casePath = null;
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
  // A run still under way belongs to the closed input: its answer must not come back.
  if (tool === 'xsd') {
    xsdSeq++;
    xsd.running = false;
    xsd.body = '';
    xsd.result = null;
  } else if (tool === 'xpath') {
    xpathSeq++;
    xpath.running = false;
    xpath.body = '';
    xpath.result = null;
  } else if (tool === 'flow') {
    flowSeq++;
    flow.running = false;
    setFlowBody('');
    flow.cmResult = flow.routerResult = flow.splitResult = flow.filterResult = flow.gatherResult = flow.converterResult = null;
    flow.encoderResult = flow.decoderResult = flow.digestResult = null;
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

function stepBody(tool: StepTool) {
  return tool === 'xsd' ? xsd.body : tool === 'xpath' ? xpath.body : tool === 'flow' ? flow.body
    : tool === 'groovy' ? groovy.body : xslt.body;
}

function setInput(path: string, content: string) {
  // Text with a NUL character is no text the editors can hold (an archive that happens to be valid UTF-8).
  if (content.includes('\0')) return void setBinaryInput(path).catch(fail);
  // XSLT, XSD and XPath read XML only; a JSON body can only feed a Groovy script (or the Flow Steps, on the same engine).
  const json = /\.json$/i.test(path) || /^\s*[[{]/.test(content);
  if (json && !app.profiles.some((p) => p.kind === 'groovy' && p.available)) {
    app.error = `${path} is JSON: only a Groovy script takes a JSON body, and no Groovy engine is available.`;
    return;
  }
  const tool: StepTool = json && app.stepTool !== 'flow' ? 'groovy' : app.stepTool;
  if (tool === 'xsd') xsd.body = content;
  else if (tool === 'xpath') xpath.body = content;
  else if (tool === 'flow') setFlowBody(content);
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
    const content = await readInputFile(path);
    if (content === null) await setBinaryInput(path);
    else setInput(path, content);
  } catch (e) {
    fail(e);
  }
}

/** A file's text, or null when it is no text: the engine refuses to decode it. */
async function readInputFile(path: string): Promise<string | null> {
  try {
    return await readFile(path);
  } catch (e) {
    if (String((e as Error)?.message ?? e).includes('cannot be read as text')) return null;
    throw e;
  }
}

/** A file that is no text (an archive for a Decoder) as the Flow Steps' input: the only steps that take bytes. */
async function setBinaryInput(path: string) {
  const r = await call('file.read', { path, binary: true });
  flow.body = '';
  flow.binary = { base64: r.contentBase64, size: r.size };
  app.inputPaths.flow = path;
  app.tool = 'flow';
  app.status = `Loaded ${path} as a binary input body (${r.size} bytes) in Flow Steps; a Decoder reads it.`;
}

/** The Flow Steps' input body as text; a binary body set before is dropped. */
function setFlowBody(body: string) {
  flow.body = body;
  flow.binary = null;
}

/** Saves the input body of the current step tool under inputs/, so it survives opening other files. */
export async function saveInput() {
  const project = app.project;
  if (!project) {
    app.error = 'Open a project folder to save files.';
    return;
  }
  const tool = app.stepTool;
  const body = stepBody(tool);
  const bytes = tool === 'flow' ? flow.binary : null;
  const ext = bytes ? 'bin' : /^\s*[[{]/.test(body) ? 'json' : /^\s*</.test(body) ? 'xml' : 'txt';
  const r = await ask('Save input body as (path inside the project)', `inputs/input.${ext}`);
  if (!r) return;
  try {
    const payload = bytes ? { contentBase64: bytes.base64 } : { content: body };
    if (!(await writeNewFile(project, r.value, payload)) || !isWriteProject(project)) return;
    app.inputPaths[tool] = r.value;
    app.status = 'Saved input as ' + r.value;
    await refreshFiles();
  } catch (e) {
    fail(e);
  }
}

/** Points the editors that show `from` to `to`; with `to` null the editor keeps the text as unsaved. */
function movePaths(from: string, to: string | null) {
  for (const editor of ['xslt', 'groovy', 'xsd'] as const) {
    const { path, content, saved } = editorDocument(editor);
    if (path === from) setEditorDocument(editor, to, content, to ? saved : '');
  }
  if (xslt.casePath === from) xslt.casePath = to;
  if (groovy.casePath === from) groovy.casePath = to;
  if (xsd.casePath === from) xsd.casePath = to;
  if (fileView.path === from) {
    if (to) setEditorDocument('file', to, fileView.content, fileView.saved);
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
  const project = app.project;
  if (!project) return;
  const r = await ask(move ? `Rename ${path} to` : `Copy ${path} to`, path);
  if (!r || r.value === path) return;
  // The same file under another spelling (./a.xsl, or A.xsl where the file system ignores case): writing it and
  // then deleting the "old" name would delete the file itself.
  if (pathKey(r.value) === pathKey(path)) {
    app.error = `${r.value} is the same file as ${path} (names that differ only in upper and lower case or in ./ are not ${move ? 'renamed' : 'copied'} here).`;
    return;
  }
  try {
    if (!isWriteProject(project)) return;
    const content = await readFile(path);
    if (!(await writeNewFile(project, r.value, { content })) || !isWriteProject(project)) return;
    if (move) {
      await call('file.delete', { path });
      if (!isWriteProject(project)) return;
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

/** Switching 1.x <-> 2.x swaps the untouched sample script to the matching API. */
export function onGroovyProfileChange() {
  if (groovy.script === SAMPLE_GROOVY_V1 && app.groovyProfile === 'groovy-2.x') setEditorDocument('groovy', groovy.scriptPath, SAMPLE_GROOVY_V2);
  if (groovy.script === SAMPLE_GROOVY_V2 && app.groovyProfile === 'groovy-1.x') setEditorDocument('groovy', groovy.scriptPath, SAMPLE_GROOVY_V1);
}

function fileExists(path: string): boolean {
  const key = pathKey(path);
  return !!app.project?.files.some((f) => pathKey(f.path) === key);
}

/**
 * Suggests a name not seen in the file tree: `path` itself (or path + suffix), else -2, -3, ...
 * mappings/x.xsl with suffix '-copy' -> mappings/x-copy.xsl, mappings/x-copy-2.xsl, ...
 */
export function freePath(path: string, suffix = ''): string {
  const dot = path.lastIndexOf('.');
  const [stem, ext] = dot > path.lastIndexOf('/') ? [path.slice(0, dot), path.slice(dot)] : [path, ''];
  let name = `${stem}${suffix}${ext}`;
  for (let n = 2; fileExists(name); n++) name = `${stem}${suffix}-${n}${ext}`;
  return name;
}

const editorSaveNames = {
  xslt: ['mapping', 'mappings/mapping.xsl'],
  groovy: ['script', 'scripts/script.groovy'],
  xsd: ['schema', 'schemas/schema.xsd'],
} as const;
export const savingEditor = $state<Record<Editor, boolean>>({ xslt: false, groovy: false, xsd: false, file: false });

async function saveEditor(editor: Editor, saveAs = false): Promise<boolean> {
  const project = app.project;
  if (!project) {
    app.error = 'Open a project folder to save files.';
    return false;
  }
  if (savingEditor[editor]) {
    app.status = 'A save is already in progress for this editor.';
    return false;
  }
  let version = documentVersion[editor];
  const isCurrent = () => app.project === project && documentVersion[editor] === version;
  savingEditor[editor] = true;
  try {
    const current = editorDocument(editor).path;
    let path = current;
    const choosePath = saveAs || !path;
    if (editor !== 'file' && choosePath) {
      const [what, fallback] = editorSaveNames[editor];
      const r = await ask(`Save ${what} as (path inside the project)`, path ? freePath(path, '-copy') : freePath(fallback));
      path = r?.value ?? null;
    }
    if (!path || !isCurrent()) return false;
    if (projectChanging) {
      app.error = 'Wait for the project change to finish before saving.';
      return false;
    }
    const { content } = editorDocument(editor);
    // Save As onto the editor's own file is a plain save of the same document, without an overwrite question.
    // Exact spelling only: on a case-sensitive file system another case is another file.
    const ownFile = current !== null && path.replace(/\\/g, '/') === current.replace(/\\/g, '/');
    if (choosePath && !ownFile) {
      if (!(await writeNewFile(project, path, { content }, isCurrent))) return false;
    } else {
      await writeProjectFile(project, path, { content }, false);
    }
    // The file is written, but the editor holds another document (or project) now: nothing of it was saved.
    if (projectChanging || !isCurrent()) return false;

    // The editor stays editable during the write. Its baseline is exactly what reached the file.
    setEditorDocument(editor, path, editorDocument(editor).content, content);
    version = documentVersion[editor];
    if (saveAs && editor !== 'file') {
      const state = editor === 'xslt' ? xslt : editor === 'groovy' ? groovy : xsd;
      state.casePath = null;
    }
    app.status = 'Saved ' + path;
    if (editor !== 'file') {
      await refreshFiles();
    } else if (path.startsWith('platform/')) {
      // The Groovy table follows its file, unless it has unsaved changes of its own.
      const key = PLATFORM_KEYS.find((k) => PLATFORM_FILES[k] === path);
      if (key && platformDirty(key)) {
        app.status = `Saved ${path}. The Groovy tab's Platform table for it still has unsaved changes; its Save would replace this file, ↻ Reload files there takes the file.`;
      }
      await loadPlatform();
    }
    return true;
  } catch (e) {
    if (isCurrent()) fail(e);
    return false;
  } finally {
    savingEditor[editor] = false;
  }
}

export const saveMapping = () => saveEditor('xslt');
export const saveScript = () => saveEditor('groovy');
export const saveSchema = () => saveEditor('xsd');
export const saveFileView = () => saveEditor('file');
export const saveMappingAs = () => saveEditor('xslt', true);
export const saveScriptAs = () => saveEditor('groovy', true);
export const saveSchemaAs = () => saveEditor('xsd', true);

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

export function runXslt() {
  clearTimeout(xsltPreviewTimer);
  xslt.armed = true;
  return executeXslt('xslt.run');
}

async function executeXslt(method: 'xslt.run' | 'xslt.preview') {
  const seq = ++runSeq;
  xslt.running = true;
  app.error = '';
  try {
    const params = xsltRequest();
    const [r, c] = await Promise.all([
      call<XsltResult>(method, params, app.profile),
      xslt.compareOn ? compareRun<XsltResult>(method, params, app.profile) : Promise.resolve(null),
    ]);
    if (seq === runSeq) {
      xslt.result = r;
      xslt.compare = c;
      xslt.resultAt = Date.now();
      xslt.history = addRun(xslt.history, { at: xslt.resultAt, profile: app.profile, what: xslt.mappingPath ?? 'mapping.xsl', result: r });
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
/**
 * Another function, chosen in the list or typed: the arguments, the parameters the last run reported and a run
 * still under way belong to the old one. Kept, a late or the next result would take the new function's arguments
 * for the old one's and drop them. False when the name is the same.
 */
export function setGroovyFunction(name: string): boolean {
  if (groovy.functionName === name) return false;
  groovy.functionName = name;
  groovy.args = [];
  groovy.mapping = null;
  forgetGroovyRun();
  return true;
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
      groovy.resultAt = Date.now();
      groovy.history = addRun(groovy.history, { at: groovy.resultAt, profile: app.groovyProfile,
        what: `${groovy.scriptPath ?? 'script.groovy'} · ${r.mapping?.function ?? params.function}`, result: r });
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

const XPATH_HISTORY = 20;

export async function runXpath() {
  const seq = ++xpathSeq;
  xpath.running = true;
  app.error = '';
  const ns: Record<string, string> = {};
  for (const r of xpath.namespaces) if (r.name.trim() && !r.disabled) ns[r.name.trim()] = r.value;
  const expression = xpath.expression;
  const body = xpath.body;
  if (expression.trim() !== '') {
    xpath.history = [...xpath.history.filter((h) => h !== expression), expression].slice(-XPATH_HISTORY);
  }
  try {
    const r = await call('xpath.eval', { expression, version: xpath.version,
      namespaces: ns, input: { body } }, app.profile);
    if (seq === xpathSeq) {
      xpath.result = r;
      xpath.resultBody = body;
    }
  } catch (e) {
    if (seq === xpathSeq) fail(e);
  } finally {
    if (seq === xpathSeq) xpath.running = false;
  }
}

// ------------------------------------------------------------------ flow steps
// The calls live in flowcalls.ts, which the MCP server's flow.rs repeats; this keeps the tab's state.

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
  const rows = { headers: clean(flow.headers), properties: clean(flow.properties) };
  // The engines take bytes as bodyBase64 (SimExchange.fromJson).
  return flow.binary ? { bodyBase64: flow.binary.base64, ...rows } : { body: flow.body, ...rows };
}

// A run's result goes to the step it was started for, and only if no newer run or newly opened step came since.
let flowSeq = 0;
const flowResultKey = { contentModifier: 'cmResult', filter: 'filterResult', router: 'routerResult', splitter: 'splitResult', gather: 'gatherResult',
  converter: 'converterResult', encoder: 'encoderResult', decoder: 'decoderResult', messageDigest: 'digestResult' } as const;

export function missingFlowRuntime(): string {
  return missingRuntime(flow.step, flow, { xml: hasProfile(flowXmlProfile()), camel: hasProfile(flow.camel) });
}

export async function runFlowStep() {
  const missing = missingFlowRuntime();
  if (missing) { app.error = missing; return; }
  const seq = ++flowSeq;
  const step = flow.step;
  const run: FlowRun = { input: $state.snapshot(flowInput()), namespaces: namespaceMap(), xml: flowXmlProfile(), camel: flow.camel,
    call: flowEngineCall() };
  const config: any = $state.snapshot(flow[flowConfigKey[step]]);
  flow.running = true;
  app.error = '';
  try {
    const r = await runStep(run, step, config);
    if (seq === flowSeq) flow[flowResultKey[step]] = r;
  } catch (e) {
    if (seq === flowSeq) fail(e);
  } finally {
    if (seq === flowSeq) flow.running = false;
  }
}

/** The splitter's messages go to the Gather step, as in an iFlow's Splitter → … → Gather. */
export function gatherSplitResult() {
  flow.gather.messages = (flow.splitResult?.messages ?? []).map((m: { body: string }) => m.body);
  flow.gatherResult = null;
  flow.step = 'gather';
}

/**
 * A result message becomes the next input, to try steps one after another. A result that is no text (an Encoder's
 * GZIP or ZIP) comes as `{ bodyBase64, bodySize }`.
 */
export function useAsFlowInput(body: string | { bodyBase64: string; bodySize: number }, headers?: Row[], properties?: Row[]) {
  if (typeof body === 'string') setFlowBody(body);
  else {
    flow.body = '';
    flow.binary = { base64: body.bodyBase64, size: body.bodySize };
  }
  if (headers) flow.headers = headers.map((r) => ({ ...r }));
  if (properties) flow.properties = properties.map((r) => ({ ...r }));
  delete app.inputPaths.flow;
}

function openFlowStep(step: IflowStep) {
  const type = step.type as FlowStep;
  const settings = settingsFromIflowStep(type, step.config ?? {});
  // Gather's messages stay: they come from the splitter, not from the iFlow.
  if (type === 'gather') flow.gather = { ...settings, messages: flow.gather.messages };
  else (flow as any)[flowConfigKey[type]] = settings;
  flow.step = step.type as FlowStep;
  flow.sources[step.type as FlowStep] = `${step.iflow} · ${step.name || step.id}`;
  const missing = defaultedSettings(type, step.config ?? {});
  if (missing.length) flow.defaulted[type] = missing.map((k) => `${k} = ${(settings as any)[k]}`).join(', ');
  else delete flow.defaulted[type];
  // Only this step's result is out of date; the other steps keep theirs.
  flow[flowResultKey[step.type as FlowStep]] = null;
  // A run still open for the step this replaces must not write its result here.
  flowSeq++;
  flow.running = false;
  app.tool = 'flow';
  app.status = `Loaded ${step.name || step.id} from ${step.iflow}` + (step.note ? `. ${step.note}` : '');
}

/**
 * Closes the iFlow step a flow step's settings came from: the step gets its starting settings back and loses its
 * result. The iFlow is not touched; the step opens again from the iFlow list or the diagram. Gather keeps its messages.
 */
export function closeFlowStep(step: FlowStep) {
  const source = flow.sources[step];
  if (!source) return;
  const d = flowStepDefaults();
  if (step === 'gather') flow.gather = { ...d.gather, messages: flow.gather.messages };
  else (flow as any)[flowConfigKey[step]] = d[flowConfigKey[step]];
  delete flow.sources[step];
  delete flow.defaulted[step];
  flow[flowResultKey[step]] = null;
  // A run still open for the closed step must not write its result here.
  flowSeq++;
  flow.running = false;
  app.status = `Closed ${source}; the step has its starting settings again.`;
}

/** The flow steps taken from the project's iFlows close with it. */
function closeIflowFlowSteps() {
  const status = app.status;
  for (const step of Object.keys(flow.sources) as FlowStep[]) closeFlowStep(step);
  app.status = status;
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
  const script = await readFile(scriptPath);
  const body = tc.input?.body;
  const input = typeof body === 'string' ? body : body?.file ? await readFile(joinPath(dir, body.file)) : body?.text ?? '';
  setEditorDocument('groovy', scriptPath, script);
  groovy.body = input;
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
  // A case names attachment files relative to its folder. A path that leaves the project keeps its "..", so the
  // engine refuses it as outside the project instead of reading another file inside it.
  groovy.attachments = attachmentRows(tc.input?.attachments, (file) => projectPath(dir, file));
  groovy.soapHeaders = soapHeaderRows(tc.input?.soapHeaders);
  groovy.casePlatform = tc.platform && typeof tc.platform === 'object' ? tc.platform : null;
  groovy.casePath = path;
  app.tool = 'groovy';
  disarmGroovy('test case ' + (tc.name ?? path));
}

async function loadXsdCase(path: string, dir: string, tc: any) {
  const schemaPath = joinPath(dir, tc.schema);
  const schema = await readFile(schemaPath);
  const body = tc.input?.body;
  const input = typeof body === 'string' ? body : body?.file ? await readFile(joinPath(dir, body.file)) : body?.text ?? '';
  setEditorDocument('xsd', schemaPath, schema);
  xsd.body = input;
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
  const mapping = await readFile(mappingPath);
  const body = tc.input?.body;
  const input = typeof body === 'string' ? body : body?.file ? await readFile(joinPath(dir, body.file)) : body?.text ?? '';
  setEditorDocument('xslt', mappingPath, mapping);
  xslt.body = input;
  xslt.headers = toRows(tc.input?.headers);
  xslt.properties = toRows(tc.input?.properties);
  xslt.paramPrecedence = tc.options?.paramPrecedence ?? 'header';
  xslt.missingParameter = tc.options?.missingParameter ?? 'default';
  if (tc.profile && app.profiles.find((p) => p.id === tc.profile && p.available)) app.profile = tc.profile;
  xslt.casePath = path;
  app.tool = 'xslt';
  app.status = 'Loaded test case ' + (tc.name ?? path);
  openXsltPreview();
  await autoRunXslt();
}
