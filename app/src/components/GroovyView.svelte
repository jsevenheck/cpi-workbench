<script lang="ts">
  import Gutter from './Gutter.svelte';
  import { paneSizes, paneColumns } from '../lib/split.svelte';
  import { formatJson } from '../lib/json';
  import Editor from './Editor.svelte';
  import KeyValueTable from './KeyValueTable.svelte';
  import CompareView from './CompareView.svelte';
  import QueueEditor from './QueueEditor.svelte';
  import { app, groovy, runGroovy, autoRunGroovy, saveScript, saveScriptAs, formatXml, otherProfile, saveInput, closeInput, closeEditorFile, selectedMapping } from '../lib/store.svelte';
  import { reindentGroovy } from '../lib/indent';
  import { formatQueue } from '../lib/queue';
  import { scriptFunctions, kindLabel, type ParamKind } from '../lib/functions';
  import type { CaseExtras } from '../lib/store.svelte';
  import type { Diagnostic } from '../lib/types';

  const panes = paneSizes('groovy', [1, 1.3, 1.1]);

  let inputTab = $state<'body' | 'args' | 'headers' | 'properties'>('body');
  let outputTab = $state<'body' | 'headers' | 'properties' | 'mpl' | 'console' | 'api' | 'compare'>('body');
  let showDiff = $state(false);
  let scriptEditor: Editor | undefined = $state();
  let mplOpen = $state<number | null>(null);

  let timer: ReturnType<typeof setTimeout> | undefined;
  function changed() {
    if (!groovy.live) return;
    clearTimeout(timer);
    // A script that was opened and never started stays unrun while it is edited (autoRunGroovy).
    timer = setTimeout(autoRunGroovy, 500);
  }

  let result = $derived(groovy.result);
  /** The function is a Message Mapping function: arguments instead of a body (known from the text or a run). */
  let mapping = $derived(selectedMapping());
  const KIND: Record<string, ParamKind> = { 'String': 'value', 'String[]': 'queue', 'Output': 'output', 'MappingContext': 'context' };
  /** All parameters of the mapping function, with names where the text has them. */
  let params = $derived(mapping?.parameters.map((t, i) => ({ type: t, kind: KIND[t], name: mapping!.names[i] ?? '' })) ?? []);
  /** The parameters that take an argument (String, String[]), in order: groovy.args holds one text each. */
  let valueParams = $derived(params.filter((p) => p.kind === 'value' || p.kind === 'queue'));
  let queueFunction = $derived(valueParams.some((p) => p.kind === 'queue'));

  /** The functions the script declares (from its text), with the engine's mapping functions it did not see. */
  let declared = $derived(scriptFunctions(groovy.script));
  let stepOptions = $derived(declared.filter((f) => f.kind === 'step').map((f) => ({ name: f.name, label: `${f.name}(${f.params[0].name})` })));
  let mappingOptions = $derived.by(() => {
    const fromText = declared.filter((f) => f.kind === 'mapping' && groovy.stepFunction !== f.name);
    const names = new Set(fromText.map((f) => f.name));
    const label = (name: string, kinds: ParamKind[], names: string[]) => {
      const args = kinds.map((k, i) => (k === 'value' || k === 'queue' ? names[i] || (k === 'queue' ? 'String[]' : 'String') : null)).filter((a) => a !== null);
      return `${name}(${args.join(', ')})`;
    };
    return [
      ...fromText.map((f) => ({ name: f.name, label: label(f.name, f.params.map((p) => p.kind!), f.params.map((p) => p.name)) })),
      ...groovy.functions.map((sig) => ({ name: sig.slice(0, sig.indexOf('(')), types: sig.slice(sig.indexOf('(') + 1, -1).split(', ').filter((t) => t) }))
        .filter((f) => !names.has(f.name))
        .map((f) => ({ name: f.name, label: label(f.name, f.types.map((t) => KIND[t]), []) })),
    ];
  });
  const OTHER = '\u0000other';
  /** "Other name…" chosen, or a function the lists do not have (e.g. from an iFlow step). */
  let otherChosen = $state(false);
  let listed = $derived([...stepOptions, ...mappingOptions].some((o) => o.name === groovy.functionName));
  let selectValue = $derived(otherChosen || !listed ? OTHER : groovy.functionName);
  let hasProcessData = $derived(stepOptions.some((o) => o.name === 'processData'));

  function useFunction(name: string) {
    otherChosen = false;
    if (groovy.functionName === name) return;
    groovy.functionName = name;
    groovy.args = []; // arguments belong to one function
    changed(); // runs only code the user has started (autoRunGroovy)
  }
  $effect(() => {
    void groovy.scriptPath; // another script: back to the list
    otherChosen = false;
  });
  function onFunctionSelect(e: Event) {
    const v = (e.currentTarget as HTMLSelectElement).value;
    if (v === OTHER) otherChosen = true;
    else useFunction(v);
  }
  $effect(() => {
    if (mapping && inputTab === 'body') inputTab = 'args';
    else if (!mapping && inputTab === 'args') inputTab = 'body';
  });
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
      autoRunGroovy();
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
      <h2 title={app.inputPaths.groovy ?? ''}>{mapping ? 'Function arguments' : 'Input message'}{#if app.inputPaths.groovy} <span class="from">· {app.inputPaths.groovy.split('/').pop()}</span><button class="close-file" onclick={() => closeInput('groovy')} title="Close the input file (it stays on disk)" aria-label="Close input file">×</button>{/if}</h2>
      <div class="tabs" role="tablist">
        {#if mapping}
          <button role="tab" class:active={inputTab === 'args'} onclick={() => (inputTab = 'args')}>Arguments <span class="count">{valueParams.length}</span></button>
        {:else}
          <button role="tab" class:active={inputTab === 'body'} onclick={() => (inputTab = 'body')}>Body</button>
        {/if}
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
      {#if inputTab === 'args' && mapping}
        <div class="pad args">
          <p class="hint" title="A Message Mapping function runs on its own here, without the mapping around it.">
            {queueFunction ? 'Called once per context, like a function with “All values of a context”.'
              : 'Called once with these values (in a mapping: once per value).'} The message body is not used.</p>
          {#each valueParams as p, i}
            <div class="arg">
              <span class="arg-head"><b class="mono">{p.name || `Argument ${i + 1}`}</b> <span class="muted">{kindLabel(p.kind)} · {p.type}</span></span>
              {#if p.kind === 'value'}
                <span class="row">
                  <input class="mono" bind:value={groovy.args[i]} oninput={changed} aria-label="Argument {i + 1}"
                    placeholder={groovy.args[i] === null ? 'null' : ''} />
                  <button class="small" class:on={groovy.args[i] === null} aria-pressed={groovy.args[i] === null}
                    title="Pass null instead of a string" onclick={() => { groovy.args[i] = groovy.args[i] === null ? '' : null; changed(); }}>null</button>
                </span>
              {:else}
                <QueueEditor bind:value={groovy.args[i]} label="Argument {i + 1}" onchange={changed} />
              {/if}
            </div>
          {/each}
          {#each params.filter((p) => p.kind === 'output' || p.kind === 'context') as p}
            <div class="arg given"><span class="arg-head"><b class="mono">{p.name || p.type}</b> <span class="muted">{p.type} · from the simulator</span></span>
              <span class="muted">{p.kind === 'output' ? 'Collects the result queue, shown under Result.' : 'Reads and sets the Headers and Properties tabs.'}</span></div>
          {/each}
          {#if hasProcessData}<p class="hint">To run the script with the message body, choose <button class="link"
            onclick={() => useFunction('processData')}>processData</button>.</p>{/if}
        </div>
      {:else if inputTab === 'body'}
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
      <h2 title={groovy.casePath ? 'From test case ' + groovy.casePath : groovy.scriptPath ?? 'Unsaved script'}>{groovy.scriptPath ?? (groovy.script ? 'script.groovy (unsaved)' : 'No script open')}{#if dirty}<span class="dirty">●</span>{/if}</h2>
      {#if groovy.scriptPath || groovy.casePath || groovy.script}<button class="close-file" onclick={() => closeEditorFile('groovy')}
        title="Close this file (it stays on disk)" aria-label="Close file">×</button>{/if}
      <label class="fn" title="The function to run: a script step gets the message, a Message Mapping function gets field values">Function
        <select value={selectValue} onchange={onFunctionSelect} aria-label="Function">
          {#if stepOptions.length}
            <optgroup label="Script step (gets the message)">
              {#each stepOptions as o}<option value={o.name}>{o.label}</option>{/each}
            </optgroup>
          {/if}
          {#if mappingOptions.length}
            <optgroup label="Message Mapping function (gets field values)">
              {#each mappingOptions as o}<option value={o.name}>{o.label}</option>{/each}
            </optgroup>
          {/if}
          <option value={OTHER}>Other name…</option>
        </select>
      </label>
      {#if selectValue === OTHER}
        <input class="mono fn-name" bind:value={groovy.functionName} onchange={changed} aria-label="Function name" placeholder="processData" />
      {/if}
      <div class="spacer"></div>
      <button class="small" onclick={saveScript} disabled={!app.project} title={app.project ? 'Save (Ctrl+S)' : 'Open a project to save'}>Save</button>
      <button class="small" onclick={() => { groovy.script = reindentGroovy(groovy.script); changed(); }}
        title="Re-indent by bracket depth; strings and comments keep their text">Format</button>
      <button class="small" onclick={saveScriptAs} disabled={!app.project} title="Save under a new name; the open file stays as it is">Save as…</button>
    </header>
    <div class="body">
      {#if !groovy.script}<p class="empty-hint">Open a script from the project, or type or paste one here.</p>{/if}
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
        <button role="tab" class:active={outputTab === 'body'} onclick={() => (outputTab = 'body')}>{result?.mapping ? 'Result' : 'Body'}</button>
        <button role="tab" class:active={outputTab === 'headers'} onclick={() => (outputTab = 'headers')}>Headers{#if result?.changes && (result.changes.headers.added.length + result.changes.headers.changed.length) > 0}<span class="count hl">{result.changes.headers.added.length + result.changes.headers.changed.length}</span>{/if}</button>
        <button role="tab" class:active={outputTab === 'properties'} onclick={() => (outputTab = 'properties')}>Properties{#if result?.changes && (result.changes.properties.added.length + result.changes.properties.changed.length) > 0}<span class="count hl">{result.changes.properties.added.length + result.changes.properties.changed.length}</span>{/if}</button>
        <button role="tab" class:active={outputTab === 'mpl'} onclick={() => (outputTab = 'mpl')}>MPL{#if mplCount}<span class="count">{mplCount}</span>{/if}</button>
        <button role="tab" class:active={outputTab === 'console'} onclick={() => (outputTab = 'console')}>Console{#if result && (result.console || result.diagnostics.length)}<span class="count" class:err={errors.length > 0}>{result.diagnostics.length + (result.console ? 1 : 0)}</span>{/if}</button>
        <button role="tab" class:active={outputTab === 'api'} onclick={() => (outputTab = 'api')}>API calls{#if result?.apiCalls.length}<span class="count">{result.apiCalls.length}</span>{/if}</button>
        {#if groovy.compareOn}<button role="tab" class:active={outputTab === 'compare'} onclick={() => (outputTab = 'compare')}>Compare</button>{/if}
      </div>
      <button class="small" class:on={groovy.compareOn} onclick={toggleCompare} disabled={!other && !groovy.compareOn}
        title={other ? 'Also run on ' + other + ' and show the differences' : 'No second Groovy runtime available'}>{groovy.compareOn ? 'Comparing' : 'Compare'}</button>
      {#if outputTab === 'body' && result?.output && !result.mapping}
        <label class="check"><input type="checkbox" bind:checked={showDiff} /> Diff</label>
      {/if}
    </header>
    <div class="body">
      {#if !result}
        <p class="hint">Press <kbd>Ctrl</kbd>+<kbd>Enter</kbd> or <b>Run</b>. The first run starts the Groovy runtime (about a second); later runs take milliseconds.</p>
      {:else if outputTab === 'compare'}
        <CompareView profile={app.groovyProfile} {result} compare={groovy.compare} language={outLanguage} />
      {:else if outputTab === 'body'}
        {#if result.output && result.mapping}
          <div class="pad">
            {#if 'returned' in result.mapping}
              <h3>Returned</h3>
              {#if result.mapping.returned === null}<p class="hint">null</p>{:else}<pre class="console">{result.mapping.returned}</pre>{/if}
            {/if}
            {#if result.mapping.queue}
              <h3>Output queue · {result.mapping.queue.length} context{result.mapping.queue.length === 1 ? '' : 's'}</h3>
              <pre class="console">{formatQueue(result.mapping.queue)}</pre>
              <p class="hint">One context per line; the same form works as a String[] argument.</p>
            {/if}
          </div>
        {:else if result.output}
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
  .fn select { max-width: 220px; font-family: var(--mono); font-size: 11px; }
  .fn-name { width: 130px; font-size: 11px; }
  .link { border: none; background: none; padding: 0; font: inherit; color: var(--accent); text-decoration: underline; cursor: pointer; }
  .args { display: flex; flex-direction: column; gap: 8px; }
  .arg { display: flex; flex-direction: column; gap: 3px; font-size: 11px; color: var(--muted); }
  .arg-head b { color: var(--fg); font-weight: 600; }
  .arg.given { opacity: 0.8; }
  .arg input { font-size: 12px; }
  .arg .row { display: flex; gap: 4px; }
  .arg .row input { flex: 1; min-width: 0; }
  .arg .on { border-color: var(--accent); color: var(--accent); }
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
