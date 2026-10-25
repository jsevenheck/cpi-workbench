<script lang="ts">
  import Gutter from './Gutter.svelte';
  import { paneSizes, paneColumns } from '../lib/split.svelte';
  import Editor from './Editor.svelte';
  import { app, xsd, xslt, runXsd, saveSchema, saveSchemaAs, saveInput } from '../lib/store.svelte';

  const panes = paneSizes('xsd', [1, 1]);

  let markers = $derived((xsd.result?.diagnostics ?? []).filter((d) => d.line && d.systemId && d.systemId !== 'input'
    && d.systemId !== 'input.xml' && !d.systemId.endsWith('input.xml')));
  let inputMarkers = $derived((xsd.result?.diagnostics ?? []).filter((d) => d.line && (d.systemId === 'input.xml' || d.systemId === 'input')));
  let dirty = $derived(xsd.schema !== xsd.savedSchema);

  function useXsltOutput() {
    if (xslt.result?.output) xsd.body = xslt.result.output.body;
  }
</script>

<div class="two" style:grid-template-columns={paneColumns(panes.sizes)}>
  <section class="pane">
    <header>
      <h2 title={app.inputPaths.xsd ?? ''}>XML document{#if app.inputPaths.xsd} <span class="from">· {app.inputPaths.xsd.split('/').pop()}</span>{/if}</h2>
      <div class="spacer"></div>
      <button class="small" onclick={useXsltOutput} disabled={!xslt.result?.output} title="Validate the last XSLT output">Use XSLT output</button>
      <button class="small" onclick={() => (xsd.body = xslt.body)}>Use XSLT input</button>
      <button class="small" onclick={saveInput} disabled={!app.project} title={app.project ? 'Save the input body under inputs/' : 'Open a project to save'}>Save…</button>
    </header>
    <div class="body"><Editor bind:value={xsd.body} language="xml" markers={inputMarkers} onrun={runXsd} label="XML document" /></div>
  </section>
  <Gutter {panes} i={0} />
  <section class="pane">
    <header>
      <h2>{xsd.schemaPath ?? 'schema.xsd (unsaved)'}{#if dirty}<span class="dirty">●</span>{/if}</h2>
      <div class="spacer"></div>
      <button class="small" onclick={saveSchema} disabled={!app.project}>Save</button>
      <button class="small" onclick={saveSchemaAs} disabled={!app.project} title="Save under a new name; the open file stays as it is">Save as…</button>
      <button class="primary small" onclick={runXsd} disabled={xsd.running || !xsd.schema.trim()}>Validate</button>
    </header>
    <div class="body schema">
      {#if !xsd.schema.trim()}
        <p class="hint">Open an .xsd from the project or paste a schema here. Includes and imports resolve inside the project folder.</p>
      {/if}
      <Editor bind:value={xsd.schema} language="xml" markers={markers} onrun={runXsd} onsave={saveSchema} label="XML schema" />
    </div>
    <div class="result">
      {#if xsd.result}
        <div class="verdict" class:ok={xsd.result.valid} class:bad={!xsd.result.valid}>
          {xsd.result.ok ? (xsd.result.valid ? '✓ Valid' : '✕ Invalid') : '✕ Schema error'}
          <span class="muted">· {xsd.result.timing.totalMs.toFixed(1)} ms · XML Schema 1.0 (JDK validator)</span>
        </div>
        {#each xsd.result.diagnostics as d}
          <div class="d {d.severity}"><b>{d.severity}</b>{#if d.line} line {d.line}{#if d.column}:{d.column}{/if}{/if}{#if d.systemId} ({d.systemId}){/if}: {d.message}</div>
        {/each}
      {:else}
        <p class="hint">Ctrl+Enter validates.</p>
      {/if}
    </div>
  </section>
</div>

<style>
  .two { display: grid; gap: 0; background: var(--border); height: 100%; min-height: 0; }
  .pane { background: var(--bg); display: flex; flex-direction: column; min-height: 0; min-width: 0; }
  header { display: flex; align-items: center; gap: 8px; padding: 6px 10px; border-bottom: 1px solid var(--border); min-height: 34px; box-sizing: border-box; flex-wrap: wrap; }
  h2 { font-size: 12px; margin: 0; font-weight: 600; }
  .spacer { flex: 1; }
  .body { flex: 1; min-height: 0; position: relative; }
  .schema .hint { position: absolute; z-index: 1; right: 8px; bottom: 8px; max-width: 60%; background: var(--surface); border: 1px solid var(--border); border-radius: 4px; }
  .result { max-height: 40%; overflow: auto; border-top: 1px solid var(--border); padding: 8px; font-size: 12px; }
  .verdict { font-weight: 600; margin-bottom: 6px; }
  .verdict.ok { color: var(--ok); }
  .verdict.bad { color: var(--err); }
  .muted { color: var(--muted); font-weight: 400; }
  .d { font-family: var(--mono); padding: 2px 0; border-bottom: 1px solid var(--border-soft); }
  .d.error b { color: var(--err); }
  .d.warning b { color: var(--warn); }
  .hint { color: var(--muted); font-size: 12px; padding: 8px; margin: 0; }
  .dirty { color: var(--accent); margin-left: 4px; }
  @media (max-width: 900px) { .two { grid-template-columns: 1fr !important; gap: 1px; } .two > :global(.gutter) { display: none; } }
  .from { color: var(--muted); font-weight: 400; font-family: var(--mono); }
</style>
