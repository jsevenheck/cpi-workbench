<script lang="ts">
  // Where an address, credential, queue, key alias, file or parameter is used across the imported iFlows, and each iFlow's
  // externalized parameters. Read-only: the engine reads the exports (IflowIndex.java); a hit selects its element in
  // the diagram, a script or mapping opens in its tab.
  import { app, whereUsed, searchIflows, loadIflowIndex, selectIflowNode, iflowStepFor, openIflowStep } from '../lib/store.svelte';
  import { KINDS, groupHits, highlight, type IndexEntry } from '../lib/whereused';

  let groups = $derived(groupHits(whereUsed.hits));
  let names = $derived(new Map(app.iflowModels.map((m) => [m.iflow, m.name ?? m.iflow])));
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

  function parameters() {
    whereUsed.view = 'parameters';
    void loadIflowIndex();
  }

  let shownIndex = $derived(whereUsed.index.filter((i) => !whereUsed.iflow || i.iflow === whereUsed.iflow));
</script>

<div class="wu">
  <header class="bar">
    <div class="tabs" role="tablist" aria-label="Where used view">
      <button role="tab" class:active={whereUsed.view === 'search'} onclick={() => (whereUsed.view = 'search')}>Search</button>
      <button role="tab" class:active={whereUsed.view === 'parameters'} onclick={parameters}>Parameters</button>
    </div>
    {#if whereUsed.view === 'search'}
      <input class="mono q" bind:value={whereUsed.query} oninput={typed} onkeydown={(e) => e.key === 'Enter' && searchIflows()}
        placeholder="A host, credential alias, queue, file, {'{{'}parameter{'}}'} or any value" aria-label="Search the iFlows" />
      <select bind:value={whereUsed.kind} onchange={() => searchIflows()} aria-label="Kind">
        {#each KINDS as [id, label]}<option value={id}>{label}</option>{/each}
      </select>
    {/if}
    {#if app.iflowModels.length > 1}
      <select bind:value={whereUsed.iflow} onchange={() => whereUsed.view === 'search' && searchIflows()} aria-label="iFlow">
        <option value="">All iFlows</option>
        {#each app.iflowModels as m}<option value={m.iflow}>{m.name ?? m.iflow}</option>{/each}
      </select>
    {/if}
  </header>
  {#if whereUsed.error}<p class="err">{whereUsed.error}</p>{/if}

  <div class="body">
    {#if whereUsed.view === 'search'}
      {#if !whereUsed.searched && !whereUsed.kind}
        <p class="muted">Type a value to find every adapter, step and route of the imported iFlows that uses it: a host,
          a credential alias, a queue, a script, an externalized parameter. A key name (<code>address</code>) finds that
          property everywhere. <i>Kind</i> narrows to addresses, credentials, queues, key aliases, files or parameters where the key says what it holds.</p>
      {:else if !groups.length}
        <p class="muted">Nothing in the imported iFlows uses “{whereUsed.searched}”{whereUsed.kind ? ` as ${whereUsed.kind}` : ''}.</p>
      {:else}
        <p class="muted small">{whereUsed.hits.length} {whereUsed.hits.length === 1 ? 'place' : 'places'} in {groups.length} {groups.length === 1 ? 'iFlow' : 'iFlows'}</p>
        {#each groups as g (g.iflow)}
          <section>
            <h3>{names.get(g.iflow) ?? g.iflow}</h3>
            {#each g.elements as el (el.element)}
              <div class="el">
                <button class="link name" onclick={() => show(g.iflow, el.node)} title="Show in the diagram">{el.name}</button>
                <span class="muted small">{el.label}</span>
                <table>
                  <tbody>
                    {#each el.hits as h}
                      <tr>
                        <td class="mono key">{h.key}{#if h.kind}<span class="kind">{h.kind}</span>{/if}</td>
                        <td class="mono">{#each highlight(h.value, whereUsed.searched) as part}{#if part.match}<mark>{part.text}</mark>{:else}{part.text}{/if}{/each}</td>
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
    {:else}
      <p class="muted small">Externalized parameters (<code>{'{{'}name{'}}'}</code>) with their values from each export's
        <code>parameters.prop</code>, type and description from <code>parameters.propdef</code>. <b>Unused</b>: given a value or a definition, but nothing uses it. <b>No value</b>: used, but the export has no value for it.</p>
      {#each shownIndex as i (i.iflow)}
        <section>
          <h3>{i.name ?? i.iflow}</h3>
          {#if i.error}<p class="err">{i.error}</p>
          {:else if !i.parameters.length}<p class="muted small">No externalized parameters.</p>
          {:else}
            <table class="params">
              <thead><tr><th>Parameter</th><th>Value</th><th>Used in</th></tr></thead>
              <tbody>
                {#each i.parameters as p (p.name)}
                  <tr>
                    <td class="mono">{p.name}
                      {#if p.unused}<span class="flag">unused</span>{/if}
                      {#if p.unresolved}<span class="flag warn">no value</span>{/if}
                      {#if p.required}<span class="flag">required</span>{/if}
                      {#if p.type}<span class="muted small">{p.type.replace(/^xsd:/, '')}</span>{/if}
                      {#if p.description}<div class="muted small desc">{p.description}</div>{/if}</td>
                    <td class="mono">{p.value ?? ''}</td>
                    <td>{#each p.uses as u, n}{n ? ', ' : ''}<button class="link" onclick={() => show(i.iflow, u.node)}
                      title="Show in the diagram">{u.name || u.element}</button> <span class="muted mono small">{u.key}</span>{/each}</td>
                  </tr>
                {/each}
              </tbody>
            </table>
          {/if}
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
