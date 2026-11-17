// The Flow Steps tab's engine calls, without its state: the starting settings of each step, how an iFlow step's
// configuration becomes those settings, and the calls that run a step. XPath runs in the XML engine (Saxon, XPath 3.1
// as SAP documents for the Content Modifier), Camel Simple and the Content Modifier in the Groovy engine's Camel. The
// Router is evaluated here, route by route.
//
// The MCP server repeats these calls (app/src-tauri/src/mcp/flow.rs). The fixtures in testdata/flow-parity hold the
// calls both must make; app/tests/flowparity.test.mts and the Rust test `flow_parity` replay them.
//
// This module has no imports of values, so that the unit tests can load it on plain Node.

import type { Profile } from './engine';

export type FlowStep = 'contentModifier' | 'filter' | 'router' | 'splitter' | 'gather' | 'converter' | 'encoder' | 'decoder' | 'messageDigest';

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

/** JSON ↔ XML and XML ↔ CSV converters; mapping and array paths are text, one entry per line. */
export function converterDefaults() {
  return {
    conversion: 'xmlToJson' as 'jsonToXml' | 'xmlToJson' | 'xmlToCsv' | 'csvToXml',
    useNamespaces: false, separator: ':', namespaceMapping: '',
    addRootElement: false, rootElementName: 'root', rootNamespace: '',
    suppressRoot: false, streaming: false, streamingElements: 'all' as 'all' | 'specific', arrayPaths: '', outputEncoding: '',
    sourcePath: '', fieldSeparator: ',', csvHeader: 'xml' as 'none' | 'xml' | 'xsd', includeParent: false, parentPath: '',
    includeAttributes: false,
    schema: '', targetPath: '', recordMarker: '', firstLineHeader: true, headerMapping: 'xsd' as 'xsd' | 'csv',
  };
}

/**
 * The settings each flow step starts with, keyed as in the store's `flow`: empty where the user fills them in (rows,
 * routes, expressions), SAP's defaults where the step has a choice (scheme, method, header name). Closing a step taken
 * from an iFlow brings them back.
 */
export function flowStepDefaults() {
  return {
    cm: { headers: [] as CmRow[], properties: [] as CmRow[], bodyType: 'constant' as 'constant' | 'expression', body: '' },
    router: { routes: [] as Route[], throwException: false },
    splitter: { splitter: 'general' as 'general' | 'iterating', expressionType: 'xpath' as 'xpath' | 'token' | 'lineBreak',
      expression: '', grouping: '' as string | number, streaming: true },
    filter: { expression: '', valueType: 'nodelist' },
    /** Gather's settings; its messages are not settings and are not part of this. */
    gather: { messageType: 'sameXml', algorithm: 'combine', sourceXPath: '', targetXPath: '' },
    converter: converterDefaults(),
    encoder: { scheme: 'base64Encode' },
    decoder: { scheme: 'base64Decode' },
    digest: { filter: '', canonicalization: 'c14n10', algorithm: 'SHA512', targetHeader: 'SAPMessageDigest' },
  };
}

type Defaults = ReturnType<typeof flowStepDefaults>;

/** The key of each step's settings in `flowStepDefaults()` and the store's `flow`. */
export const flowConfigKey = { contentModifier: 'cm', filter: 'filter', router: 'router', splitter: 'splitter', gather: 'gather',
  converter: 'converter', encoder: 'encoder', decoder: 'decoder', messageDigest: 'digest' } as const;

/** A value the iFlow leaves out or sets null takes the default; an empty string stays empty. */
const or = <T>(v: T | null | undefined, d: T): T => (v ?? d);

/**
 * The settings an iFlow step's configuration (as `iflow.steps` returns it) gives the tab. What the step leaves out
 * starts from the defaults, not from a step opened before. The iFlow's lists become the tab's text fields.
 */
export function settingsFromIflowStep(type: FlowStep, c: any = {}): any {
  const d: Defaults = flowStepDefaults();
  const row = (r: any): CmRow => ({ action: r.action === 'Delete' ? 'Delete' : 'Create', name: r.name ?? '', type: r.type ?? 'constant',
    value: r.value ?? '', datatype: r.datatype ?? '', default: r.default ?? '' });
  switch (type) {
    case 'contentModifier':
      return { headers: (c.headers ?? []).map(row), properties: (c.properties ?? []).map(row),
        bodyType: c.bodyType === 'expression' ? 'expression' : 'constant', body: c.body ?? '' };
    case 'router':
      return { routes: c.routes ?? [], throwException: !!c.throwException };
    case 'filter':
      return { expression: c.expression ?? '', valueType: or(c.valueType, d.filter.valueType) };
    case 'converter': {
      const given = Object.fromEntries(Object.entries(c).filter(([, v]) => v !== null && v !== undefined));
      return { ...d.converter, ...given, namespaceMapping: converterText(given.namespaceMapping, 'namespaceMapping'),
        arrayPaths: converterText(given.arrayPaths, 'arrayPaths') };
    }
    case 'encoder':
      return { scheme: or(c.scheme, d.encoder.scheme) };
    case 'decoder':
      return { scheme: or(c.scheme, d.decoder.scheme) };
    case 'messageDigest':
      return { filter: c.filter ?? '', canonicalization: or(c.canonicalization, d.digest.canonicalization),
        algorithm: or(c.algorithm, d.digest.algorithm), targetHeader: or(c.targetHeader, d.digest.targetHeader) };
    case 'gather':
      return { messageType: or(c.messageType, d.gather.messageType), algorithm: or(c.algorithm, d.gather.algorithm),
        sourceXPath: c.sourceXPath ?? '', targetXPath: c.targetXPath ?? '' };
    case 'splitter':
      return { splitter: or(c.splitter, d.splitter.splitter), expressionType: or(c.expressionType, d.splitter.expressionType),
        expression: c.expression ?? '', grouping: c.grouping ?? '', streaming: c.streaming !== false };
  }
}

