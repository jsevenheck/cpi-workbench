<script lang="ts">
  import Gutter from './Gutter.svelte';
  import { paneSizes, paneColumns } from '../lib/split.svelte';
  import { formatJson } from '../lib/json';
  import Editor from './Editor.svelte';
  import { app, jsonpath, groovy, runJsonpath, saveInput } from '../lib/store.svelte';

  const panes = paneSizes('jsonpath', [1.3, 1]);

  // The header names the file the body came from: the Groovy input's file, none for the output.
  function useGroovyOutput() {
    if (!groovy.result?.output) return;
    jsonpath.body = groovy.result.output.body;
    delete app.inputPaths.jsonpath;
  }
  function useGroovyInput() {
    jsonpath.body = groovy.body;
    app.inputPaths.jsonpath = app.inputPaths.groovy;
  }

  function pretty() {
    try {
      jsonpath.body = formatJson(jsonpath.body);
    } catch (e) {
      app.error = 'Input is not valid JSON: ' + (e as Error).message;
    }
  }

  /** Objects and arrays re-indented; the engine sends them on one line. */
  function show(value: string) {
    if (!/^[[{]/.test(value)) return value;
    try {
      return formatJson(value).trimEnd();
    } catch {
      return value;
    }
  }

  function key(e: KeyboardEvent) {
    if (e.key === 'Enter') runJsonpath();
  }
</script>

<div class="jp">
  <div class="bar">
    <label class="expr">JSONPath
      <input class="mono" bind:value={jsonpath.expression} onkeydown={key} placeholder="$.items[*].sku" aria-label="JSONPath expression" />
    </label>
    <button class="primary" onclick={runJsonpath} disabled={jsonpath.running}>Evaluate</button>
  </div>
  <div class="grid" style:grid-template-columns={paneColumns(panes.sizes)}>
    <section class="pane">
      <header><h2 title={app.inputPaths.jsonpath ?? ''}>JSON document{#if app.inputPaths.jsonpath} <span class="from">· {app.inputPaths.jsonpath.split('/').pop()}</span>{/if}</h2><div class="spacer"></div>
        <button class="small" onclick={pretty}>Format</button>
        <button class="small" onclick={useGroovyInput}>Use Groovy input</button>
        <button class="small" onclick={useGroovyOutput} disabled={!groovy.result?.output}>Use Groovy output</button>
        <button class="small" onclick={saveInput} disabled={!app.project} title={app.project ? 'Save the input body under inputs/' : 'Open a project to save'}>Save…</button>
      </header>
      <div class="body"><Editor bind:value={jsonpath.body} language="json" onrun={runJsonpath} label="JSON document" /></div>
    </section>
    <Gutter {panes} i={0} />

    <section class="pane side">
      <header><h2>Result{#if jsonpath.result} <span class="muted">· {jsonpath.result.count} item(s){#if jsonpath.result.definite === false} · JsonPath.read returns a List{/if} · {jsonpath.result.engine}</span>{/if}</h2></header>
      <div class="res">
        {#if jsonpath.result}
          {#each jsonpath.result.diagnostics as d}<div class:err={d.severity === 'error'} class:muted={d.severity !== 'error'}>{d.message}</div>{/each}
          {#each jsonpath.result.items as it, i}
            <div class="item"><span class="k">{i + 1} · {it.type}{it.path ? ' · ' + it.path : ''}</span><pre>{show(it.value)}</pre></div>
          {/each}
        {:else}<p class="muted">Press Enter in the expression field. Evaluated with Jayway JsonPath, as in a script that uses the json-path library; no standard CPI step evaluates JSONPath.</p>{/if}
      </div>
    </section>
  </div>
</div>

<style>
  .jp { display: flex; flex-direction: column; height: 100%; min-height: 0; }
  .bar { display: flex; gap: 12px; align-items: end; padding: 8px 10px; border-bottom: 1px solid var(--border); flex-wrap: wrap; }
  .bar label { display: flex; flex-direction: column; font-size: 11px; color: var(--muted); gap: 2px; }
  .expr { flex: 1; min-width: 240px; }
  .expr input { width: 100%; box-sizing: border-box; font-size: 13px; padding: 5px 6px; }
  .grid { flex: 1; display: grid; gap: 0; background: var(--border); min-height: 0; }
  .pane { background: var(--bg); display: flex; flex-direction: column; min-height: 0; min-width: 0; }
  header { display: flex; align-items: center; gap: 8px; padding: 6px 10px; border-bottom: 1px solid var(--border); min-height: 34px; box-sizing: border-box; }
  h2 { font-size: 12px; margin: 0; font-weight: 600; }
  .spacer { flex: 1; }
  .body { flex: 1; min-height: 0; }
  .res { flex: 1; overflow: auto; padding: 8px; font-size: 12px; }
  .item { border-bottom: 1px solid var(--border-soft); padding: 4px 0; }
  .k { color: var(--muted); font-size: 11px; font-family: var(--mono); }
  pre { margin: 2px 0 0; font-family: var(--mono); white-space: pre-wrap; word-break: break-all; }
  .err { color: var(--err); font-family: var(--mono); }
  .muted { color: var(--muted); font-weight: 400; }
  @media (max-width: 900px) { .grid { grid-template-columns: 1fr !important; gap: 1px; } .grid > :global(.gutter) { display: none; } }
  .from { color: var(--muted); font-weight: 400; font-family: var(--mono); }
</style>
