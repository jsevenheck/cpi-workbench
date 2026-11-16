// An imported iFlow as a whole (engine: iflow.models; docs/plan.md F68): the outline the sidebar lists and the
// geometry the Diagram tab draws. Read-only: nothing here runs.

export interface Box { x: number; y: number; width: number; height: number }

export interface IflowNode {
  id: string;
  name: string;
  /** sender / receiver (systems), start, end, event, gateway, step, subprocess. */
  kind: string;
  /** The BPMN element (callActivity, exclusiveGateway, …). */
  element?: string;
  process: string | null;
  activityType?: string | null;
  subActivityType?: string | null;
  /** The name in cmdVariantUri (cname::…). */
  variant?: string | null;
  bounds: Box | null;
  properties: Record<string, string>;
  /** error, message, timer, … for events. */
  event?: string;
  /** Process Call: the id of the local process it runs. */
  calls?: string;
  loop?: boolean;
}

export interface IflowFlow {
  id: string;
  kind: 'sequence' | 'message';
  name: string;
  source: string;
  target: string;
  waypoints: [number, number][];
  condition?: string | null;
  expressionType?: string | null;
  default?: boolean;
  adapter?: string;
  direction?: string | null;
  properties?: Record<string, string>;
}

export interface IflowProcess {
  id: string;
  name: string;
  kind: 'main' | 'local' | 'exception' | 'subprocess';
  parent?: string;
  pool?: string | null;
  bounds: Box | null;
}

export interface IflowModel {
  /** The folder under iflows/. */
  iflow: string;
  file: string;
  name?: string;
  processes?: IflowProcess[];
  nodes?: IflowNode[];
  flows?: IflowFlow[];
  hasLayout?: boolean;
  error?: string;
}

export interface OutlineEntry {
  node: IflowNode;
  depth: number;
  /** The route that leads here from a Router or Multicast. */
  route?: { name: string; condition?: string | null; default?: boolean };
  /** Reached a second time (branches meet): listed once more, as a reference. */
  again?: boolean;
  /** Reached from no start event. */
  unreachable?: boolean;
}

export interface OutlineGroup {
  process: IflowProcess;
  entries: OutlineEntry[];
}

/** Main processes first, each followed by its exception subprocesses, then the local processes likewise. */
export function processOrder(model: IflowModel): IflowProcess[] {
  const all = model.processes ?? [];
  const out: IflowProcess[] = [];
  const add = (p: IflowProcess) => {
    out.push(p);
    for (const c of all) if (c.parent === p.id) add(c);
  };
  for (const kind of ['main', 'local'] as const) for (const p of all) if (p.kind === kind && !p.parent) add(p);
  for (const p of all) if (!out.includes(p)) out.push(p);
  return out;
}

/**
 * Each process's elements in flow order: from its start events along the sequence flows; the routes of a gateway
 * are indented under it, with their names and conditions. Elements that no start event reaches come last.
 */
export function outline(model: IflowModel): OutlineGroup[] {
  const nodes = model.nodes ?? [];
  const flows = (model.flows ?? []).filter((f) => f.kind === 'sequence');
  const byId = new Map(nodes.map((n) => [n.id, n]));
  const out = new Map<string, IflowFlow[]>();
  for (const f of flows) out.set(f.source, [...(out.get(f.source) ?? []), f]);
  const groups: OutlineGroup[] = [];
  for (const process of processOrder(model)) {
    const own = nodes.filter((n) => n.process === process.id && n.kind !== 'subprocess');
    const entries: OutlineEntry[] = [];
    const seen = new Set<string>();
    const walk = (start: string, depth: number, route?: OutlineEntry['route']) => {
      let id: string | undefined = start;
      let r = route;
      // A chain of single flows stays at one depth; a loop here would only recurse at branches.
      while (id) {
        const node = byId.get(id);
        if (!node) return;
        if (seen.has(id)) {
          entries.push({ node, depth, route: r, again: true });
          return;
        }
        seen.add(id);
        entries.push({ node, depth, route: r });
        const next: IflowFlow[] = out.get(id) ?? [];
        if (next.length === 1) {
          // A Join, or a Router with one route: no branch, but a route keeps its label.
          const f = next[0];
          id = f.target;
          r = node.kind === 'gateway' && (f.name || f.condition) ? { name: f.name, condition: f.condition, default: f.default } : undefined;
          continue;
        }
        for (const f of next) walk(f.target, depth + 1, { name: f.name, condition: f.condition, default: f.default });
        return;
      }
    };
    for (const n of own) if (n.kind === 'start') walk(n.id, 0);
    for (const n of own) if (!seen.has(n.id)) {
      seen.add(n.id);
      entries.push({ node: n, depth: 0, unreachable: true });
    }
    groups.push({ process, entries });
  }
  return groups;
}

/** The adapters: who sends to the iFlow, and whom it calls. */
export function adapters(model: IflowModel): { flow: IflowFlow; system: IflowNode | undefined; inbound: boolean }[] {
  const byId = new Map((model.nodes ?? []).map((n) => [n.id, n]));
  return (model.flows ?? []).filter((f) => f.kind === 'message').map((flow) => {
    const src = byId.get(flow.source);
    const inbound = src?.kind === 'sender' || flow.direction === 'Sender';
    return { flow, system: inbound ? src : byId.get(flow.target), inbound };
  });
}

