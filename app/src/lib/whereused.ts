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

/** Per iFlow, each externalized parameter's value from `parameters.prop`. */
export function parameterValues(index: IndexedIflow[]): Map<string, Map<string, string>> {
  return new Map(index.map((i) => [i.iflow,
    new Map(i.parameters.filter((p) => p.value != null).map((p) => [p.name, p.value as string]))]));
}

/** The value with each `{{parameter}}` that has a value replaced by it (IflowIndex.PARAM); null if none has one. */
export function resolveParameters(value: string, params: Map<string, string> | undefined): string | null {
  let replaced = false;
  // A Content Modifier's table is XML text: a name in it is escaped once more (IflowIndex.unescapeXml).
  const table = value.trimStart().startsWith('<row');
  const entities: Record<string, string> = { lt: '<', gt: '>', amp: '&', apos: "'", quot: '"' };
  const out = value.replace(/\{\{([^{}]+)\}\}/g, (all, raw: string) => {
    const name = table ? raw.trim().replace(/&(lt|gt|amp|apos|quot);/g, (_, e: string) => entities[e]) : raw.trim();
    const v = params?.get(name);
    if (v == null) return all;
    replaced = true;
    return v;
  });
  return replaced ? out : null;
}

/** The iFlows' display names; a name that several iFlows share gets the iFlow's folder after it. */
export function iflowLabels(models: { iflow: string; name?: string | null }[]): Map<string, string> {
  const count = new Map<string, number>();
  for (const m of models) count.set(m.name ?? m.iflow, (count.get(m.name ?? m.iflow) ?? 0) + 1);
  return new Map(models.map((m) => {
    const name = m.name ?? m.iflow;
    return [m.iflow, count.get(name)! > 1 && name !== m.iflow ? `${name} · ${m.iflow}` : name];
  }));
}

export interface InventoryGroup {
  kind: string;
  label: string;
  /** Each distinct value with the number of places that use it, most used first, and what its parameters resolve to. */
  values: { value: string; count: number; resolved: string[] }[];
}

/** The overview of an empty search: the distinct addresses, credentials, queues, key aliases and files the iFlows use. */
export function inventory(index: IndexedIflow[]): InventoryGroup[] {
  const kinds = KINDS.filter(([id]) => id && id !== 'parameter');
  const params = parameterValues(index);
  const counts = new Map<string, Map<string, { count: number; resolved: Set<string> }>>(kinds.map(([id]) => [id, new Map()]));
  for (const i of index) {
    for (const e of i.entries) {
      const byValue = e.kind ? counts.get(e.kind) : undefined;
      if (!byValue || !e.value) continue;
      let v = byValue.get(e.value);
      if (!v) byValue.set(e.value, (v = { count: 0, resolved: new Set() }));
      v.count++;
      const r = resolveParameters(e.value, params.get(i.iflow));
      if (r != null) v.resolved.add(r);
    }
  }
  return kinds.map(([kind, label]) => ({ kind, label,
    values: [...counts.get(kind)!].map(([value, v]) => ({ value, count: v.count, resolved: [...v.resolved].sort() }))
      .sort((a, b) => b.count - a.count || a.value.localeCompare(b.value)) }))
    .filter((g) => g.values.length);
}

/** For a search: each iFlow's parameters whose name or value contains the query (ignoring case); iFlows without one left out. */
export function matchingParameters(index: IndexedIflow[], query: string): IndexedIflow[] {
  const q = query.trim().toLowerCase();
  if (!q) return [];
  return index.map((i) => ({ ...i, parameters: i.parameters.filter((p) =>
    p.name.toLowerCase().includes(q) || (p.value ?? '').toLowerCase().includes(q)) }))
    .filter((i) => i.parameters.length);
}
