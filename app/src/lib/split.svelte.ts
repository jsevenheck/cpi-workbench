/** Column widths (fr) of a view's panes, kept in localStorage per view. */
export function paneSizes(key: string, defaults: number[]): { key: string; defaults: number[]; sizes: number[] } {
  let stored: unknown = null;
  try {
    stored = JSON.parse(localStorage.getItem('cpiwb.panes.' + key) ?? 'null');
  } catch {
    stored = null;
  }
  const ok = Array.isArray(stored) && stored.length === defaults.length && stored.every((n) => typeof n === 'number' && n > 0);
  const panes = $state({ key, defaults, sizes: ok ? (stored as number[]) : [...defaults] });
  return panes;
}

export function savePaneSizes(p: { key: string; sizes: number[] }) {
  localStorage.setItem('cpiwb.panes.' + p.key, JSON.stringify(p.sizes));
}

/** grid-template-columns with a gutter column between the panes; panes never shrink below `min` px. */
export function paneColumns(sizes: number[], min = 160) {
  return sizes.map((s) => `minmax(${min}px, ${s}fr)`).join(' 5px ');
}
