/**
 * Where elements and attributes are in an XML document's text, for the XPath tab: marking the nodes an
 * expression selected (the engine names them by a path of element positions, `at` in its result) and writing
 * the XPath of the element or attribute at the cursor.
 *
 * A scanner, not a parser: it follows tags, comments, CDATA, processing instructions and the DOCTYPE, and
 * does not check well-formedness or expand entities. An entity reference in the content (`&name;`, other than
 * the five XML predefines and character references) can stand for elements the parser counts and the scanner
 * cannot see, so positions from the engine would point at the wrong element; such a document is flagged
 * (`entities`) and the XPath tab neither marks nor writes paths for it. The element name sent with each
 * place is also compared, for what else could disagree.
 */

export interface ScannedAttribute {
  /** As written, with its prefix. */
  name: string;
  /** From the name to the closing quote. */
  start: number;
  end: number;
}

export interface ScannedElement {
  /** As written, with its prefix. */
  name: string;
  /** The `<` of the start tag. */
  start: number;
  /** Just after the start tag's `>`. */
  tagEnd: number;
  /** The `<` of the end tag, or tagEnd for an empty-element tag. */
  contentEnd: number;
  /** Just after the end tag (or the empty-element tag). */
  end: number;
  parent: number;
  /** Among the parent's element children, from 1. */
  position: number;
  children: number[];
  attributes: ScannedAttribute[];
  /** Namespace declarations on this element: prefix ('' for the default namespace) to URI. */
  ns: Record<string, string>;
}

export interface Scan {
  elements: ScannedElement[];
  /** Top-level elements (one in a well-formed document). */
  roots: number[];
  /** The content has entity references that may hold elements: positions in the text are not reliable. */
  entities: boolean;
}

