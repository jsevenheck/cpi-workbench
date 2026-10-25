<script lang="ts">
  import Gutter from './Gutter.svelte';
  import { paneSizes, paneColumns } from '../lib/split.svelte';
  import { formatJson } from '../lib/json';
  import Editor from './Editor.svelte';
  import KeyValueTable from './KeyValueTable.svelte';
  import CompareView from './CompareView.svelte';
  import { app, groovy, runGroovy, saveScript, saveScriptAs, formatXml, otherProfile, saveInput } from '../lib/store.svelte';
  import { reindentGroovy } from '../lib/indent';
  import type { CaseExtras } from '../lib/store.svelte';
  import type { Diagnostic } from '../lib/types';

  const panes = paneSizes('groovy', [1, 1.3, 1.1]);

  let inputTab = $state<'body' | 'headers' | 'properties'>('body');
  let outputTab = $state<'body' | 'headers' | 'properties' | 'mpl' | 'console' | 'api' | 'compare'>('body');
  let showDiff = $state(false);
  let scriptEditor: Editor | undefined = $state();
  let mplOpen = $state<number | null>(null);

  let timer: ReturnType<typeof setTimeout> | undefined;
  function changed() {
    if (!groovy.live) return;
    clearTimeout(timer);
    timer = setTimeout(runGroovy, 500);
  }

  let result = $derived(groovy.result);
  let errors = $derived(result?.diagnostics.filter((d) => d.severity === 'error') ?? []);
  let others = $derived(result?.diagnostics.filter((d) => d.severity !== 'error') ?? []);
  let markers = $derived<Diagnostic[]>((result?.diagnostics ?? []).filter((d) => d.line));
  let dirty = $derived(groovy.script !== groovy.savedScript);
  let mplCount = $derived(result ? result.mpl.properties.length + result.mpl.customHeaders.length + result.mpl.attachments.length : 0);
  let outLanguage = $derived.by(() => {
    const b = result?.output?.body?.trimStart() ?? '';
    return b.startsWith('<') ? 'xml' : b.startsWith('{') || b.startsWith('[') ? 'json' : 'plaintext';
  });

  /** JSON when the body starts like JSON, XML otherwise (also for an empty body). */
  let inLanguage = $derived(/^\s*[[{]/.test(groovy.body) ? 'json' : 'xml');

  async function prettyInput() {
    try {
      groovy.body = inLanguage === 'json' ? formatJson(groovy.body) : await formatXml(groovy.body);
      changed();
    } catch (e) {
      app.error = `Input is not ${inLanguage === 'json' ? 'valid JSON' : 'well-formed XML'}: ` + (e as Error).message;
    }
  }

  let other = $derived(otherProfile(app.groovyProfile));

  function extrasSummary(x: CaseExtras) {
    const n = (k: number, one: string) => `${k} ${one}${k === 1 ? '' : 's'}`;
    const parts: string[] = [];
    if (x.attachments?.length) parts.push(n(x.attachments.length, 'attachment'));
    if (x.soapHeaders?.length) parts.push(n(x.soapHeaders.length, 'SOAP header'));
    if (x.platform) parts.push('platform overrides');
    return parts.join(', ');
  }

  function toggleCompare() {
    groovy.compareOn = !groovy.compareOn;
    groovy.compare = null;
    if (groovy.compareOn) {
      outputTab = 'compare';
      runGroovy();
    } else if (outputTab === 'compare') {
      outputTab = 'body';
    }
  }

  function jump(d: Diagnostic) {
    if (d.line) scriptEditor?.revealLine(d.line);
  }
</script>

<div class="groovy" style:grid-template-columns={paneColumns(panes.sizes)}>
  <section class="pane input" aria-label="Input message">
    <header>
      <h2 title={app.inputPaths.groovy ?? ''}>Input message{#if app.inputPaths.groovy} <span class="from">· {app.inputPaths.groovy.split('/').pop()}</span>{/if}</h2>
      <div class="tabs" role="tablist">
        <button role="tab" class:active={inputTab === 'body'} onclick={() => (inputTab = 'body')}>Body</button>
        <button role="tab" class:active={inputTab === 'headers'} onclick={() => (inputTab = 'headers')}>Headers <span class="count">{groovy.headers.length}</span></button>
        <button role="tab" class:active={inputTab === 'properties'} onclick={() => (inputTab = 'properties')}>Properties <span class="count">{groovy.properties.length}</span></button>
      </div>
      {#if inputTab === 'body'}
        <label class="bt" title="Java type of the body when the script starts. Adapters often deliver an InputStream.">as
          <select bind:value={groovy.bodyType} onchange={changed} aria-label="Body type">
            <option value="String">String</option>
            <option value="InputStream">InputStream</option>
            <option value="byte[]">byte[]</option>
          </select>
        </label>
        <button class="small" onclick={prettyInput}>Format</button>
        <button class="small" onclick={saveInput} disabled={!app.project} title={app.project ? 'Save the input body under inputs/' : 'Open a project to save'}>Save…</button>
      {/if}
    </header>
    <div class="body">
      {#if inputTab === 'body'}
        <Editor bind:value={groovy.body} language={inLanguage} onchange={changed} onrun={runGroovy} label="Input body" />
      {:else if inputTab === 'headers'}
        <KeyValueTable bind:rows={groovy.headers} onchange={changed} emptyText="No headers. Types matter: getHeader(name, Integer) converts like Camel." />
      {:else}
        <KeyValueTable bind:rows={groovy.properties} onchange={changed} emptyText="No exchange properties." />
      {/if}
    </div>
    {#if groovy.caseExtras}
      <p class="hint extras">From the test case (edit in <code>case.json</code>): {extrasSummary(groovy.caseExtras)}</p>
    {/if}
  </section>

  <Gutter {panes} i={0} />

  <section class="pane script" aria-label="Groovy script">
    <header>
      <h2 title={groovy.scriptPath ?? 'Unsaved script'}>{groovy.scriptPath ?? 'script.groovy (unsaved)'}{#if dirty}<span class="dirty">●</span>{/if}</h2>
      <label class="fn" title="Function the Groovy Script step calls">fn
        <input class="mono" bind:value={groovy.functionName} onchange={changed} aria-label="Function name" />
      </label>
      <div class="spacer"></div>
      <button class="small" onclick={saveScript} disabled={!app.project} title={app.project ? 'Save (Ctrl+S)' : 'Open a project to save'}>Save</button>
      <button class="small" onclick={() => { groovy.script = reindentGroovy(groovy.script); changed(); }}
        title="Re-indent by bracket depth; strings and comments keep their text">Format</button>
      <button class="small" onclick={saveScriptAs} disabled={!app.project} title="Save under a new name; the open file stays as it is">Save as…</button>
    </header>
    <div class="body">
      <Editor bind:this={scriptEditor} bind:value={groovy.script} language="java" markers={markers}
        onchange={changed} onrun={runGroovy} onsave={saveScript} label="Groovy script" />
    </div>
  </section>

  <Gutter {panes} i={1} />

  <section class="pane output" aria-label="Output message">
    <header>
      <h2>Output
        {#if groovy.running}<span class="badge run">running…</span>
        {:else if result}<span class="badge" class:ok={result.ok} class:bad={!result.ok}>{result.ok ? 'OK' : (result.failedPhase ?? 'error') + ' error'}</span>{/if}
      </h2>
      <div class="tabs" role="tablist">
        <button role="tab" class:active={outputTab === 'body'} onclick={() => (outputTab = 'body')}>Body</button>
        <button role="tab" class:active={outputTab === 'headers'} onclick={() => (outputTab = 'headers')}>Headers{#if result?.changes && (result.changes.headers.added.length + result.changes.headers.changed.length) > 0}<span class="count hl">{result.changes.headers.added.length + result.changes.headers.changed.length}</span>{/if}</button>
        <button role="tab" class:active={outputTab === 'properties'} onclick={() => (outputTab = 'properties')}>Properties{#if result?.changes && (result.changes.properties.added.length + result.changes.properties.changed.length) > 0}<span class="count hl">{result.changes.properties.added.length + result.changes.properties.changed.length}</span>{/if}</button>
        <button role="tab" class:active={outputTab === 'mpl'} onclick={() => (outputTab = 'mpl')}>MPL{#if mplCount}<span class="count">{mplCount}</span>{/if}</button>
        <button role="tab" class:active={outputTab === 'console'} onclick={() => (outputTab = 'console')}>Console{#if result && (result.console || result.diagnostics.length)}<span class="count" class:err={errors.length > 0}>{result.diagnostics.length + (result.console ? 1 : 0)}</span>{/if}</button>
        <button role="tab" class:active={outputTab === 'api'} onclick={() => (outputTab = 'api')}>API calls{#if result?.apiCalls.length}<span class="count">{result.apiCalls.length}</span>{/if}</button>
        {#if groovy.compareOn}<button role="tab" class:active={outputTab === 'compare'} onclick={() => (outputTab = 'compare')}>Compare</button>{/if}
      </div>
      <button class="small" class:on={groovy.compareOn} onclick={toggleCompare} disabled={!other && !groovy.compareOn}
        title={other ? 'Also run on ' + other + ' and show the differences' : 'No second Groovy runtime available'}>{groovy.compareOn ? 'Comparing' : 'Compare'}</button>
      {#if outputTab === 'body' && result?.output}
        <label class="check"><input type="checkbox" bind:checked={showDiff} /> Diff</label>
      {/if}
    </header>
    <div class="body">
      {#if !result}
        <p class="hint">Press <kbd>Ctrl</kbd>+<kbd>Enter</kbd> or <b>Run</b>. The first run starts the Groovy runtime (about a second); later runs take milliseconds.</p>
      {:else if outputTab === 'compare'}
        <CompareView profile={app.groovyProfile} {result} compare={groovy.compare} language={outLanguage} />
      {:else if outputTab === 'body'}
        {#if result.output}
          <Editor value={result.output.body} language={outLanguage} readOnly original={showDiff ? groovy.body : null} label="Output body" />
        {:else}
          <div class="errors">
            {#each errors as d}
              <button class="diag error" onclick={() => jump(d)}>
                <b>{result.failedPhase === 'compile' ? 'Compilation error' : 'Exception'}</b>{#if d.line} · line {d.line}{#if d.column}:{d.column}{/if}{/if}
                <div>{d.message}</div>
              </button>
            {/each}
            {#if result.stackTrace}<pre class="stack">{result.stackTrace}</pre>{/if}
            {#if result.console}<div class="diag msg"><b>Console</b><pre>{result.console}</pre></div>{/if}
          </div>
        {/if}
      {:else if outputTab === 'headers'}
        <KeyValueTable rows={result.output?.headers ?? []} readOnly added={result.changes?.headers.added} changed={result.changes?.headers.changed} removed={result.changes?.headers.removed} emptyText="No headers" />
        {#if result.soapHeaders?.length}
          <div class="pad soap">
            <h3>SOAP headers</h3>
            {#each result.soapHeaders as h}<div class="row"><span class="mono">{h.name}</span>{#if h.mustUnderstand} <span class="muted">mustUnderstand</span>{/if}<pre>{h.value}</pre></div>{/each}
          </div>
        {/if}
      {:else if outputTab === 'properties'}
        <KeyValueTable rows={result.output?.properties ?? []} readOnly added={result.changes?.properties.added} changed={result.changes?.properties.changed} removed={result.changes?.properties.removed} emptyText="No properties" />
      {:else if outputTab === 'mpl'}
        <div class="pad">
          {#if !result.mpl.enabled}<p class="hint">MPL disabled: messageLogFactory.getMessageLog() returned null (like log level "None").</p>{/if}
          <h3>Properties</h3>
          {#if result.mpl.properties.length === 0}<p class="hint">None</p>{/if}
          {#each result.mpl.properties as p}<div class="row"><span class="mono">{p.name}</span> = <span class="mono">{p.value}</span> <span class="muted">({p.type})</span></div>{/each}
          <h3>Custom header properties</h3>
          {#if result.mpl.customHeaders.length === 0}<p class="hint">None</p>{/if}
          {#each result.mpl.customHeaders as p}<div class="row"><span class="mono">{p.name}</span> = <span class="mono">{p.value}</span></div>{/each}
          <h3>Attachments</h3>
          {#if result.mpl.attachments.length === 0}<p class="hint">None</p>{/if}
          {#each result.mpl.attachments as a, i}
            <div class="att">
              <button class="link" onclick={() => (mplOpen = mplOpen === i ? null : i)}>{mplOpen === i ? '▾' : '▸'} {a.name}</button>
              <span class="muted">{a.mediaType} · {a.content?.length ?? 0} chars</span>
              {#if mplOpen === i}<pre>{a.content}</pre>{/if}
            </div>
          {/each}
          {#if result.attachments?.length}
            <h3>Message attachments</h3>
            {#each result.attachments as a}<div class="row"><span class="mono">{a.name}</span> <span class="muted">{a.contentType} · {a.size} bytes</span></div>{/each}
          {/if}
        </div>
      {:else if outputTab === 'console'}
        <div class="pad">
          {#each [...errors, ...others] as d}
            <button class="line diag-line {d.severity}" onclick={() => jump(d)}>
              <b>{d.severity}</b>{#if d.line} · line {d.line}{/if}: {d.message}
            </button>
          {/each}
          {#if result.console}<pre class="console">{result.console}</pre>{:else if result.diagnostics.length === 0}<p class="hint">Nothing printed.</p>{/if}
        </div>
      {:else}
        <div class="pad">
          <p class="hint">Calls to SAP APIs. Mocked data comes from the project's <code>platform/</code> folder: <code>credentials.json</code>, <code>value-mappings.json</code>, <code>partner-directory.json</code> and <code>keystore.json</code> (names a PKCS#12/JKS file).</p>
          {#each result.apiCalls as c}
            <div class="row mono">{c.api}.{c.method}({c.args.map((a) => (a === null ? 'null' : JSON.stringify(a))).join(', ')})</div>
          {/each}
        </div>
      {/if}
    </div>
    {#if result}
      <footer>
        Groovy {result.engine.groovy} · Camel {result.engine.camel} · Java {result.engine.java}
        · compile {result.timing.cached ? 'cached' : result.timing.compileMs.toFixed(0) + ' ms'}
        {#if result.runMs !== undefined} · run {result.runMs.toFixed(1)} ms{/if}
        {#if result.output} · body {result.output.bodyType}{/if}
      </footer>
    {/if}
  </section>
</div>

<style>
  .groovy { display: grid; gap: 0; background: var(--border); height: 100%; min-height: 0; }
  .pane { background: var(--bg); display: flex; flex-direction: column; min-width: 0; min-height: 0; }
  header { display: flex; align-items: center; gap: 8px; padding: 6px 10px; border-bottom: 1px solid var(--border); flex-wrap: wrap; min-height: 34px; box-sizing: border-box; }
  h2 { font-size: 12px; font-weight: 600; margin: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; max-width: 100%; }
  h3 { font-size: 11px; text-transform: uppercase; color: var(--muted); margin: 12px 0 4px; }
  .body { flex: 1; min-height: 0; position: relative; }
  .spacer { flex: 1; }
  .dirty { color: var(--accent); margin-left: 4px; }
  .fn, .bt { display: flex; align-items: center; gap: 4px; font-size: 11px; color: var(--muted); }
  .fn input { width: 110px; }
  .badge { font-size: 10px; padding: 1px 6px; border-radius: 8px; margin-left: 6px; text-transform: uppercase; background: var(--surface); }
  .badge.ok { background: var(--ok-bg); color: var(--ok); }
  .badge.bad { background: var(--err-bg); color: var(--err); }
  .badge.run { color: var(--muted); }
  .hint { color: var(--muted); font-size: 12px; padding: 4px 0; margin: 0; }
  .body > .hint { padding: 12px; }
  .extras { padding: 4px 8px; border-top: 1px solid var(--border-soft); }
  .errors, .pad { padding: 8px; overflow: auto; height: 100%; box-sizing: border-box; font-size: 12px; }
  .diag { display: block; width: 100%; text-align: left; border: 1px solid var(--border); border-left: 3px solid var(--err); border-radius: 4px; padding: 6px 8px; margin-bottom: 6px; background: var(--err-bg); color: var(--fg); font: inherit; cursor: pointer; }
  .diag div, .diag pre { white-space: pre-wrap; margin: 2px 0 0; font-family: var(--mono); }
  .diag.msg { border-left-color: var(--accent); background: var(--surface); cursor: default; }
  pre { font-family: var(--mono); font-size: 12px; white-space: pre-wrap; word-break: break-word; margin: 4px 0; }
  .stack { color: var(--muted); }
  .console { background: var(--surface); border: 1px solid var(--border-soft); border-radius: 4px; padding: 6px; }
  .line { display: block; width: 100%; text-align: left; padding: 3px 4px; border: none; background: none; color: var(--fg); font: inherit; border-bottom: 1px solid var(--border-soft); cursor: pointer; }
  .diag-line.error b { color: var(--err); }
  .diag-line.warning b { color: var(--warn); }
  .row { padding: 2px 0; border-bottom: 1px solid var(--border-soft); }
  .muted { color: var(--muted); }
  .att { border-bottom: 1px solid var(--border-soft); padding: 3px 0; }
  .link { background: none; border: none; color: var(--accent); cursor: pointer; padding: 0; font: inherit; }
  footer { font-size: 11px; color: var(--muted); padding: 4px 10px; border-top: 1px solid var(--border); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .soap { height: auto; border-top: 1px solid var(--border); }
  .on { background: var(--active); color: var(--accent); border-color: var(--accent); }
  .check { font-size: 12px; display: flex; align-items: center; gap: 4px; }
  @media (max-width: 1100px) {
    .groovy > :global(.gutter) { display: none; }
    .groovy { grid-template-columns: 1fr !important; gap: 1px; grid-template-rows: minmax(220px, 1fr) minmax(300px, 1.3fr) minmax(260px, 1fr); }
  }
  .from { color: var(--muted); font-weight: 400; font-family: var(--mono); }
</style>
