// The last runs of a tab in this session (in memory only), for "compare with an earlier run".
import type { Profile } from './engine';

export interface HistoryEntry<R> {
  /** When the run ended (ms since the epoch). */
  at: number;
  profile: Profile;
  /** What ran: the mapping's or script's path, with a script's function. */
  what: string;
  result: R;
}

/** Runs a tab keeps; older ones drop out. */
export const HISTORY_MAX = 20;

interface RunLike {
  ok: boolean;
  output?: unknown;
  mapping?: unknown;
  diagnostics?: unknown;
}

/** Same outcome: status, output message, a mapping function's result and the diagnostics. */
export function sameOutcome(a: RunLike, b: RunLike): boolean {
  return a.ok === b.ok && JSON.stringify(a.output ?? null) === JSON.stringify(b.output ?? null)
    && JSON.stringify(a.mapping ?? null) === JSON.stringify(b.mapping ?? null)
    && JSON.stringify(a.diagnostics ?? null) === JSON.stringify(b.diagnostics ?? null);
}

/**
 * The list with a new run in front. A run of the same thing on the same runtime with the same outcome as the newest
 * one only moves that one's time (live runs repeat a lot while typing), so the list holds runs that differ.
 */
export function addRun<R extends RunLike>(list: HistoryEntry<R>[], entry: HistoryEntry<R>, max = HISTORY_MAX): HistoryEntry<R>[] {
  const last = list[0];
  if (last && last.what === entry.what && last.profile === entry.profile && sameOutcome(last.result, entry.result)) {
    return [{ ...last, at: entry.at, result: entry.result }, ...list.slice(1)];
  }
  return [entry, ...list].slice(0, max);
}

export function timeOf(at: number): string {
  const d = new Date(at);
  return [d.getHours(), d.getMinutes(), d.getSeconds()].map((n) => String(n).padStart(2, '0')).join(':');
}
