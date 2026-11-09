/**
 * Arguments and results of Message Mapping functions in the Groovy tab. A queue (String[] argument or
 * Output) is a list of contexts; the text form has one context per line as a JSON array, e.g.
 * `["A", "B"]` then `["C"]`. A context holds strings, {"suppress": true} or {"xsiNil": true}, as the
 * engine reads and returns them. The whole queue as one JSON array of arrays is accepted too.
 */
export type QueueEntry = string | null | { suppress: true } | { xsiNil: true };
export type Queue = QueueEntry[][];

export function parseQueue(text: string): Queue {
  const t = text.trim();
  if (t === '') return [[]];
  const whole = tryJson(t);
  if (Array.isArray(whole) && whole.length > 0 && whole.every(Array.isArray)) return whole;
  return t.split('\n').filter((l) => l.trim() !== '').map((line, i) => {
    const ctx = tryJson(line.trim());
    if (!Array.isArray(ctx)) throw new Error(`Line ${i + 1} is not a JSON array of values, e.g. ["A", "B"]`);
    return ctx;
  });
}

export function formatQueue(q: Queue): string {
  return q.map((ctx) => JSON.stringify(ctx)).join('\n');
}

/** Values of a test case's or agent's argument for the text field; null stays null (a String argument may be null). */
export function argText(v: unknown): string | null {
  if (typeof v === 'string') return v;
  if (v === null) return null;
  if (v === undefined) return '';
  if (Array.isArray(v)) return formatQueue(v.length > 0 && v.every(Array.isArray) ? (v as Queue) : [v as QueueEntry[]]);
  return String(v);
}

/** The request's args: one per String or String[] parameter, in order. */
export function mappingArgs(parameters: string[], texts: (string | null)[]): unknown[] {
  const out: unknown[] = [];
  parameters.filter((p) => p === 'String' || p === 'String[]').forEach((p, i) => {
    if (p === 'String') return void out.push(texts[i] === undefined ? '' : texts[i]);
    try {
      out.push(parseQueue(texts[i] ?? ''));
    } catch (e) {
      throw new Error(`Argument ${i + 1}: ${(e as Error).message}`);
    }
  });
  return out;
}

function tryJson(s: string): unknown {
  try {
    return JSON.parse(s);
  } catch {
    return undefined;
  }
}
