/**
 * The functions a Groovy script declares, read from its text. Opening a script must not run its code, and
 * compiling Groovy can run code too (AST transformations), so the Groovy tab reads the signatures from the
 * text until the first run; from then on the engine's list (MappingFunction.java) wins where they differ.
 *
 * This is a lexical reading, not a parser: it finds top-level `def`/typed method declarations after blanking
 * comments and strings. The rules mirror the engine's: a parameter is a single value (String, Object or
 * untyped), a context (String[] or Object[]), Output or MappingContext; any other type, or a single Object or
 * Message parameter, makes the function a script step candidate or no mapping function at all.
 */

export type ParamKind = 'value' | 'queue' | 'output' | 'context';

export interface ScriptParam {
  name: string;
  /** The type as written, '' when untyped. */
  type: string;
  kind: ParamKind | null;
}

export interface ScriptFunction {
  name: string;
  params: ScriptParam[];
  /** 'mapping': a Message Mapping function; 'step': takes one parameter (the message); null: neither. */
  kind: 'mapping' | 'step' | null;
  /** The engine's spelling of the parameter types: String, String[], Output, MappingContext (else as written). */
  parameters: string[];
  /** The engine's signature, e.g. getHeader(String, MappingContext). */
  signature: string;
}

const KEYWORDS = new Set(['if', 'else', 'for', 'while', 'switch', 'catch', 'return', 'new', 'throw', 'try', 'synchronized', 'assert']);
const MODIFIERS = new Set(['public', 'protected', 'final', 'abstract', 'synchronized']);

/** Blanks comments and string contents (keeping offsets and line breaks), so their text matches nothing. */
export function maskCode(src: string): string {
  const out = src.split('');
  const blank = (from: number, to: number) => {
    for (let k = from; k < to && k < out.length; k++) if (out[k] !== '\n') out[k] = ' ';
  };
  let i = 0;
  while (i < src.length) {
    const c = src[i];
    if (c === '/' && src[i + 1] === '/') {
      const end = src.indexOf('\n', i);
      const stop = end < 0 ? src.length : end;
      blank(i, stop);
      i = stop;
    } else if (c === '/' && src[i + 1] === '*') {
      const end = src.indexOf('*/', i + 2);
      const stop = end < 0 ? src.length : end + 2;
      blank(i, stop);
      i = stop;
    } else if (c === '"' || c === "'") {
      const triple = src.startsWith(c.repeat(3), i);
      const quote = triple ? c.repeat(3) : c;
      let j = i + quote.length;
      while (j < src.length && !src.startsWith(quote, j)) {
        if (src[j] === '\\') j++;
        else if (!triple && src[j] === '\n') break;
        j++;
      }
      const stop = Math.min(src.length, j + quote.length);
      blank(i + quote.length, j); // the quotes stay, so `"a"` is still a token
      i = stop;
    } else {
      i++;
    }
  }
  return out.join('');
}

/** Brace depth at each offset of masked code. */
function depths(code: string): Int32Array {
  const d = new Int32Array(code.length + 1);
  let depth = 0;
  for (let i = 0; i < code.length; i++) {
    d[i] = depth;
    if (code[i] === '{') depth++;
    else if (code[i] === '}') depth = Math.max(0, depth - 1);
  }
  d[code.length] = depth;
  return d;
}

/** Splits a parameter list at commas outside <...>, (...) and [...]. */
function splitParams(list: string): string[] {
  const parts: string[] = [];
  let depth = 0;
  let cur = '';
  for (const c of list) {
    if (c === '<' || c === '(' || c === '[') depth++;
    else if (c === '>' || c === ')' || c === ']') depth--;
    if (c === ',' && depth === 0) {
      parts.push(cur);
      cur = '';
    } else {
      cur += c;
    }
  }
  if (cur.trim()) parts.push(cur);
  return parts.map((p) => p.trim()).filter((p) => p !== '');
}

function kindOf(type: string): ParamKind | null {
  const t = type.replace(/\s+/g, '').replace(/^(java\.lang\.|com\.sap\.it\.api\.mapping\.)/, '');
  if (t === '' || t === 'def' || t === 'String' || t === 'Object') return 'value';
  if (t === 'String[]' || t === 'String...' || t === 'Object[]' || t === 'Object...') return 'queue';
  if (t === 'Output') return 'output';
  if (t === 'MappingContext') return 'context';
  return null;
}

function parseParam(text: string): ScriptParam {
  // Annotations, `final` and a default value say nothing about the type.
  const p = text.replace(/@\w+(\([^)]*\))?/g, ' ').replace(/=.*$/s, '').replace(/\bfinal\b/g, ' ').trim();
  const m = /^(.*?)([A-Za-z_$][\w$]*)$/s.exec(p);
  const name = m ? m[2] : p;
  const type = m ? m[1].trim() : '';
  return { name, type, kind: kindOf(type) };
}

const ENGINE_TYPE: Record<ParamKind, string> = { value: 'String', queue: 'String[]', output: 'Output', context: 'MappingContext' };

function classify(params: ScriptParam[]): ScriptFunction['kind'] {
  if (params.length === 0) return null;
  if (params.length === 1) {
    const t = params[0].type.replace(/\s+/g, '');
    // As in the engine: one String or String[] parameter is a mapping function; one Object (or untyped) or a
    // Message is the script step's message.
    if (params[0].kind === 'value' && t !== 'String' && t !== 'java.lang.String') return 'step';
    if (params[0].kind === null) return /(^|\.)Message$/.test(t) ? 'step' : null;
  }
  return params.every((p) => p.kind !== null) ? 'mapping' : null;
}

/** The top-level functions the script declares, in source order (first declaration of each name and signature). */
export function scriptFunctions(src: string): ScriptFunction[] {
  const code = maskCode(src);
  const depth = depths(code);
  const out: ScriptFunction[] = [];
  // [modifiers] (def | Type) name ( params ) [throws ...] {
  const decl = /(^|[\s;}])((?:[A-Za-z_$][\w$.]*(?:<[^>{};]*>)?(?:\[\])*[ \t]+)+)([A-Za-z_$][\w$]*)\s*\(([^()]*)\)\s*(?:throws\s+[\w$.,\s]+)?\{/g;
  for (let m; (m = decl.exec(code)); ) {
    const at = m.index + m[1].length;
    if (depth[at] !== 0) continue;
    const words = m[2].trim().split(/\s+/);
    const name = m[3];
    if (KEYWORDS.has(name) || words.some((w) => KEYWORDS.has(w)) || words.includes('static') || words.includes('private')
      || words.includes('class') || words.includes('interface') || words.includes('enum')) continue;
    if (words.every((w) => MODIFIERS.has(w))) continue; // a call such as `foo(x) {` after a keyword-free modifier
    if (name === 'run') continue;
    const params = splitParams(m[4]).map(parseParam);
    const kind = classify(params);
    const parameters = params.map((p) => (p.kind ? ENGINE_TYPE[p.kind] : p.type || 'Object'));
    const signature = `${name}(${parameters.join(', ')})`;
    if (!out.some((f) => f.signature === signature)) out.push({ name, params, kind, parameters, signature });
  }
  return out;
}

/** A description of a parameter kind for the UI. */
export function kindLabel(kind: ParamKind): string {
  return kind === 'value' ? 'single value' : kind === 'queue' ? 'all values of a context'
    : kind === 'output' ? 'output queue' : 'header and property access';
}
