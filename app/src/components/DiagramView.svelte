<script lang="ts">
  // The imported iFlows drawn from the layout their export carries (plan F68), read-only. A step that a tab can run
  // opens there (double-click, or Open in the details); the others show their settings.
  import { untrack } from 'svelte';
  import { app, iflowStepFor, openIflowStep, selectIflowNode, showWhereUsed } from '../lib/store.svelte';
  import { extent, flowPath, shapeOf, typeLabel, type Box, type IflowModel, type IflowNode } from '../lib/iflowmodel';

  const TAB: Record<string, string> = { groovy: 'Groovy Script', xslt: 'XSLT Mapping', xsd: 'XSD Validation', mmap: 'Groovy Script' };

  let model = $derived<IflowModel | undefined>(
    app.iflowModels.find((m) => m.iflow === app.iflowSelected?.iflow) ?? app.iflowModels[0]);
  let nodes = $derived(new Map((model?.nodes ?? []).map((n) => [n.id, n])));
  let selected = $derived(app.iflowSelected?.id && app.iflowSelected.iflow === model?.iflow ? nodes.get(app.iflowSelected.id) : undefined);
  let full = $derived(model ? extent(model) : null);

  let view = $state<Box | null>(null);
  let svg: SVGSVGElement | undefined = $state();
  let lastModel = '';
  $effect(() => {
    // Another iFlow: show all of it.
    const key = model?.iflow ?? '';
    if (key !== lastModel) {
      lastModel = key;
      view = full ? { ...full } : null;
    }
  });
  $effect(() => {
    // An element chosen in the list that is out of sight: centre it. Only when the choice changes: run on every
    // change of the view, this would pull a chosen element back while the user pans away from it.
    const b = selected?.bounds;
    const v = untrack(() => view);
    if (!b || !v) return;
    if (b.x < v.x || b.y < v.y || b.x + b.width > v.x + v.width || b.y + b.height > v.y + v.height) {
      view = { ...v, x: b.x + b.width / 2 - v.width / 2, y: b.y + b.height / 2 - v.height / 2 };
    }
  });

  function fit() {
    view = full ? { ...full } : null;
  }

  function zoom(factor: number, cx?: number, cy?: number) {
    if (!view) return;
    const x = cx ?? view.x + view.width / 2;
    const y = cy ?? view.y + view.height / 2;
    const w = Math.min(Math.max(view.width * factor, 100), (full?.width ?? 1000) * 4);
    const k = w / view.width;
    view = { x: x - (x - view.x) * k, y: y - (y - view.y) * k, width: w, height: view.height * k };
  }

  /** The point in diagram coordinates under the mouse. */
  function at(e: MouseEvent): [number, number] {
    const r = svg!.getBoundingClientRect();
    const v = view!;
    // preserveAspectRatio meet: the scale is the smaller one, the rest is centred.
    const s = Math.min(r.width / v.width, r.height / v.height);
    const ox = (r.width - v.width * s) / 2;
    const oy = (r.height - v.height * s) / 2;
    return [v.x + (e.clientX - r.left - ox) / s, v.y + (e.clientY - r.top - oy) / s];
  }

  function onWheel(e: WheelEvent) {
    if (!view) return;
    e.preventDefault();
    const [x, y] = at(e);
    zoom(e.deltaY > 0 ? 1.15 : 1 / 1.15, x, y);
  }

  let drag: { x: number; y: number; moved: boolean } | null = null;
  function onDown(e: PointerEvent) {
    if (!view || e.button !== 0) return;
    drag = { x: e.clientX, y: e.clientY, moved: false };
    (e.currentTarget as Element).setPointerCapture(e.pointerId);
  }
  function onMove(e: PointerEvent) {
    if (!drag || !view) return;
    const r = svg!.getBoundingClientRect();
    const s = Math.min(r.width / view.width, r.height / view.height);
    const dx = (e.clientX - drag.x) / s;
    const dy = (e.clientY - drag.y) / s;
    if (Math.abs(e.clientX - drag.x) + Math.abs(e.clientY - drag.y) > 3) drag.moved = true;
    if (drag.moved) {
      view = { ...view, x: view.x - dx, y: view.y - dy };
      drag.x = e.clientX;
      drag.y = e.clientY;
    }
  }
  function onUp(e: PointerEvent) {
    const d = drag;
    drag = null;
    if (!d || d.moved || !model) return;
    // A click, not a pan: the element under the pointer, or none.
    const el = (document.elementFromPoint(e.clientX, e.clientY) as Element | null)?.closest('[data-node]');
    selectIflowNode(model.iflow, el?.getAttribute('data-node') ?? null);
  }

  function onDbl(e: MouseEvent) {
    const id = (document.elementFromPoint(e.clientX, e.clientY) as Element | null)?.closest('[data-node]')?.getAttribute('data-node');
    const n = id ? nodes.get(id) : undefined;
    if (n) open(n);
  }

  function open(n: IflowNode) {
    const step = model ? iflowStepFor(model.iflow, n.id) : undefined;
    if (step) void openIflowStep(step);
  }

  function tabFor(n: IflowNode) {
    const step = model ? iflowStepFor(model.iflow, n.id) : undefined;
    return step ? TAB[step.type] ?? 'Flow Steps' : null;
  }

  /** The name in lines that fit a box of that width (about 6.5 px per character at 11 px). */
  function lines(text: string, width: number, max = 3): string[] {
    const per = Math.max(4, Math.floor(width / 6.5));
    const out: string[] = [];
    let line = '';
    for (const word of text.split(/\s+/).filter(Boolean)) {
      if ((line + ' ' + word).trim().length > per && line) {
        out.push(line);
        line = word;
      } else line = (line + ' ' + word).trim();
    }
    if (line) out.push(line);
    if (out.length > max) {
      out.length = max;
      out[max - 1] = out[max - 1].slice(0, per - 1) + '…';
    }
    return out.map((l) => (l.length > per ? l.slice(0, per - 1) + '…' : l));
  }

  function mid(pts: [number, number][]): [number, number] {
    if (pts.length === 0) return [0, 0];
    const i = Math.floor((pts.length - 1) / 2);
    const [a, b] = [pts[i], pts[Math.min(i + 1, pts.length - 1)]];
    return [(a[0] + b[0]) / 2, (a[1] + b[1]) / 2];
  }

  let processName = $derived((id: string | null) => model?.processes?.find((p) => p.id === id)?.name ?? '');
  let properties = $derived(selected ? Object.entries(selected.properties ?? {}) : []);
  // A sender or receiver system shows the adapters that connect it, with their settings (address, credential, …).
  let adapters = $derived(selected && (selected.kind === 'sender' || selected.kind === 'receiver')
    ? (model?.flows ?? []).filter((f) => f.kind === 'message' && (f.source === selected!.id || f.target === selected!.id))
    : []);
  const META = new Set(['cmdVariantUri', 'componentVersion', 'ComponentType', 'direction']);
