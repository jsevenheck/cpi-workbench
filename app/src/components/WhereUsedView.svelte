<script lang="ts">
  // Where an address, credential, queue, key alias, file or parameter is used across the imported iFlows. An empty
  // search shows an overview: what the iFlows use, by kind, and each iFlow's externalized parameters. Read-only: the
  // engine reads the exports (IflowIndex.java); a hit selects its element in the diagram, a script or mapping opens in its tab.
  import { onMount } from 'svelte';
  import { app, whereUsed, searchIflows, loadIflowIndex, selectIflowNode, iflowStepFor, openIflowStep } from '../lib/store.svelte';
  import { KINDS, groupHits, highlight, inventory, iflowLabels, matchingParameters, parameterValues, resolveParameters,
    type IndexEntry, type IndexedIflow } from '../lib/whereused';

  let groups = $derived(groupHits(whereUsed.hits));
  let labels = $derived(iflowLabels(app.iflowModels));
  let params = $derived(parameterValues(whereUsed.index));
  let timer: ReturnType<typeof setTimeout> | undefined;

  function typed() {
    clearTimeout(timer);
    timer = setTimeout(() => void searchIflows(), 250);
  }

  function show(iflow: string, node: string) {
    selectIflowNode(iflow, node, true);
  }

  function openFile(h: IndexEntry) {
    const step = iflowStepFor(h.iflow, h.element);
    if (step) openIflowStep(step);
  }

  /** Searches for a value of the overview, as that kind. */
  function pick(kind: string, value: string) {
    clearTimeout(timer);
    whereUsed.query = value;
    whereUsed.kind = kind;
    void searchIflows();
  }

  function overview() {
    clearTimeout(timer);
    whereUsed.query = '';
    whereUsed.kind = '';
    void searchIflows();
  }

  // Read each time the tab opens: a rescan or import since may have changed the iFlows.
  onMount(() => void loadIflowIndex());

  let searching = $derived(!!whereUsed.searched || !!whereUsed.kind);
  let shownIndex = $derived(whereUsed.index.filter((i) => !whereUsed.iflow || i.iflow === whereUsed.iflow));
  let uses = $derived(inventory(shownIndex));
  // A search also finds parameters by name or value (parameters.prop), unless Kind asks for another kind of setting.
  let paramHits = $derived(searching && (!whereUsed.kind || whereUsed.kind === 'parameter')
    ? matchingParameters(shownIndex, whereUsed.searched) : []);
</script>