/** A general entity reference: not one of XML's five predefined entities and not a character reference. */
const ENTITY_REF = /&(?!(?:lt|gt|amp|quot|apos);|#)[A-Za-z_:][\w.:-]*;/;

const NAME_CHAR = /[^\s/>=<"']/;

/** Scans a document. Text that is not XML gives what could be read up to the first problem. */
export function scanXml(text: string): Scan {
  const elements: ScannedElement[] = [];
  const roots: number[] = [];
  const open: number[] = [];
  let entities = false;
  let i = 0;
  const n = text.length;
  while (i < n) {
    const lt = text.indexOf('<', i);
    // Character data before the next markup: where an entity reference could add elements.
    if (!entities && open.length && ENTITY_REF.test(text.slice(i, lt < 0 ? n : lt))) entities = true;
    if (lt < 0) break;
    if (text.startsWith('<!--', lt)) {
      const e = text.indexOf('-->', lt + 4);
      i = e < 0 ? n : e + 3;
    } else if (text.startsWith('<![CDATA[', lt)) {
      const e = text.indexOf(']]>', lt + 9);
      i = e < 0 ? n : e + 3;
    } else if (text.startsWith('<?', lt)) {
      const e = text.indexOf('?>', lt + 2);
      i = e < 0 ? n : e + 2;
    } else if (text.startsWith('<!', lt)) {
      i = skipDeclaration(text, lt);
    } else if (text[lt + 1] === '/') {
      const gt = text.indexOf('>', lt);
      const top = open.pop();
      if (top !== undefined) {
        elements[top].contentEnd = lt;
        elements[top].end = gt < 0 ? n : gt + 1;
      }
      i = gt < 0 ? n : gt + 1;
    } else {
      let j = lt + 1;
      while (j < n && NAME_CHAR.test(text[j])) j++;
      const name = text.slice(lt + 1, j);
      if (name === '') {
        i = lt + 1;
        continue;
      }
      const attributes: ScannedAttribute[] = [];
      const ns: Record<string, string> = {};
      let empty = false;
      let tagEnd = n;
      while (j < n) {
        while (j < n && /\s/.test(text[j])) j++;
        if (text[j] === '>') {
          tagEnd = j + 1;
          break;
        }
        if (text.startsWith('/>', j)) {
          empty = true;
          tagEnd = j + 2;
          break;
        }
        const aStart = j;
        while (j < n && NAME_CHAR.test(text[j])) j++;
        const aName = text.slice(aStart, j);
        if (aName === '') {
          j++;
          continue;
        }
        while (j < n && /\s/.test(text[j])) j++;
        let value = '';
        if (text[j] === '=') {
          j++;
          while (j < n && /\s/.test(text[j])) j++;
          const q = text[j];
          if (q === '"' || q === "'") {
            const e = text.indexOf(q, j + 1);
            value = text.slice(j + 1, e < 0 ? n : e);
            j = e < 0 ? n : e + 1;
          }
        }
        attributes.push({ name: aName, start: aStart, end: j });
        if (aName === 'xmlns') ns[''] = decodeEntities(value);
        else if (aName.startsWith('xmlns:')) ns[aName.slice(6)] = decodeEntities(value);
      }
      const parent = open.length ? open[open.length - 1] : -1;
      const siblings = parent >= 0 ? elements[parent].children : roots;
      const index = elements.length;
      elements.push({ name, start: lt, tagEnd, contentEnd: tagEnd, end: tagEnd, parent, position: siblings.length + 1,
        children: [], attributes, ns });
      siblings.push(index);
      if (!empty) open.push(index);
      i = tagEnd;
    }
  }
  // Elements left open (the text ends early) end with the text.
  for (const k of open) {
    elements[k].contentEnd = n;
    elements[k].end = n;
  }
  return { elements, roots, entities };
}

/** Skips `<!DOCTYPE …>` with its internal subset, quoted strings and comments inside. */
function skipDeclaration(text: string, lt: number): number {
  let depth = 0;
  for (let j = lt + 2; j < text.length; j++) {
    const c = text[j];
    if (c === '"' || c === "'") {
      const e = text.indexOf(c, j + 1);
      if (e < 0) return text.length;
      j = e;
    } else if (text.startsWith('<!--', j)) {
      const e = text.indexOf('-->', j + 4);
      if (e < 0) return text.length;
      j = e + 2;
    } else if (c === '[') depth++;
    else if (c === ']') depth--;
    else if (c === '>' && depth <= 0) return j + 1;
  }
  return text.length;
}

function decodeEntities(v: string): string {
  return v.replace(/&(lt|gt|amp|quot|apos|#x[0-9a-fA-F]+|#\d+);/g, (_, e: string) =>
    e === 'lt' ? '<' : e === 'gt' ? '>' : e === 'amp' ? '&' : e === 'quot' ? '"' : e === 'apos' ? "'"
      : String.fromCodePoint(e[1] === 'x' ? parseInt(e.slice(2), 16) : parseInt(e.slice(1), 10)));
}

// ------------------------------------------------------------------ engine places → text ranges

/** Where the engine says a node is (`at` of an XPath result item). */
export interface NodePlace {
  path: number[];
  /** The element's name as written. */
  name: string;
  attribute?: string;
  text?: boolean;
}

export interface TextRange {
  start: number;
  end: number;
}

/** The text range of a node, or null where the scan and the engine do not agree. */
export function rangeOf(scan: Scan, at: NodePlace): TextRange | null {
  let list = scan.roots;
  let el: ScannedElement | undefined;
  for (const p of at.path) {
    const k = list[p - 1];
    if (k === undefined) return null;
    el = scan.elements[k];
    list = el.children;
  }
  if (!el || el.name !== at.name) return null;
  if (at.attribute !== undefined) {
    const a = el.attributes.find((x) => x.name === at.attribute);
    return a ? { start: a.start, end: a.end } : null;
  }
  if (at.text) return el.contentEnd > el.tagEnd ? { start: el.tagEnd, end: el.contentEnd } : null;
  return { start: el.start, end: el.end };
}

// ------------------------------------------------------------------ cursor → XPath

export interface CursorXPath {
  /** With a position wherever siblings share the name: selects this one node. */
  exact: string;
  /** Without positions: this node and all like it. */
  general: string;
  /** Namespaces on the way without a prefix in the Namespace mapping. */
  unmapped: string[];
}

/** The innermost element whose tags or content hold the offset, and the attribute there, if any. */
export function nodeAt(scan: Scan, offset: number): { element: number; attribute?: ScannedAttribute } | null {
  let found = -1;
  let list = scan.roots;
  for (;;) {
    const k = list.find((x) => scan.elements[x].start <= offset && offset < scan.elements[x].end);
    if (k === undefined) break;
    found = k;
    list = scan.elements[k].children;
  }
  if (found < 0) return null;
  const el = scan.elements[found];
  const attribute = offset < el.tagEnd ? el.attributes.find((a) => a.start <= offset && offset <= a.end && !isNsDecl(a.name)) : undefined;
  return { element: found, attribute };
}

const isNsDecl = (name: string) => name === 'xmlns' || name.startsWith('xmlns:');

function namespaceOf(scan: Scan, k: number, prefix: string): string {
  for (let e = k; e >= 0; e = scan.elements[e].parent) {
    const uri = scan.elements[e].ns[prefix];
    if (uri !== undefined) return uri;
  }
  return prefix === 'xml' ? 'http://www.w3.org/XML/1998/namespace' : '';
}

function split(qname: string): [string, string] {
  const c = qname.indexOf(':');
  return c < 0 ? ['', qname] : [qname.slice(0, c), qname.slice(c + 1)];
}

/**
 * The XPath of the element or attribute at the offset, with the Namespace mapping's prefixes (prefix → URI).
 * A namespace without a prefix there is written `Q{uri}name` in XPath 3.1 and `*[local-name()='name']` in 1.0,
 * and listed in `unmapped`.
 */
export function xpathAt(scan: Scan, offset: number, mapping: Record<string, string>, version: '1.0' | '3.1'): CursorXPath | null {
  const at = nodeAt(scan, offset);
  if (!at) return null;
  const unmapped = new Set<string>();
  const prefixFor = (uri: string) => Object.keys(mapping).find((p) => mapping[p] === uri && p !== '');
  const step = (local: string, uri: string, attr: boolean): { name: string; predicate: boolean } => {
    if (uri === '') return { name: (attr ? '@' : '') + local, predicate: false };
    const p = prefixFor(uri);
    if (p) return { name: (attr ? '@' : '') + p + ':' + local, predicate: false };
    unmapped.add(uri);
    if (version === '3.1') return { name: (attr ? '@' : '') + `Q{${uri}}${local}`, predicate: false };
    return { name: (attr ? '@*' : '*') + `[local-name()='${local}' and namespace-uri()='${uri}']`, predicate: true };
  };
  const exact: string[] = [];
  const general: string[] = [];
  const chain: number[] = [];
  for (let e = at.element; e >= 0; e = scan.elements[e].parent) chain.unshift(e);
  for (const k of chain) {
    const el = scan.elements[k];
    const [prefix, local] = split(el.name);
    const uri = namespaceOf(scan, k, prefix);
    const s = step(local, uri, false);
    const siblings = (el.parent >= 0 ? scan.elements[el.parent].children : scan.roots)
      .filter((x) => { const [p, l] = split(scan.elements[x].name); return l === local && namespaceOf(scan, x, p) === uri; });
    const pos = siblings.indexOf(k) + 1;
    exact.push(s.name + (siblings.length > 1 ? `[${pos}]` : ''));
    general.push(s.name);
  }
  if (at.attribute) {
    const [prefix, local] = split(at.attribute.name);
    // An attribute without a prefix is in no namespace (the default namespace does not apply to it).
    const s = step(local, prefix === '' ? '' : namespaceOf(scan, at.element, prefix), true);
    exact.push(s.name);
    general.push(s.name);
  }
  return { exact: '/' + exact.join('/'), general: '/' + general.join('/'), unmapped: [...unmapped] };
}
