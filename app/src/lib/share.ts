/**
 * A message handed over from a tenant's trace by the CPI Helper browser extension (its GroovyDebugX and
 * xsltDebugX plugins), so that a step can be run here on a real message. The app still connects to
 * nothing: the extension, in the user's browser, has the data and appends it to a link.
 *
 * The formats are the plugins' own, read from their sources on 2026-11-13 (docs/plan.md S47); they are
 * not documented and may change:
 * - GroovyDebugX, IDE "GroovyIDE" or "Custom URL": `<url><data>`, the JSON
 *   {input: {body, headers: {name: value}, properties: {…}}, script: {code, function}}.
 * - xsltDebugX: `<url>#share/<data>`, the JSON {xml, xslt, headers: [{name, value}], properties: […]}.
 * `<data>` is that JSON, compressed with raw DEFLATE and written as URL-safe Base64 without padding.
 * GroovyDebugX's "Contiva IDE" format (a zip inside gzip) is not read.
 *
 * Also taken, for a message typed or exported by hand: the plain JSON {body, headers, properties}.
 */
import type { Row } from './types';

/**
 * The desktop receiver's port until the user chooses one (`receiver.svelte.ts`). Not 7777 to 7780: the engines
 * of the browser mode and the e2e tests listen there, and 7778, the default up to 0.4.0, is the Groovy
 * engines' own `--http` default. A setting saved with 7778 stays as it is.
 */
export const RECEIVER_DEFAULT_PORT = 7790;

export interface SharedMessage {
  /** The tab the message is for; 'message' when the text names no step (plain {body, headers, properties}). */
  kind: 'groovy' | 'xslt' | 'message';
  /** Parts that are empty were not handed over (the plugins let the user leave parts out) and change nothing. */
  body: string;
  headers: Row[];
  properties: Row[];
  /** The Groovy script or the XSLT mapping, '' when it did not come along. */
  source: string;
  /** The Groovy function, '' for XSLT. */
  function: string;
}

const TOKEN = /^[A-Za-z0-9_-]+$/;

/** The data of a pasted link, or of the text itself: the JSON, or the Base64 token. */
function shareText(pasted: string): { json?: string; token?: string } {
  const text = pasted.trim();
  if (text === '') throw new Error('Nothing to import: paste the link CPI Helper opened, or a message as JSON.');
  if (text.startsWith('{')) return { json: text };
  if (/[?&]data=/.test(text) && !text.includes('#share/')) {
    throw new Error('This looks like a link for the Contiva IDE, whose format is not read. In CPI Helper\'s GroovyDebugX settings, choose "Custom URL" (or GroovyIDE) and send the step again.');
  }
  const hash = text.indexOf('#share/');
  const token = hash >= 0 ? text.slice(hash + '#share/'.length) : text.slice(Math.max(text.lastIndexOf('/'), text.lastIndexOf('='), text.lastIndexOf('#')) + 1);
  if (!TOKEN.test(token)) throw new Error('This is not a link from CPI Helper or a message as JSON: no data found after the address.');
  return { token };
}

/**
 * The most an unpacked link may hold, in characters. A link can come from any web page, and raw DEFLATE packs
 * a lot into little, so the unpacking stops here rather than fill the memory; a 25 MB traced body fits.
 */
export const MAX_UNPACKED = 128_000_000;

async function inflate(token: string, limit: number): Promise<string> {
  if (typeof DecompressionStream === 'undefined') {
    throw new Error('This system\'s web view cannot unpack the link (no DecompressionStream; macOS before 13.3). Use the browser mode, or paste the message as JSON.');
  }
  const base64 = token.replace(/-/g, '+').replace(/_/g, '/');
  let bytes: Uint8Array;
  try {
    bytes = Uint8Array.from(atob(base64), (c) => c.charCodeAt(0));
  } catch {
    throw new Error('The data in the link is not Base64.');
  }
  const reader = new Blob([bytes as BlobPart]).stream().pipeThrough(new DecompressionStream('deflate-raw'))
    .pipeThrough(new TextDecoderStream()).getReader();
  let text = '';
  for (;;) {
    let chunk: ReadableStreamReadResult<string>;
    try {
      chunk = await reader.read();
    } catch {
      throw new Error('The data in the link could not be unpacked. The link may be cut off: long links do not survive every copy and paste.');
    }
    if (chunk.done) return text;
    text += chunk.value;
    if (text.length > limit) {
      await reader.cancel().catch(() => undefined);
      throw new Error(`The message in the link is longer than ${(limit / 1e6).toLocaleString('en')} million characters unpacked, more than CPI Workbench takes.`);
    }
  }
}

const str = (v: unknown): string => (typeof v === 'string' ? v : v === undefined || v === null ? '' : String(v));
const isObj = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null && !Array.isArray(v);