/**
 * The choices an iFlow step may leave out, which then take the tab's default. Whether CPI uses the same default for a
 * setting missing from an export is not known, so the app and the MCP server name these as assumed. `mcp/flow.rs`
 * (`ASSUMED_DEFAULTS`) repeats this list; its test reads this one, so keep one entry per line.
 */
export const ASSUMED_DEFAULTS: Partial<Record<FlowStep, string[]>> = {
  filter: ['valueType'],
  splitter: ['splitter', 'expressionType'],
  gather: ['messageType', 'algorithm'],
  encoder: ['scheme'],
  decoder: ['scheme'],
  messageDigest: ['canonicalization', 'algorithm', 'targetHeader'],
};

/** The settings of an iFlow step that its configuration leaves out (or sets null) and that took a default. */
export function defaultedSettings(type: FlowStep, c: any = {}): string[] {
  return (ASSUMED_DEFAULTS[type] ?? []).filter((k) => c?.[k] === undefined || c?.[k] === null);
}

/** A converter list as the tab's text: one `prefix = namespace` or path per line. */
function converterText(v: unknown, key: 'namespaceMapping' | 'arrayPaths'): string {
  if (!Array.isArray(v)) return (v as string) ?? '';
  // A pair without prefix or namespace is left out, as the engine does with a list (ConverterStep.mapping).
  return key === 'namespaceMapping'
    ? v.map((m: any) => [String(m?.prefix ?? '').trim(), String(m?.namespace ?? '').trim()]).filter(([p, n]) => p && n)
      .map(([p, n]) => `${p} = ${n}`).join('\n')
    : v.join('\n');
}

/** An engine call: method, parameters and the engine (profile) that runs it. */
export type EngineCall = (method: string, params: any, profile: Profile) => Promise<any>;

/**
 * Everything a flow run reads, taken when it starts: the message, the namespaces, the engines and the call. Edits
 * while the run waits for the engines do not leak into it (the MCP server's Ctx does the same).
 */
export interface FlowRun {
  input: { body?: string; bodyBase64?: string; headers: any; properties: any };
  namespaces: Record<string, string>;
  xml: Profile;
  camel: Profile;
  call: EngineCall;
}

async function xpathValue(run: FlowRun, expression: string, as: 'boolean' | 'string' | 'nodes', input = run.input) {
  // Headers and properties are XPath variables ($name), as Camel's XPath resolver provides them (F39).
  const r = await run.call('xpath.eval', { expression, version: '3.1', as, namespaces: run.namespaces, input }, run.xml);
  if (!r.ok) throw new Error(r.diagnostics?.[0]?.message ?? 'XPath failed');
  return r.value as string;
}

async function simpleEval(run: FlowRun, expression: string, mode: 'condition' | 'expression') {
  const r = await run.call('simple.eval', { expression, mode, input: run.input }, run.camel);
  if (!r.ok) throw new Error(r.diagnostics?.[0]?.message ?? 'Camel Simple failed');
  return r as { value: string | null; evaluated?: string };
}

/** Runs a step with the tab's settings for it; a Gather's settings carry its `messages`. */
export async function runStep(run: FlowRun, step: FlowStep, config: any): Promise<any> {
  switch (step) {
    case 'contentModifier': return runContentModifier(run, config);
    case 'filter': return runFilter(run, config);
    case 'router': return runRouter(run, config);
    case 'splitter': return runSplitter(run, config);
    case 'converter': return runConverter(run, config);
    case 'encoder': return run.call('encode.run', { scheme: config.scheme, input: run.input }, run.xml);
    case 'decoder': return run.call('decode.run', { scheme: config.scheme, input: run.input }, run.xml);
    case 'messageDigest': return run.call('digest.run', { filter: config.filter, canonicalization: config.canonicalization,
      algorithm: config.algorithm, targetHeader: config.targetHeader, namespaces: run.namespaces, input: run.input }, run.xml);
    case 'gather': return runGather(run, config);
  }
}

