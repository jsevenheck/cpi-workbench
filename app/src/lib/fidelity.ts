// What backs each step's behaviour, read from docs/fidelity.md (its tables' *Applies to* and *Basis* columns), so the
// app and the page cannot disagree. No imports of values: the unit tests load this on plain Node.

export const BASES = ['Tenant', 'Documented', 'Assumption', 'Not supported'] as const;
export type Basis = (typeof BASES)[number];

export const STEP_IDS = ['xslt', 'xsd', 'xpath', 'groovy', 'mapping', 'contentModifier', 'filter', 'router', 'splitter', 'gather',
  'converter', 'encoder', 'decoder', 'messageDigest'] as const;
export type StepId = (typeof STEP_IDS)[number];

export interface FidelityRow {
  section: string;
  area: string;
  appliesTo: string[];
  basis: string;
  status: string;
}

/** A Markdown table row's cells; `\|` is a literal bar inside a cell. */
function cells(line: string): string[] {
  return line.trim().replace(/^\|/, '').replace(/\|$/, '').split(/(?<!\\)\|/).map((c) => c.trim().replaceAll('\\|', '|'));
}

/** The rows of every table with *Applies to* and *Basis* columns, with the `##` heading they are under. */
export function parseFidelity(md: string): FidelityRow[] {
  const rows: FidelityRow[] = [];
  let section = '';
  let columns: string[] | null = null;
  for (const line of md.split(/\r?\n/)) {
    if (line.startsWith('## ')) { section = line.slice(3).trim(); columns = null; continue; }
    if (!line.startsWith('|')) { columns = null; continue; }
    const c = cells(line);
    if (!columns) { columns = c; continue; }
    if (c.every((x) => /^-+$/.test(x))) continue;
    const at = (name: string) => columns!.indexOf(name);
    if (at('Applies to') < 0 || at('Basis') < 0) continue;
    rows.push({
      section, area: c[at('Area')] ?? '', basis: c[at('Basis')] ?? '', status: c[at('Status')] ?? '',
      appliesTo: (c[at('Applies to')] ?? '').split(',').map((s) => s.trim()).filter(Boolean),
    });
  }
  return rows;
}

/** The weakest of Tenant, Documented and Assumption among a step's rows; refusals are listed, not ranked. */
export function weakest(rows: FidelityRow[]): Basis | null {
  let worst = -1;
  for (const r of rows) {
    const i = BASES.indexOf(r.basis as Basis);
    if (i >= 0 && i < 3) worst = Math.max(worst, i);
  }
  return worst < 0 ? null : BASES[worst];
}

export function rowsFor(rows: FidelityRow[], step: StepId): FidelityRow[] {
  return rows.filter((r) => r.appliesTo.includes(step));
}

/** Markdown inline markup as plain text: links keep their text, `**`, `*` and backticks go. */
export function plainText(md: string): string {
  return md.replace(/\[([^\]]*)\]\([^)]*\)/g, '$1').replace(/\*\*([^*]+)\*\*/g, '$1').replace(/(^|\W)\*([^*]+)\*(?=\W|$)/g, '$1$2')
    .replace(/`([^`]*)`/g, '$1');
}