/** Headers or properties as rows: {name: value} (GroovyDebugX) or [{name, value}] (xsltDebugX). A trace holds text, so String. */
function toRows(v: unknown): Row[] {
  const pairs: [unknown, unknown][] = Array.isArray(v) ? v.filter(isObj).map((r) => [r.name, r.value]) : isObj(v) ? Object.entries(v) : [];
  return pairs.filter(([name]) => str(name) !== '').map(([name, value]) => ({ name: str(name), value: str(value), type: 'String' }));
}

/** Reads a pasted link, its data or a message as JSON. Throws with what is wrong, in words for the user. */
export async function readShared(pasted: string, limit = MAX_UNPACKED): Promise<SharedMessage> {
  const { json, token } = shareText(pasted);
  let data: unknown;
  try {
    data = JSON.parse(json ?? await inflate(token!, limit));
  } catch (e) {
    if (json === undefined && !(e instanceof SyntaxError)) throw e;
    throw new Error(json === undefined ? 'The link\'s data is not the JSON CPI Helper writes.' : 'The text is not valid JSON: ' + (e as Error).message);
  }
  if (!isObj(data)) throw new Error('The data is not a message: expected an object.');
  if (isObj(data.input) || isObj(data.script)) {
    const input = isObj(data.input) ? data.input : {};
    const script = isObj(data.script) ? data.script : {};
    return { kind: 'groovy', body: str(input.body), headers: toRows(input.headers), properties: toRows(input.properties),
      source: str(script.code), function: str(script.function) };
  }
  if ('xml' in data || 'xslt' in data) {
    return { kind: 'xslt', body: str(data.xml), headers: toRows(data.headers), properties: toRows(data.properties),
      source: str(data.xslt), function: '' };
  }
  if ('body' in data || 'headers' in data || 'properties' in data) {
    return { kind: 'message', body: str(data.body), headers: toRows(data.headers), properties: toRows(data.properties), source: '', function: '' };
  }
  throw new Error('The data is not a message: it has none of input, script, xml, xslt, body, headers or properties.');
}

/**
 * A warning for a body that starts like XML or JSON and does not parse, else ''. A large traced body can arrive
 * cut off before CPI Helper builds the link (plan Q-T3); the import still takes it. XML is checked with the web
 * view's DOMParser, so only where there is one (not in Node).
 */
export function bodyWarning(body: string): string {
  const text = body.replace(/^\uFEFF/, '').trimStart();
  if (text.length > MAX_CHECKED) return '';
  let problem = '';
  let what = '';
  if (text.startsWith('<') && typeof DOMParser !== 'undefined') {
    what = 'well-formed XML';
    problem = parseError(text) ?? '';
  } else if (text.startsWith('{') || text.startsWith('[')) {
    what = 'valid JSON';
    try {
      JSON.parse(text);
    } catch (e) {
      problem = (e as Error).message;
    }
  }
  return problem === '' ? '' : `The body is not ${what} (${problem}). It may have been cut off in the trace or on the way; steps that parse it may fail.`;
}

/** Bodies above this many characters are not checked: the check would cost more than it tells. */
const MAX_CHECKED = 64_000_000;

const XHTML = 'http://www.w3.org/1999/xhtml';
const MOZILLA_ERROR = 'http://www.mozilla.org/newlayout/xml/parsererror.xml';

/**
 * What the browser's XML parser reports, as "error on line 1 at column 47: …", or null for a well-formed
 * document. A document can have an element `parsererror` of its own, even in the XHTML namespace, so the
 * browser's report is told by its form:
 * - Chromium and WebKit add an XHTML `parsererror` with a `style` and a heading `h3` first;
 * - Firefox replaces the document with a `parsererror` in a namespace of its own, text first.
 */
function parseError(text: string): string | null {
  const doc = new DOMParser().parseFromString(text, 'application/xml');
  const root = doc.documentElement;
  if (root?.localName === 'parsererror' && root.namespaceURI === MOZILLA_ERROR) {
    const m = /XML Parsing Error: ([^\n]*)[\s\S]*?Line Number (\d+), Column (\d+)/.exec(root.firstChild?.textContent ?? '');
    return m ? `error on line ${m[2]} at column ${m[3]}: ${m[1].trim()}` : 'parse error';
  }
  const error = Array.from(doc.getElementsByTagNameNS(XHTML, 'parsererror')).find((e) => e.hasAttribute('style')
    && e.firstElementChild?.localName === 'h3' && e.firstElementChild.namespaceURI === XHTML);
  if (!error) return null;
  return /error on line \d+ at column \d+: [^\n]*?(?=Below is|\n|$)/.exec(error.textContent ?? '')?.[0]?.trim() || 'parse error';
}

/** What an import brought, for the status line: "body, 3 headers, 12 properties, script". */
export function sharedSummary(m: SharedMessage): string {
  const parts: string[] = [];
  if (m.body !== '') parts.push('body');
  if (m.headers.length) parts.push(m.headers.length === 1 ? '1 header' : `${m.headers.length} headers`);
  if (m.properties.length) parts.push(m.properties.length === 1 ? '1 property' : `${m.properties.length} properties`);
  if (m.source !== '') parts.push(m.kind === 'xslt' ? 'mapping' : 'script');
  return parts.join(', ');
}