async function runContentModifier(run: FlowRun, cm: Defaults['cm']) {
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
          const s = await run.call('contentModifier.run', { ...pending, bodyType: 'constant', body: '', input: state }, run.camel);
          if (s.ok) state = { ...input, headers: s.output.headers, properties: s.output.properties };
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
  return await run.call('contentModifier.run', {
    ...done, bodyType: cm.bodyType, body: cm.body, input,
  }, run.camel);
}

async function runRouter(run: FlowRun, router: Defaults['router']) {
  const routes: RouteResult[] = router.routes.map((r) => ({ status: r.default ? 'default' : 'not evaluated' }));
  if (new Set(router.routes.filter((r) => !r.default).map((r) => r.expressionType)).size > 1) {
    return { routes, diagnostics: [],
      taken: 'Error: the routes mix XML and Non-XML conditions; SAP: "the evaluation fails" (Define Router).' };
  }
  // Whether a route was taken, apart from its name: a route's name may be empty.
  let taken = '';
  let decided = false;
  for (const [i, r] of router.routes.entries()) {
    if (r.default) continue;
    try {
      const s = r.expressionType === 'xml' ? { value: await xpathValue(run, r.condition, 'boolean') } : await simpleEval(run, r.condition, 'condition');
      routes[i] = { status: s.value === 'true' ? 'true' : 'false', camel: (s as { evaluated?: string }).evaluated };
    } catch (e) {
      routes[i] = { status: 'error', message: (e as Error).message };
      taken = 'Error: the route condition failed, so CPI raises an exception.';
      decided = true;
      break;
    }
    if (routes[i].status === 'true') {
      taken = r.name || `Route ${i + 1}`;
      decided = true;
      break;
    }
  }
  if (!decided) {
    const d = router.routes.find((r) => r.default);
    taken = router.throwException ? 'No route matched: Throw Exception is set, so the Router raises an exception.'
      : d ? d.name + ' (default route)' : 'No route matched and there is no default route.';
  }
  return { routes, taken, diagnostics: [] };
}

async function runSplitter(run: FlowRun, s: Defaults['splitter']) {
  // XPath and grouping may refer to a header or property (SAP); Camel Simple resolves the reference first.
  const resolve = async (v: string) => (v.includes('${') ? (await simpleEval(run, v, 'expression')).value ?? '' : v);
  return await run.call('split.run', {
    splitter: s.splitter, expressionType: s.expressionType, expression: await resolve(s.expression),
    grouping: await resolve(String(s.grouping ?? '')), streaming: s.streaming, namespaces: run.namespaces,
    input: run.input.bodyBase64 !== undefined ? { bodyBase64: run.input.bodyBase64 } : { body: run.input.body },
  }, run.xml);
}

async function runFilter(run: FlowRun, f: Defaults['filter']) {
  return await run.call('filter.run', {
    expression: await resolveSimpleFunctions(f.expression, async (e) => (await simpleEval(run, e, 'expression')).value ?? ''),
    valueType: f.valueType, namespaces: run.namespaces, input: run.input,
  }, run.xml);
}

async function runGather(run: FlowRun, g: Defaults['gather'] & { messages: string[] }) {
  return await run.call('gather.run', {
    messageType: g.messageType, algorithm: g.algorithm, sourceXPath: g.sourceXPath, targetXPath: g.targetXPath,
    namespaces: run.namespaces, messages: g.messages,
  }, run.xml);
}

async function runConverter(run: FlowRun, c: Defaults['converter']) {
  // SAP (CSV to XML 1.4): schema, target path and record marker may come from a header or property.
  const resolve = async (v: string) => (v.includes('${') ? (await simpleEval(run, v, 'expression')).value ?? '' : v);
  const csv = c.conversion === 'csvToXml';
  return await run.call('convert.run', {
    ...c,
    schema: csv ? await resolve(c.schema) : c.schema,
    targetPath: csv ? await resolve(c.targetPath) : c.targetPath,
    recordMarker: csv ? await resolve(c.recordMarker) : c.recordMarker,
    namespaces: run.namespaces, input: run.input,
  }, run.xml);
}

// SAP: `function:simple('${property.x}')` in a Filter XPath. Camel Simple gives the value, which goes into the
// XPath as a string literal. The MCP server does the same (resolve_simple_functions in mcp/flow.rs).
const SIMPLE_CALL = /function:simple\(\s*(?:'([^']*)'|"([^"]*)")\s*\)/g;

/** The XPath with each function:simple(...) replaced by its value as an XPath string literal. */
export async function resolveSimpleFunctions(xpath: string, evaluate: (expression: string) => Promise<string>) {
  let out = xpath;
  for (const m of xpath.matchAll(SIMPLE_CALL)) {
    const v = await evaluate(m[1] ?? m[2]);
    // A function, not a string: in a replacement string, $$, $& and $1 are patterns, and the value would change.
    out = out.replace(m[0], () => "'" + v.replaceAll("'", "''") + "'");
  }
  return out;
}
