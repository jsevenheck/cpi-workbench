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
  const lower = value.toLowerCase();
  let at = 0;
  for (let i = lower.indexOf(q); i >= 0; i = lower.indexOf(q, at)) {
    if (i > at) out.push({ text: value.slice(at, i), match: false });
    out.push({ text: value.slice(i, i + q.length), match: true });
    at = i + q.length;
  }
  if (at < value.length) out.push({ text: value.slice(at), match: false });
  return out;
}
