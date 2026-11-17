// The iFlow outline and diagram geometry: node --test tests/ (Node 22.18+ runs the TypeScript sources).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { adapters, border, extent, flowPath, outline, processOrder, typeLabel, type IflowFlow, type IflowModel, type IflowNode } from '../src/lib/iflowmodel.ts';

const n = (id: string, kind: string, process: string | null = 'P1', extra: Partial<IflowNode> = {}): IflowNode =>
  ({ id, name: id, kind, process, bounds: null, properties: {}, ...extra });
const seq = (id: string, source: string, target: string, extra: Partial<IflowFlow> = {}): IflowFlow =>
  ({ id, kind: 'sequence', name: '', source, target, waypoints: [], ...extra });

const model: IflowModel = {
  iflow: 'A', file: 'iflows/A/x.iflw',
  processes: [
    { id: 'P2', name: 'Local', kind: 'local', bounds: null },
    { id: 'E1', name: 'Errors', kind: 'exception', parent: 'P1', bounds: null },
    { id: 'P1', name: 'Main', kind: 'main', bounds: null },
  ],
  nodes: [
    n('Shop', 'sender', null), n('S', 'start'), n('A', 'step'), n('G', 'gateway'), n('B', 'step'), n('C', 'step'),
    n('J', 'gateway'), n('End', 'end'), n('Lost', 'step'), n('E1', 'subprocess'), n('ES', 'start', 'E1'),
    n('LS', 'start', 'P2'), n('Call', 'step', 'P1', { calls: 'P2', subActivityType: 'NonLoopingProcess' }),
  ],
  flows: [
    { id: 'M', kind: 'message', name: 'HTTPS', source: 'Shop', target: 'S', waypoints: [], adapter: 'HTTPS', direction: 'Sender' },
    seq('1', 'S', 'A'), seq('2', 'A', 'G'), seq('3', 'G', 'B', { name: 'ERP', condition: "${header.x} = 'ERP'" }),
    seq('4', 'G', 'C', { name: 'Other', default: true }), seq('5', 'B', 'J'), seq('6', 'C', 'J'), seq('7', 'J', 'Call'),
    seq('8', 'Call', 'End'),
  ],
};

test('processes: main, its exception subprocesses, then local ones', () => {
  assert.deepEqual(processOrder(model).map((p) => p.id), ['P1', 'E1', 'P2']);
});

test('the outline follows the flows, branches indented, a meeting point listed again', () => {
  const [main, errors, local] = outline(model);
  assert.deepEqual(main.entries.map((e) => [e.node.id, e.depth, e.route?.name ?? '', !!e.again, !!e.unreachable]), [
    ['S', 0, '', false, false], ['A', 0, '', false, false], ['G', 0, '', false, false],
    ['B', 1, 'ERP', false, false], ['J', 1, '', false, false], ['Call', 1, '', false, false], ['End', 1, '', false, false],
    ['C', 1, 'Other', false, false], ['J', 1, '', true, false],
    ['Lost', 0, '', false, true],
  ]);
  assert.equal(main.entries[7].route?.default, true);
  assert.deepEqual(errors.entries.map((e) => e.node.id), ['ES']);
  assert.deepEqual(local.entries.map((e) => e.node.id), ['LS']);
});

test('adapters name the system and the direction', () => {
  const [a] = adapters(model);
  assert.equal(a.inbound, true);
  assert.equal(a.system?.id, 'Shop');
  assert.equal(a.flow.adapter, 'HTTPS');
});

test('type labels read as in the editor, unknown types show the file\'s name', () => {
  assert.equal(typeLabel(n('x', 'step', 'P1', { activityType: 'Enricher' })), 'Content Modifier');
  assert.equal(typeLabel(n('x', 'step', 'P1', { activityType: 'ProcessCallElement', subActivityType: 'LoopingProcess' })), 'Looping Process Call');
  assert.equal(typeLabel(n('x', 'step', 'P1', { activityType: 'DBstorage', variant: 'put' })), 'Data Store (put)');
  assert.equal(typeLabel(n('x', 'step', 'P1', { activityType: 'SomethingNew', variant: 'newThing' })), 'newThing');
  assert.equal(typeLabel(n('x', 'start', 'P1', { event: 'error' })), 'Start (error)');
});

test('lines end at the shapes, not at their centres', () => {
  const box = { x: 0, y: 0, width: 100, height: 60 };
  assert.deepEqual(border(box, 'rect', 200, 30), [100, 30]);
  assert.deepEqual(border(box, 'rect', 50, 200), [50, 60]);
  const ev = { x: 0, y: 0, width: 32, height: 32 };
  assert.deepEqual(border(ev, 'circle', 116, 16), [32, 16]);
  const gw = { x: 0, y: 0, width: 40, height: 40 };
  assert.deepEqual(border(gw, 'diamond', 20, 100), [20, 40]);
  const a = n('a', 'start', 'P1', { bounds: ev });
  const b = n('b', 'step', 'P1', { bounds: { x: 200, y: -14, width: 100, height: 60 } });
  assert.deepEqual(flowPath(seq('f', 'a', 'b', { waypoints: [[16, 16], [250, 16]] }), a, b), [[32, 16], [200, 16]]);
  assert.deepEqual(flowPath(seq('f', 'a', 'b'), a, b), [[32, 16], [200, 16]], 'no waypoints: centre to centre, cut');
  assert.equal(extent(model), null, 'no layout');
});