const ACTIVITY: Record<string, string> = {
  Enricher: 'Content Modifier', ExclusiveGateway: 'Router', Filter: 'Filter', Splitter: 'Splitter', Gather: 'Gather',
  XmlValidator: 'XML Validator', Encoder: 'Encoder', Decoder: 'Decoder', MessageDigest: 'Message Digest',
  ErrorEventSubProcessTemplate: 'Exception Subprocess', JsonToXmlConverter: 'JSON to XML Converter',
  XmlToJsonConverter: 'XML to JSON Converter', CsvToXmlConverter: 'CSV to XML Converter', XmlToCsvConverter: 'XML to CSV Converter',
  SequentialMulticast: 'Sequential Multicast', DBstorage: 'Data Store',
};
const SUB: Record<string, string> = {
  GroovyScript: 'Groovy Script', JavaScript: 'JavaScript', XSLTMapping: 'XSLT Mapping', MessageMapping: 'Message Mapping',
  NonLoopingProcess: 'Process Call', LoopingProcess: 'Looping Process Call',
};

/** A name for the element's type. Known ones read as in the editor; the others show the name the file gives. */
export function typeLabel(n: IflowNode): string {
  if (n.kind === 'sender') return 'Sender';
  if (n.kind === 'receiver') return 'Receiver';
  if (n.kind === 'start' || n.kind === 'end' || n.kind === 'event') {
    const what = n.kind === 'start' ? 'Start' : n.kind === 'end' ? 'End' : 'Intermediate';
    return n.event ? `${what} (${n.event})` : `${what} event`;
  }
  const sub = n.subActivityType ? SUB[n.subActivityType] : undefined;
  if (sub) return sub;
  const act = n.activityType ? ACTIVITY[n.activityType] : undefined;
  if (act) return act === 'Data Store' && n.variant ? `Data Store (${n.variant})` : act;
  return n.variant || n.activityType || n.element || n.kind;
}

/** Where the line from a shape's centre towards (tx, ty) leaves the shape: a circle for events, a diamond for gateways. */
export function border(b: Box, shape: 'rect' | 'circle' | 'diamond', tx: number, ty: number): [number, number] {
  const cx = b.x + b.width / 2;
  const cy = b.y + b.height / 2;
  const dx = tx - cx;
  const dy = ty - cy;
  if (dx === 0 && dy === 0) return [cx, cy];
  const hw = b.width / 2;
  const hh = b.height / 2;
  let t: number;
  if (shape === 'circle') t = Math.min(hw, hh) / Math.hypot(dx, dy);
  else if (shape === 'diamond') t = 1 / (Math.abs(dx) / hw + Math.abs(dy) / hh);
  else t = Math.min(dx === 0 ? Infinity : hw / Math.abs(dx), dy === 0 ? Infinity : hh / Math.abs(dy));
  return [cx + dx * Math.min(t, 1), cy + dy * Math.min(t, 1)];
}

export function shapeOf(n: IflowNode | undefined): 'rect' | 'circle' | 'diamond' {
  return n?.kind === 'gateway' ? 'diamond' : n && ['start', 'end', 'event'].includes(n.kind) ? 'circle' : 'rect';
}

/**
 * A flow's line, cut at the shapes it joins: the editor writes waypoints from centre to centre (F68). Without
 * waypoints, a straight line between the shapes' centres.
 */
export function flowPath(f: IflowFlow, src: IflowNode | undefined, tgt: IflowNode | undefined): [number, number][] {
  const centre = (n: IflowNode | undefined): [number, number] | null =>
    n?.bounds ? [n.bounds.x + n.bounds.width / 2, n.bounds.y + n.bounds.height / 2] : null;
  let pts: [number, number][] = f.waypoints?.length >= 2 ? f.waypoints.map(([x, y]) => [x, y]) : [];
  if (!pts.length) {
    const a = centre(src);
    const b = centre(tgt);
    if (!a || !b) return [];
    pts = [a, b];
  }
  if (src?.bounds) pts[0] = border(src.bounds, shapeOf(src), pts[1][0], pts[1][1]);
  const last = pts.length - 1;
  if (tgt?.bounds) pts[last] = border(tgt.bounds, shapeOf(tgt), pts[last - 1][0], pts[last - 1][1]);
  return pts;
}

/** The area all shapes cover, with a margin. */
export function extent(model: IflowModel, margin = 20): Box | null {
  const boxes = [...(model.nodes ?? []).map((n) => n.bounds), ...(model.processes ?? []).map((p) => p.bounds)]
    .filter((b): b is Box => !!b);
  if (!boxes.length) return null;
  const x = Math.min(...boxes.map((b) => b.x));
  const y = Math.min(...boxes.map((b) => b.y));
  const r = Math.max(...boxes.map((b) => b.x + b.width));
  const btm = Math.max(...boxes.map((b) => b.y + b.height));
  return { x: x - margin, y: y - margin, width: r - x + 2 * margin, height: btm - y + 2 * margin };
}
