<script lang="ts">
  import Editor from './Editor.svelte';
  import KeyValueTable from './KeyValueTable.svelte';
  import { app, xslt, runXslt, saveMapping, saveCase, formatXml, ask } from '../lib/store.svelte';
  import { languageFor } from '../lib/monaco';
  import type { Diagnostic } from '../lib/types';

  let inputTab = $state<'body' | 'headers' | 'properties'>('body');
  let outputTab = $state<'body' | 'headers' | 'properties' | 'parameters' | 'console'>('body');
  let showDiff = $state(false);
  let mappingEditor: Editor | undefined = $state();

  let timer: ReturnType<typeof setTimeout> | undefined;
  function changed() {
    if (!xslt.live) return;
    clearTimeout(timer);
    timer = setTimeout(runXslt, 450);
  }

  let result = $derived(xslt.result);
  let errors = $derived(result?.diagnostics.filter((d) => d.severity === 'error') ?? []);
  let warnings = $derived(result?.diagnostics.filter((d) => d.severity !== 'error') ?? []);

  /** Markers for the mapping editor: diagnostics that point into the open stylesheet. */
  let markers = $derived<Diagnostic[]>((result?.diagnostics ?? []).filter((d) => {
    if (!d.line) return false;
    if (!d.systemId) return true;
    const p = xslt.mappingPath ?? 'mapping.xsl';
    return d.systemId.endsWith('/' + p) || d.systemId === p;
  }));

  let outLanguage = $derived(result?.outputMethod === 'text' ? 'plaintext'
    : result?.outputMethod === 'json' ? 'json' : 'xml');
  let consoleCount = $derived((result?.messages.length ?? 0) + (result?.diagnostics.length ?? 0) + (result?.cpiCalls.length ?? 0));
  let dirty = $derived(xslt.mapping !== xslt.savedMapping);

  async function prettyInput() {
    try {
      xslt.body = await formatXml(xslt.body);
      changed();
    } catch (e) {
      app.error = 'Input is not well-formed XML: ' + (e as Error).message;
    }
  }

  async function newCase() {
    const suggested = (xslt.mappingPath?.split('/').pop()?.replace(/\.xslt?$/, '') ?? 'mapping') + '-case';
    const r = await ask('Save as test case (name)', suggested, 'Use the current output as expected result');
    if (!r) return;
    try {
      await saveCase(r.value, r.checked);
    } catch (e) {
      app.error = (e as Error).message;
    }
  }

  function jump(d: Diagnostic) {
    if (d.line) mappingEditor?.revealLine(d.line);
  }
</script>