{#snippet paramTable(i: IndexedIflow, q: string)}
            <table class="params">
              <thead><tr><th>Parameter</th><th>Value</th><th>Used in</th></tr></thead>
              <tbody>
                {#each i.parameters as p (p.name)}
                  <tr>
                    <td class="mono">{@render marked(p.name, q)}
                      {#if p.unused}<span class="flag">unused</span>{/if}
                      {#if p.unresolved}<span class="flag warn">no value</span>{/if}
                      {#if p.required}<span class="flag">required</span>{/if}
                      {#if p.type}<span class="muted small">{p.type.replace(/^xsd:/, '')}</span>{/if}
                      {#if p.description}<div class="muted small desc">{p.description}</div>{/if}</td>
                    <td class="mono">{@render marked(p.value ?? '', q)}</td>
                    <td>{#each p.uses as u, n}{n ? ', ' : ''}<button class="link" onclick={() => show(i.iflow, u.node)}
                      title="Show in the diagram">{u.name || u.element}</button> <span class="muted mono small">{u.key}</span>{/each}</td>
                  </tr>
                {/each}
              </tbody>
            </table>
{/snippet}

{#snippet marked(text: string, q: string)}{#each highlight(text, q) as part}{#if part.match}<mark>{part.text}</mark>{:else}{part.text}{/if}{/each}{/snippet}

<div class="wu">
  <header class="bar">
    {#if searching}<button class="small" onclick={overview} title="Clear the search and show what the iFlows use">← Overview</button>{/if}
    <input class="mono q" bind:value={whereUsed.query} oninput={typed} onkeydown={(e) => e.key === 'Enter' && searchIflows()}
      placeholder="A host, credential alias, queue, file, {'{{'}parameter{'}}'} or any value" aria-label="Search the iFlows" />
    <select bind:value={whereUsed.kind} onchange={() => searchIflows()} aria-label="Kind">
      {#each KINDS as [id, label]}<option value={id}>{label}</option>{/each}
    </select>
    {#if app.iflowModels.length > 1}
      <select bind:value={whereUsed.iflow} onchange={() => searchIflows()} aria-label="iFlow">
        <option value="">All iFlows</option>
        {#each app.iflowModels as m}<option value={m.iflow}>{labels.get(m.iflow)}</option>{/each}
      </select>
    {/if}
  </header>
  {#if whereUsed.error}<p class="err">{whereUsed.error}</p>{/if}

  <div class="body">
    {#if !searching}
      <p class="muted small">What the imported iFlows use. Click a value to see every place that uses it, or type any
        value, a key name (<code>address</code>) or a <code>{'{{'}parameter{'}}'}</code> above. Settings are searched as the
        export writes them: one that holds a parameter is found by the parameter's name, not by its value.</p>
      {#each uses as g (g.kind)}
        <!-- Files are many and each used once or twice: folded, by file name. -->
        <details class="kind-group" open={g.kind !== 'file'}>
          <summary><h3>{g.label} <span class="muted small">{g.values.length}</span></h3></summary>
          <ul class="values">
            {#each g.values as v (v.value)}
              <li><button class="link mono" onclick={() => pick(g.kind, v.value)} title={g.kind === 'file' ? v.value : 'Show where it is used'}>{g.kind === 'file' ? v.value.split('/').pop() : v.value}</button>
                {#if v.resolved.length === 1}<span class="mono resolved">→ {v.resolved[0]}</span>
                {:else if v.resolved.length > 1}<span class="mono resolved" title={v.resolved.join('\n')}>→ {v.resolved.length} values</span>{/if}
                <span class="muted small">{v.count} {v.count === 1 ? 'place' : 'places'}</span></li>
            {/each}
          </ul>
        </details>
      {/each}
      <section>
        <h3>Parameters</h3>
        <p class="muted small">Externalized parameters (<code>{'{{'}name{'}}'}</code>) with their values from each export's
          <code>parameters.prop</code>, type and description from <code>parameters.propdef</code>. <b>Unused</b>: given a value or a definition, but nothing uses it. <b>No value</b>: used, but the export has no value for it.</p>
        {#each shownIndex as i (i.iflow)}
          <h4>{labels.get(i.iflow) ?? i.name ?? i.iflow}</h4>
          {#if i.error}<p class="err">{i.error}</p>
          {:else if !i.parameters.length}<p class="muted small">No externalized parameters.</p>
          {:else}
            {@render paramTable(i, '')}
          {/if}
        {/each}
      </section>
    {:else if !groups.length && !paramHits.length}
      <p class="muted">Nothing in the imported iFlows uses “{whereUsed.searched}”{whereUsed.kind ? ` as ${whereUsed.kind}` : ''}.</p>
    {:else}
      {#if paramHits.length}
        <section class="param-hits">
          <h3>Parameters <span class="muted small">whose name or value contains “{whereUsed.searched}”</span></h3>
          {#each paramHits as i (i.iflow)}
            <h4>{labels.get(i.iflow) ?? i.name ?? i.iflow}</h4>
            {@render paramTable(i, whereUsed.searched)}
          {/each}
        </section>
      {/if}
      {#if groups.length}
        <p class="muted small">{whereUsed.hits.length} {whereUsed.hits.length === 1 ? 'place' : 'places'} in {groups.length} {groups.length === 1 ? 'iFlow' : 'iFlows'}</p>
      {:else}
        <p class="muted small">No setting of the iFlows contains “{whereUsed.searched}”: settings name a parameter, so use its name to find them.</p>
      {/if}
      {#each groups as g (g.iflow)}
        <section>
          <h3>{labels.get(g.iflow) ?? g.iflow}</h3>
          {#each g.elements as el (el.element)}
            <div class="el">
              <button class="link name" onclick={() => show(g.iflow, el.node)} title="Show in the diagram">{el.name}</button>
              <span class="muted small">{el.label}</span>
              <table>
                <tbody>
                  {#each el.hits as h}
                    <tr>
                      <td class="mono key">{h.key}{#if h.kind}<span class="kind">{h.kind}</span>{/if}</td>
                      <td class="mono">{#each highlight(h.value, whereUsed.searched) as part}{#if part.match}<mark>{part.text}</mark>{:else}{part.text}{/if}{/each}{#if resolveParameters(h.value, params.get(h.iflow)) != null}<span class="resolved">→ {resolveParameters(h.value, params.get(h.iflow))}</span>{/if}</td>
                      <td>{#if h.kind === 'file' && iflowStepFor(h.iflow, h.element)}<button class="small" onclick={() => openFile(h)}>Open</button>{/if}</td>
                    </tr>
                  {/each}
                </tbody>
              </table>
            </div>
          {/each}
        </section>
      {/each}
    {/if}
  </div>
</div>

<style>
  .wu { display: flex; flex-direction: column; height: 100%; min-height: 0; }
  .bar { display: flex; gap: 8px; align-items: center; padding: 6px 10px; border-bottom: 1px solid var(--border); flex-wrap: wrap; }
  .q { flex: 1; min-width: 220px; padding: 4px 6px; }
  .body { overflow: auto; padding: 10px 14px; flex: 1; }
  h3 { font-size: 13px; margin: 14px 0 6px; }
  h4 { font-size: 12px; margin: 10px 0 4px; }
  .values { list-style: none; margin: 0; padding: 0 0 0 8px; border-left: 2px solid var(--border-soft); }
  .values li { padding: 1px 0; word-break: break-all; }
  .resolved { margin-left: 6px; color: var(--muted); }
  summary { cursor: pointer; }
  summary h3 { display: inline; }
  table.params { table-layout: fixed; }
  table.params th:nth-child(1) { width: 30%; }
  table.params th:nth-child(2) { width: 30%; }
  .el { margin: 0 0 10px; padding-left: 8px; border-left: 2px solid var(--border-soft); }
  .name { font-weight: 600; }
  table { border-collapse: collapse; width: 100%; font-size: 12px; margin-top: 4px; }
  td, th { padding: 2px 6px; vertical-align: top; text-align: left; border-bottom: 1px solid var(--border-soft); }
  td.key { width: 22%; white-space: nowrap; }
  td.mono { word-break: break-all; }
  .kind, .flag { margin-left: 6px; font-family: inherit; font-size: 10px; padding: 0 4px; border-radius: 3px; border: 1px solid var(--border); color: var(--muted); }
  .desc { font-family: inherit; }
  .flag.warn { color: var(--warn); border-color: var(--warn); }
  mark { background: var(--changed-bg); color: inherit; }
  .err { color: var(--err); margin: 6px 14px; }
  .muted { color: var(--muted); }
  .small { font-size: 12px; }
  .link { background: none; border: none; padding: 0; color: var(--accent); cursor: pointer; font: inherit; }
</style>
