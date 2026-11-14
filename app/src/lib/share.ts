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

async function inflate(token: string): Promise<string> {
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
  try {
    const stream = new Blob([bytes as BlobPart]).stream().pipeThrough(new DecompressionStream('deflate-raw'));
    return await new Response(stream).text();
  } catch {
    throw new Error('The data in the link could not be unpacked. The link may be cut off: long links do not survive every copy and paste.');
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
export async function readShared(pasted: string): Promise<SharedMessage> {
  const { json, token } = shareText(pasted);
  let data: unknown;
  try {
    data = JSON.parse(json ?? await inflate(token!));
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

/** What an import brought, for the status line: "body, 3 headers, 12 properties, script". */
export function sharedSummary(m: SharedMessage): string {
  const parts: string[] = [];
  if (m.body !== '') parts.push('body');
  if (m.headers.length) parts.push(m.headers.length === 1 ? '1 header' : `${m.headers.length} headers`);
  if (m.properties.length) parts.push(m.properties.length === 1 ? '1 property' : `${m.properties.length} properties`);
  if (m.source !== '') parts.push(m.kind === 'xslt' ? 'mapping' : 'script');
  return parts.join(', ');
}
