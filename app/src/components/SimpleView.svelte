<script lang="ts">
  import Gutter from './Gutter.svelte';
  import { paneSizes, paneColumns } from '../lib/split.svelte';
  import Editor from './Editor.svelte';
  import KeyValueTable from './KeyValueTable.svelte';
  import { app, simple, groovy, runSimple, saveInput } from '../lib/store.svelte';

  const panes = paneSizes('simple', [1.3, 1]);

  /** Body, headers and properties as the Groovy step receives them. */
  function useGroovyInput() {
    simple.body = groovy.body;
    simple.headers = groovy.headers.map((r) => ({ ...r }));
    simple.properties = groovy.properties.map((r) => ({ ...r }));
    app.inputPaths.simple = app.inputPaths.groovy;
  }

  let language = $derived(/^\s*[[{]/.test(simple.body) ? 'json' : 'xml');

  /** Enter runs a condition; an expression (a Content Modifier body, say) may span lines, so it runs on Ctrl+Enter. */
  function key(e: KeyboardEvent) {
    if (e.key === 'Enter' && (simple.mode === 'condition' || e.ctrlKey || e.metaKey)) {
      e.preventDefault();
      runSimple();
    }
  }

  /** SAP: tenant software 8.x (Cloud Foundry) and 7.x (Neo) run on Camel 3, 6.x and 5.x on Camel 2. */
  let tenants = $derived(simple.result?.engine.startsWith('Camel 2') ? 'tenant software 5.x / 6.x' : 'tenant software 7.x / 8.x');
</script>

<div class="sp">
  <div class="bar">
    <label class="expr">{simple.mode === 'condition' ? 'Condition' : 'Expression'}
      <textarea class="mono" bind:value={simple.expression} onkeydown={key} aria-label="Camel Simple expression"
        rows={simple.mode === 'condition' ? 1 : 3} spellcheck="false"
        placeholder={simple.mode === 'condition' ? "${header.SenderId} = '1'" : '${header.SenderId}-${date:now:yyyyMMdd}'}></textarea>
    </label>
    <label>Used as
      <select bind:value={simple.mode} onchange={runSimple}>
        <option value="condition">Router / Looping Process Call condition (Non-XML)</option>
        <option value="expression">Content Modifier / Write Variables (Expression)</option>
      </select>
    </label>
    <button class="primary" onclick={runSimple} disabled={simple.running}>Evaluate</button>
  </div>
  <div class="grid" style:grid-template-columns={paneColumns(panes.sizes)}>
    <section class="pane">
      <header><h2 title={app.inputPaths.simple ?? ''}>Body{#if app.inputPaths.simple} <span class="from">· {app.inputPaths.simple.split('/').pop()}</span>{/if}</h2><div class="spacer"></div>
        <button class="small" onclick={useGroovyInput}>Use Groovy input</button>
        <button class="small" onclick={saveInput} disabled={!app.project} title={app.project ? 'Save the input body under inputs/' : 'Open a project to save'}>Save…</button>
      </header>
      <div class="body"><Editor bind:value={simple.body} {language} onrun={runSimple} label="Message body" /></div>
    </section>
    <Gutter {panes} i={0} />

    <section class="pane side">
      <header><h2>Headers</h2></header>
      <div class="kv"><KeyValueTable bind:rows={simple.headers} emptyText="No headers. ${'{'}header.name{'}'} reads them." /></div>
      <header><h2>Exchange properties</h2></header>
      <div class="kv"><KeyValueTable bind:rows={simple.properties} emptyText="No properties. ${'{'}property.name{'}'} reads them." /></div>
      <header><h2>Result{#if simple.result} <span class="muted" title="Choose the Camel line of your tenant under Runtime">· {simple.result.engine} ({tenants})</span>{/if}</h2></header>
      <div class="res">
        {#if simple.result}
          {#each simple.result.diagnostics as d}<div class:err={d.severity === 'error'} class:muted={d.severity !== 'error'}>{d.message}</div>{/each}
          {#if simple.result.ok}
            <div class="item"><span class="k">{simple.result.type}</span>
              <pre class:true={simple.mode === 'condition' && simple.result.value === 'true'}>{simple.result.value ?? 'null'}</pre></div>
          {/if}
          {#if simple.result.evaluated}<p class="muted">Evaluated as Camel <code>{simple.result.evaluated}</code></p>{/if}
        {:else}<p class="muted">Press Enter in the condition field (Ctrl+Enter for an expression).</p>{/if}
      </div>
    </section>
  </div>
</div>

<style>
  .sp { display: flex; flex-direction: column; height: 100%; min-height: 0; }
  .bar { display: flex; gap: 12px; align-items: end; padding: 8px 10px; border-bottom: 1px solid var(--border); flex-wrap: wrap; }
  .bar label { display: flex; flex-direction: column; font-size: 11px; color: var(--muted); gap: 2px; }
  .expr { flex: 1; min-width: 240px; }
  .expr textarea { width: 100%; box-sizing: border-box; font: inherit; font-family: var(--mono); font-size: 13px; padding: 5px 6px; resize: vertical;
    border: 1px solid var(--border); border-radius: 4px; background: var(--bg); color: var(--fg); }
  .expr textarea:focus { outline: 2px solid var(--accent); outline-offset: -1px; }
  .grid { flex: 1; display: grid; gap: 0; background: var(--border); min-height: 0; }
  .pane { background: var(--bg); display: flex; flex-direction: column; min-height: 0; min-width: 0; }
  header { display: flex; align-items: center; gap: 8px; padding: 6px 10px; border-bottom: 1px solid var(--border); min-height: 34px; box-sizing: border-box; }
  h2 { font-size: 12px; margin: 0; font-weight: 600; }
  .spacer { flex: 1; }
  .body { flex: 1; min-height: 0; }
  .kv { max-height: 25%; overflow: auto; }
  .res { flex: 1; overflow: auto; padding: 8px; font-size: 12px; }
  .item { padding: 4px 0; }
  .k { color: var(--muted); font-size: 11px; }
  pre { margin: 2px 0 0; font-family: var(--mono); font-size: 13px; white-space: pre-wrap; word-break: break-all; }
  pre.true { color: var(--ok); font-weight: 600; }
  code { font-family: var(--mono); }
  .err { color: var(--err); font-family: var(--mono); white-space: pre-wrap; }
  .muted { color: var(--muted); font-weight: 400; }
  @media (max-width: 900px) { .grid { grid-template-columns: 1fr !important; gap: 1px; } .grid > :global(.gutter) { display: none; } }
  .from { color: var(--muted); font-weight: 400; font-family: var(--mono); }
</style>