</script>

<div class="diagram">
  <header class="bar">
    {#if app.iflowModels.length > 1}
      <select aria-label="iFlow" value={model?.iflow} onchange={(e) => selectIflowNode(e.currentTarget.value, null)}>
        {#each app.iflowModels as m}<option value={m.iflow}>{m.name ?? m.iflow}</option>{/each}
      </select>
    {:else if model}
      <b>{model.name ?? model.iflow}</b>
    {/if}
    <span class="muted file">{model?.file}</span>
    <span class="spacer"></span>
    {#if view}
      <button class="small" onclick={() => zoom(1 / 1.3)} title="Zoom in">+</button>
      <button class="small" onclick={() => zoom(1.3)} title="Zoom out">−</button>
      <button class="small" onclick={fit} title="Show the whole iFlow">Fit</button>
    {/if}
  </header>
  <div class="body">
    <div class="canvas">
      {#if !model}
        <p class="hint">Import an iFlow (sidebar, <b>Import iFlow</b>) to see it here.</p>
      {:else if model.error}
        <p class="hint err">{model.error}</p>
      {:else if !view}
        <p class="hint">This iFlow's file has no diagram layout. The list on the left shows its elements.</p>
      {:else}
        <svg bind:this={svg} viewBox={`${view.x} ${view.y} ${view.width} ${view.height}`} role="img"
          aria-label={`Diagram of ${model.name ?? model.iflow}`} onwheel={onWheel} onpointerdown={onDown} onpointermove={onMove}
          onpointerup={onUp} ondblclick={onDbl}>
          <defs>
            <marker id="arrow" viewBox="0 0 10 10" refX="10" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
              <path d="M0,0 L10,5 L0,10 z" class="arrowhead" />
            </marker>
          </defs>
          {#each model.processes ?? [] as p}
            {#if p.bounds}
              <g class="pool" class:sub={p.kind === 'exception' || p.kind === 'subprocess'}>
                <rect x={p.bounds.x} y={p.bounds.y} width={p.bounds.width} height={p.bounds.height} rx={p.parent ? 10 : 0} />
                <text x={p.bounds.x + 8} y={p.bounds.y + 16} class="pool-label">{p.name}{p.kind === 'local' ? ' (local)' : ''}</text>
              </g>
            {/if}
          {/each}
          {#each model.flows ?? [] as f}
            {@const pts = flowPath(f, nodes.get(f.source), nodes.get(f.target))}
            {#if pts.length}
              {@const [mx, my] = mid(pts)}
              <polyline class="flow" class:message={f.kind === 'message'} points={pts.map((p) => p.join(',')).join(' ')}
                marker-end="url(#arrow)" />
              {#if f.kind === 'message' && f.adapter}
                <text class="flow-label" x={mx} y={my - 5} text-anchor="middle">{f.adapter}</text>
              {:else if f.name || f.default}
                <text class="flow-label" x={mx + 4} y={my - 5}>{f.name}{f.default ? ' (default)' : ''}</text>
              {/if}
            {/if}
          {/each}
          {#each model.nodes ?? [] as n}
            {#if n.bounds && n.kind !== 'subprocess'}
              {@const b = n.bounds}
              {@const shape = shapeOf(n)}
              {@const runnable = !!iflowStepFor(model.iflow, n.id)}
              <g class="node {n.kind}" class:runnable class:selected={selected?.id === n.id} data-node={n.id}>
                <title>{n.name || n.id} · {typeLabel(n)}{runnable ? ' · double-click to open' : ''}</title>
                {#if shape === 'circle'}
                  <circle cx={b.x + b.width / 2} cy={b.y + b.height / 2} r={Math.min(b.width, b.height) / 2} />
                  {#if n.event === 'error'}<text class="glyph" x={b.x + b.width / 2} y={b.y + b.height / 2 + 4} text-anchor="middle">!</text>{/if}
                  <text class="caption" x={b.x + b.width / 2} y={b.y + b.height + 13} text-anchor="middle">{n.name}</text>
                {:else if shape === 'diamond'}
                  <polygon points={`${b.x + b.width / 2},${b.y} ${b.x + b.width},${b.y + b.height / 2} ${b.x + b.width / 2},${b.y + b.height} ${b.x},${b.y + b.height / 2}`} />
                  <text class="glyph" x={b.x + b.width / 2} y={b.y + b.height / 2 + 5} text-anchor="middle">{n.element === 'parallelGateway' ? '+' : '×'}</text>
                  <text class="caption" x={b.x + b.width / 2} y={b.y - 5} text-anchor="middle">{n.name}</text>
                {:else}
                  <rect x={b.x} y={b.y} width={b.width} height={b.height} rx="6" />
                  <text class="type" x={b.x + 6} y={b.y + 12}>{typeLabel(n)}</text>
                  {#each lines(n.name || n.id, b.width - 10) as line, i}
                    <text class="name" x={b.x + b.width / 2} y={b.y + 28 + i * 12} text-anchor="middle">{line}</text>
                  {/each}
                  {#if n.loop}<text class="glyph" x={b.x + b.width - 10} y={b.y + b.height - 5}>↻</text>{/if}
                {/if}
              </g>
            {/if}
          {/each}
        </svg>
      {/if}
    </div>
    {#if selected}
      <aside class="details" aria-label="Element details">
        <div class="head">
          <b>{selected.name || selected.id}</b>
          <button class="icon" onclick={() => selectIflowNode(model!.iflow, null)} aria-label="Close details">✕</button>
        </div>
        <div class="muted">{typeLabel(selected)}{selected.process ? ' · ' + processName(selected.process) : ''}</div>
        {#if tabFor(selected)}
          <button class="primary open" onclick={() => open(selected!)}>Open in {tabFor(selected)}</button>
        {:else}
          <p class="muted small">Not simulated: its settings as the iFlow stores them (read-only).</p>
        {/if}
        {#if selected.calls}<p class="small">Runs the process <b>{processName(selected.calls) || selected.calls}</b>{selected.loop ? ' in a loop' : ''}.</p>{/if}
        {#if properties.length}
          <table>
            <tbody>
              {#each properties as [k, v]}<tr><td class="mono key">{k}</td><td class="mono">{v}</td>
                <td>{#if v && k !== 'cmdVariantUri' && k !== 'componentVersion'}<button class="icon" onclick={() => showWhereUsed(String(v))}
                  title="Where else is this value used?" aria-label="Where else is {k} used">⌕</button>{/if}</td></tr>{/each}
            </tbody>
          </table>
        {/if}
        {#each adapters as f (f.id)}
          <h4 class="adapter">{f.adapter} {f.direction === 'Receiver' ? 'receiver' : 'sender'} adapter</h4>
          <table>
            <tbody>
              {#each Object.entries(f.properties ?? {}).filter(([k, v]) => v && !META.has(k)) as [k, v]}<tr><td class="mono key">{k}</td><td class="mono">{v}</td>
                <td><button class="icon" onclick={() => showWhereUsed(String(v))}
                  title="Where else is this value used?" aria-label="Where else is {k} used">⌕</button></td></tr>{/each}
            </tbody>
          </table>
        {/each}
        <p class="muted small mono">{selected.id}</p>
      </aside>
    {/if}
  </div>
</div>

<style>
  .diagram { display: flex; flex-direction: column; height: 100%; min-height: 0; font-size: 12px; }
  .bar { display: flex; align-items: center; gap: 8px; padding: 6px 10px; border-bottom: 1px solid var(--border); }
  .bar .file { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; min-width: 0; }
  .spacer { flex: 1; }
  .body { flex: 1; display: flex; min-height: 0; }
  .canvas { flex: 1; min-width: 0; position: relative; background: var(--bg); }
  svg { width: 100%; height: 100%; display: block; cursor: grab; user-select: none; touch-action: none; }
  svg:active { cursor: grabbing; }
  .hint { color: var(--muted); padding: 16px; margin: 0; }
  .hint.err { color: var(--err); }
  .muted { color: var(--muted); }
  .small { font-size: 11px; }
  .mono { font-family: var(--mono); }
  .pool rect { fill: var(--surface); stroke: var(--border); stroke-width: 1; }
  .pool.sub rect { fill: none; stroke-dasharray: 6 4; }
  .pool-label { fill: var(--muted); font-size: 12px; font-weight: 600; }
  .flow { fill: none; stroke: var(--muted); stroke-width: 1.4; }
  .flow.message { stroke-dasharray: 6 4; }
  .arrowhead { fill: var(--muted); }
  .flow-label { fill: var(--muted); font-size: 10px; }
  .node rect, .node circle, .node polygon { fill: var(--bg); stroke: var(--border); stroke-width: 1.4; }
  .node.end circle { stroke-width: 3; }
  .node.sender rect, .node.receiver rect { fill: var(--surface); }
  .node.runnable rect, .node.runnable circle, .node.runnable polygon { stroke: var(--accent); cursor: pointer; }
  .node.selected rect, .node.selected circle, .node.selected polygon { stroke: var(--accent); stroke-width: 3; fill: var(--active); }
  .node { cursor: pointer; }
  .node .type { fill: var(--muted); font-size: 9px; }
  .node .name { fill: var(--fg); font-size: 11px; }
  .node .caption { fill: var(--fg); font-size: 10px; }
  .node .glyph { fill: var(--fg); font-size: 14px; font-weight: 700; }
  .node:not(.runnable) .name { fill: var(--muted); }
  .details { width: 300px; flex-shrink: 0; border-left: 1px solid var(--border); padding: 10px; overflow: auto; box-sizing: border-box; }
  .details .head { display: flex; justify-content: space-between; align-items: start; gap: 6px; margin-bottom: 4px; }
  .details .open { margin: 8px 0; }
  .details h4.adapter { font-size: 12px; margin: 12px 0 0; }
  table { border-collapse: collapse; width: 100%; margin-top: 8px; }
  td { padding: 2px 4px; border-bottom: 1px solid var(--border-soft); vertical-align: top; word-break: break-word; font-size: 11px; }
  td.key { color: var(--muted); width: 40%; }
</style>
