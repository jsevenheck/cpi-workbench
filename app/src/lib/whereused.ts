// The Where used tab's data: what `iflow.search` and `iflow.index` return (engine/core IflowIndex.java) and how the tab
// groups it. No imports of values: the unit tests load this on plain Node.

/** A property of an element, adapter or route in an imported iFlow. */
export interface IndexEntry {
  iflow: string;
  /** The element, adapter (message flow) or route (sequence flow) the property belongs to. */
  element: string;
  /** The diagram node to select for it: the element itself, an adapter's system, a route's Router. */
  node: string;
  name: string | null;
  type: string;
  /** What it is: a step's activity type, `SFTP receiver`, `Route`, … */
  label: string;
  key: string;
  value: string;
  /** address, credential, queue, key (a keystore alias) or file, where the key says so (plan F69); null otherwise. */
  kind: string | null;
}

export interface ParameterUse { element: string; node: string; name: string | null; key: string }

export interface Parameter {
  name: string;
  value: string | null;
  /** From parameters.propdef, where the export has one. */
  type: string | null;
  required: boolean | null;
  description: string | null;
  uses: ParameterUse[];
  unused: boolean;
  unresolved: boolean;
}

export interface IndexedIflow {
  iflow: string;
  name: string | null;
  file: string;
  error: string | null;
  entries: IndexEntry[];
  parameters: Parameter[];
}

export const KINDS: [string, string][] = [['', 'Anything'], ['address', 'Address'], ['credential', 'Credential'],
  ['queue', 'Queue'], ['key', 'Key alias'], ['file', 'File'], ['parameter', 'Parameter']];

/** Keys the index leaves out (IflowIndex.SKIPPED): searching for their values finds nothing. Keep both in step. */
export const SKIPPED_KEYS = new Set(['cmdVariantUri', 'componentVersion', 'componentNS', 'componentSWCVName', 'componentSWCVId']);

/**
 * What to search for a property value shown in the diagram. `iflow.models` cuts long values and ends them with `…`;
 * the index holds them whole, so the part before the cut is searched. Null for a key the index leaves out.
 */
export function whereUsedQuery(key: string, value: unknown): string | null {
  const v = value == null ? '' : String(value);
  if (!v || SKIPPED_KEYS.has(key)) return null;
  return v.length > 2000 && v.endsWith('…') ? v.slice(0, -1) : v;
}

export interface HitGroup {
  iflow: string;
  elements: { element: string; node: string; name: string; label: string; hits: IndexEntry[] }[];
}

/** Hits grouped by iFlow, then by element, in the order the engine found them. */
export function groupHits(hits: IndexEntry[]): HitGroup[] {
  const groups = new Map<string, Map<string, HitGroup['elements'][number]>>();
  for (const h of hits) {
    let byElement = groups.get(h.iflow);
    if (!byElement) groups.set(h.iflow, (byElement = new Map()));
    let el = byElement.get(h.element);
    if (!el) byElement.set(h.element, (el = { element: h.element, node: h.node, name: h.name || h.element, label: h.label, hits: [] }));
    el.hits.push(h);
  }
  return [...groups].map(([iflow, els]) => ({ iflow, elements: [...els.values()] }));
}

/** The parts of a value around each case-insensitive match of the query, for highlighting. */
export function highlight(value: string, query: string): { text: string; match: boolean }[] {
  const q = query.trim().toLowerCase();
  if (!q) return [{ text: value, match: false }];
  const out: { text: string; match: boolean }[] = [];
  // Lower case can change a character's length ('İ' becomes two units): `from` maps each unit of `lower` to the
  // value's index of the character it came from.
  let lower = '';
  const from: number[] = [];
  for (let i = 0; i < value.length; ) {
    const ch = String.fromCodePoint(value.codePointAt(i)!);
    const lc = ch.toLowerCase();
    lower += lc;
    for (let k = 0; k < lc.length; k++) from.push(i);
    i += ch.length;
  }
  from.push(value.length);
  let at = 0;
  for (let i = lower.indexOf(q); i >= 0; i = lower.indexOf(q, i + q.length)) {
    let e = i + q.length;
    while (e < lower.length && from[e] === from[e - 1]) e++; // a match ending inside a character marks all of it
    const start = Math.max(from[i], at), end = from[e];
    if (end <= start) continue;
    if (start > at) out.push({ text: value.slice(at, start), match: false });
    out.push({ text: value.slice(start, end), match: true });
    at = end;
  }
  if (at < value.length) out.push({ text: value.slice(at), match: false });
  return out;
}

export interface InventoryGroup {
  kind: string;
  label: string;
  /** Each distinct value with the number of places that use it, most used first. */
  values: { value: string; count: number }[];
}

/** The overview of an empty search: the distinct addresses, credentials, queues, key aliases and files the iFlows use. */
export function inventory(index: IndexedIflow[]): InventoryGroup[] {
  const kinds = KINDS.filter(([id]) => id && id !== 'parameter');
  const counts = new Map<string, Map<string, number>>(kinds.map(([id]) => [id, new Map()]));
  for (const i of index) {
    for (const e of i.entries) {
      const byValue = e.kind ? counts.get(e.kind) : undefined;
      if (byValue && e.value) byValue.set(e.value, (byValue.get(e.value) ?? 0) + 1);
    }
  }
  return kinds.map(([kind, label]) => ({ kind, label,
    values: [...counts.get(kind)!].map(([value, count]) => ({ value, count }))
      .sort((a, b) => b.count - a.count || a.value.localeCompare(b.value)) }))
    .filter((g) => g.values.length);
}
