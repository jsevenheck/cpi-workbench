import type { Row } from './types';

/**
 * Header or property rows from a test case: a list of rows, or the shorthand object
 * {"Name": "value"} / {"Name": {"value": "1", "type": "Integer"}}. Types follow the engine's
 * TypedValue.readMap, so a case runs the same in the app and on the CLI: a JSON boolean stays a
 * Boolean, other scalars become Strings.
 */
export function toRows(v: any): Row[] {
  if (!v) return [];
  if (Array.isArray(v)) return v.map((r) => ({ name: r.name, value: r.value ?? '', type: r.type ?? 'String', disabled: !!r.disabled }));
  return Object.entries(v).map(([name, val]: [string, any]) =>
    val !== null && typeof val === 'object' ? { name, value: val.value ?? '', type: val.type ?? 'String' }
      : { name, value: val === null ? '' : String(val), type: val === null ? 'null' : typeof val === 'boolean' ? 'Boolean' : 'String' });
}