<div class="xslt">
  <section class="pane input" aria-label="Input message">
    <header>
      <h2>Input message</h2>
      <div class="tabs" role="tablist">
        <button role="tab" class:active={inputTab === 'body'} onclick={() => (inputTab = 'body')}>Body</button>
        <button role="tab" class:active={inputTab === 'headers'} onclick={() => (inputTab = 'headers')}>Headers <span class="count">{xslt.headers.length}</span></button>
        <button role="tab" class:active={inputTab === 'properties'} onclick={() => (inputTab = 'properties')}>Properties <span class="count">{xslt.properties.length}</span></button>
      </div>
      {#if inputTab === 'body'}<button class="small" onclick={prettyInput} title="Pretty-print">Format</button>{/if}
    </header>
    <div class="body">
      {#if inputTab === 'body'}
        <Editor bind:value={xslt.body} language="xml" onchange={changed} onrun={runXslt} label="Input body" />
      {:else if inputTab === 'headers'}
        <KeyValueTable bind:rows={xslt.headers} onchange={changed} emptyText="No headers. Headers become xsl:param values." />
      {:else}
        <KeyValueTable bind:rows={xslt.properties} onchange={changed} emptyText="No exchange properties. Properties become xsl:param values." />
      {/if}
    </div>
  </section>

  <section class="pane mapping" aria-label="XSLT mapping">
    <header>
      <h2 title={xslt.mappingPath ?? 'Unsaved mapping'}>
        {xslt.mappingPath ?? 'mapping.xsl (unsaved)'}{#if dirty}<span class="dirty" title="Unsaved changes">●</span>{/if}
      </h2>
      <div class="spacer"></div>
      <button class="small" onclick={saveMapping} disabled={!app.project} title={app.project ? 'Save (Ctrl+S)' : 'Open a project to save'}>Save</button>
      <button class="small" onclick={newCase} disabled={!app.project} title="Save input, mapping reference and result as a regression test">Save as test</button>
    </header>
    <div class="body">
      <Editor bind:this={mappingEditor} bind:value={xslt.mapping} language={languageFor(xslt.mappingPath ?? 'm.xsl')}
        markers={markers} onchange={changed} onrun={runXslt} onsave={saveMapping} label="XSLT mapping" />
    </div>
  </section>

  <section class="pane output" aria-label="Output message">
    <header>
      <h2>Output
        {#if xslt.running}<span class="badge run">running…</span>
        {:else if result}<span class="badge" class:ok={result.ok} class:bad={!result.ok}>{result.ok ? 'OK' : (result.failedPhase ?? 'error') + ' error'}</span>{/if}
      </h2>
      <div class="tabs" role="tablist">
        <button role="tab" class:active={outputTab === 'body'} onclick={() => (outputTab = 'body')}>Body</button>
        <button role="tab" class:active={outputTab === 'headers'} onclick={() => (outputTab = 'headers')}>Headers{#if result?.changes && (result.changes.headers.added.length + result.changes.headers.changed.length) > 0}<span class="count hl">{result.changes.headers.added.length + result.changes.headers.changed.length}</span>{/if}</button>
        <button role="tab" class:active={outputTab === 'properties'} onclick={() => (outputTab = 'properties')}>Properties{#if result?.changes && (result.changes.properties.added.length + result.changes.properties.changed.length) > 0}<span class="count hl">{result.changes.properties.added.length + result.changes.properties.changed.length}</span>{/if}</button>
        <button role="tab" class:active={outputTab === 'parameters'} onclick={() => (outputTab = 'parameters')}>Parameters</button>
        <button role="tab" class:active={outputTab === 'console'} onclick={() => (outputTab = 'console')}>Console{#if consoleCount}<span class="count" class:err={errors.length > 0}>{consoleCount}</span>{/if}</button>
      </div>
      {#if outputTab === 'body' && result?.output}
        <label class="check"><input type="checkbox" bind:checked={showDiff} /> Diff</label>
      {/if}
    </header>
    <div class="body">
      {#if !result}
        <p class="hint">Press <kbd>Ctrl</kbd>+<kbd>Enter</kbd> or <b>Run</b>. With <b>Live</b> on, every change re-runs the mapping.</p>
      {:else if outputTab === 'body'}
        {#if result.output}
          <Editor value={result.output.body} language={outLanguage} readOnly original={showDiff ? xslt.body : null} label="Output body" />
        {:else}
          <div class="errors">
            {#each errors as d}
              <button class="diag error" onclick={() => jump(d)}>
                <b>{d.code ?? 'Error'}</b>{#if d.line} · line {d.line}{#if d.column}:{d.column}{/if}{/if}
                {#if d.systemId && !d.systemId.endsWith(xslt.mappingPath ?? 'mapping.xsl')}<span class="sys"> in {d.systemId}</span>{/if}
                <div>{d.message}</div>
              </button>
            {/each}
            {#each result.messages as m}
              <div class="diag msg"><b>xsl:message{m.terminate ? ' (terminate)' : ''}</b><div>{m.text}</div></div>
            {/each}
          </div>
        {/if}
      {:else if outputTab === 'headers'}
        <KeyValueTable rows={result.output?.headers ?? []} readOnly added={result.changes?.headers.added} changed={result.changes?.headers.changed} removed={result.changes?.headers.removed} emptyText="No headers" />
      {:else if outputTab === 'properties'}
        <KeyValueTable rows={result.output?.properties ?? []} readOnly added={result.changes?.properties.added} changed={result.changes?.properties.changed} removed={result.changes?.properties.removed} emptyText="No properties" />
      {:else if outputTab === 'parameters'}
        <div class="params">
          <p class="hint">How each global <code>xsl:param</code> was bound. In CPI a parameter receives the header or exchange property with the same name.</p>
          {#if result.parameters.length === 0}<p class="hint">The stylesheet declares no global parameters.</p>{/if}
          <table>
            <thead><tr><th>Parameter</th><th>Bound from</th><th>Value</th><th>Type</th></tr></thead>
            <tbody>
              {#each result.parameters as p}
                <tr class:muted={p.source === 'default'}>
                  <td class="mono">{p.name}{#if p.required}<span class="tag">required</span>{/if}</td>
                  <td>{p.source === 'default' ? 'not set → default' : p.source}</td>
                  <td class="mono">{p.value ?? (p.source === 'exchange' ? '(exchange)' : '')}</td>
                  <td>{p.type ?? ''}</td>
                </tr>
              {/each}
            </tbody>
          </table>
        </div>
      {:else}
        <div class="console">
          {#each result.cpiCalls as c}
            <div class="line call"><b>{c.function}</b> {c.name} = <span class="mono">{c.value}</span></div>
          {/each}
          {#each result.messages as m}
            <div class="line msg"><b>xsl:message</b>{#if m.line} (line {m.line}){/if}: {m.text}</div>
          {/each}
          {#each [...errors, ...warnings] as d}
            <button class="line diag-line {d.severity}" onclick={() => jump(d)}>
              <b>{d.severity}</b>{#if d.code} {d.code}{/if}{#if d.line} · line {d.line}{/if}: {d.message}
            </button>
          {/each}
          {#if consoleCount === 0}<p class="hint">Nothing logged.</p>{/if}
        </div>
      {/if}
    </div>
    {#if result}
      <footer>
        {result.engine.product} · compile {result.timing.cached ? 'cached' : result.timing.compileMs.toFixed(0) + ' ms'}
        {#if result.transformMs !== undefined} · transform {result.transformMs.toFixed(1)} ms{/if}
        {#if result.output} · {result.output.bodySize} bytes, {result.output.encoding}{/if}
      </footer>
    {/if}
  </section>
</div>

<style>
  .xslt { display: grid; grid-template-columns: minmax(260px, 1fr) minmax(320px, 1.3fr) minmax(280px, 1.1fr); gap: 1px; background: var(--border); height: 100%; min-height: 0; }
  .pane { background: var(--bg); display: flex; flex-direction: column; min-width: 0; min-height: 0; }
  header { display: flex; align-items: center; gap: 8px; padding: 6px 10px; border-bottom: 1px solid var(--border); flex-wrap: wrap; min-height: 34px; box-sizing: border-box; }
  h2 { font-size: 12px; font-weight: 600; margin: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 100%; }
  .body { flex: 1; min-height: 0; position: relative; }
  .spacer { flex: 1; }
  .dirty { color: var(--accent); margin-left: 4px; }
  .badge { font-size: 10px; padding: 1px 6px; border-radius: 8px; margin-left: 6px; text-transform: uppercase; background: var(--surface); }
  .badge.ok { background: var(--ok-bg); color: var(--ok); }
  .badge.bad { background: var(--err-bg); color: var(--err); }
  .badge.run { color: var(--muted); }
  .hint { color: var(--muted); font-size: 12px; padding: 12px; margin: 0; }
  .errors, .console, .params { padding: 8px; overflow: auto; height: 100%; box-sizing: border-box; font-size: 12px; }
  .diag { display: block; width: 100%; text-align: left; border: 1px solid var(--border); border-left: 3px solid var(--err); border-radius: 4px; padding: 6px 8px; margin-bottom: 6px; background: var(--err-bg); color: var(--fg); font: inherit; cursor: pointer; }
  .diag div { white-space: pre-wrap; margin-top: 2px; font-family: var(--mono); }
  .diag.msg { border-left-color: var(--accent); background: var(--surface); cursor: default; }
  .sys { color: var(--muted); }
  .line { display: block; width: 100%; text-align: left; padding: 3px 4px; border: none; background: none; color: var(--fg); font: inherit; border-bottom: 1px solid var(--border-soft); }
  .diag-line { cursor: pointer; }
  .diag-line.error b { color: var(--err); }
  .diag-line.warning b { color: var(--warn); }
  .call b { color: var(--ok); }
  .msg b { color: var(--accent); }
  table { width: 100%; border-collapse: collapse; }
  th { text-align: left; color: var(--muted); font-weight: 600; padding: 4px; border-bottom: 1px solid var(--border); }
  td { padding: 4px; border-bottom: 1px solid var(--border-soft); }
  tr.muted td { color: var(--muted); }
  .tag { margin-left: 6px; font-size: 10px; text-transform: uppercase; color: var(--warn); }
  footer { font-size: 11px; color: var(--muted); padding: 4px 10px; border-top: 1px solid var(--border); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .check { font-size: 12px; display: flex; align-items: center; gap: 4px; }
  @media (max-width: 1100px) {
    .xslt { grid-template-columns: 1fr; grid-template-rows: minmax(220px, 1fr) minmax(300px, 1.3fr) minmax(260px, 1fr); }
  }
</style>
