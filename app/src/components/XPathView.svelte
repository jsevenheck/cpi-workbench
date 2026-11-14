<script lang="ts">
  import Gutter from './Gutter.svelte';
  import { paneSizes, paneColumns } from '../lib/split.svelte';
  import Editor from './Editor.svelte';
  import KeyValueTable from './KeyValueTable.svelte';
  import { app, xpath, xslt, runXpath, saveInput, closeInput } from '../lib/store.svelte';
  import { rangeOf, scanXml, xpathAt, type CursorXPath, type NodePlace } from '../lib/xmltext';

  const panes = paneSizes('xpath', [1.3, 1]);
  let docEditor: Editor | undefined = $state();

  // ---- the result's nodes in the document
  /** Marks beyond this many would slow the editor down (an expression like //* on a large message). */
  const MAX_MARKS = 1000;
  let placed = $derived(xpath.result?.items.some((it: { at?: NodePlace }) => it.at) ?? false);
  /** The result is for the text in the editor (not edited since the run). */
  let current = $derived(!!xpath.result && xpath.resultBody === xpath.body);
  // Scanned only for a result with places, not on every keystroke.
  let scan = $derived(current && placed ? scanXml(xpath.resultBody) : null);
  let ranges = $derived(scan ? (xpath.result!.items as { at?: NodePlace }[]).map((it) => (it.at ? rangeOf(scan!, it.at) : null)) : []);
  let marked = $derived(ranges.filter((r): r is { start: number; end: number } => r !== null));
  let highlights = $derived(marked.slice(0, MAX_MARKS));

  function show(i: number) {
    const r = ranges[i];
    if (r) docEditor?.selectRange(r.start, r.end);
  }

  // ---- the XPath of the node at the cursor
  let cursorMenu: CursorXPath | null = $state(null);
  let cursorNote = $state('');

  function namespaceMapping(): Record<string, string> {
    const ns: Record<string, string> = {};
    for (const r of xpath.namespaces) if (r.name.trim() && !r.disabled) ns[r.name.trim()] = r.value;
    return ns;
  }

  function atCursor() {
    const offset = docEditor?.cursorOffset();
    cursorNote = '';
    cursorMenu = offset == null ? null : xpathAt(scanXml(xpath.body), offset, namespaceMapping(), xpath.version);
    if (!cursorMenu) cursorNote = 'Put the cursor on an element or an attribute of the document first.';
  }

  async function useXPath(expr: string) {
    const unmapped = cursorMenu?.unmapped ?? [];
    cursorMenu = null;
    xpath.expression = expr;
    historyAt = -1;
    let copied = false;
    try {
      await navigator.clipboard.writeText(expr);
      copied = true;
    } catch {
      // No clipboard (a browser without permission): the expression field has it.
    }
    app.status = (copied ? 'Copied ' : 'Took ') + expr + (unmapped.length
      ? ` · ${unmapped.join(', ')} has no prefix in the Namespace mapping; add one to write it as in the iFlow.` : '');
    runXpath();
  }

  // ---- history: ↑ and ↓ in the expression field
  let historyAt = $state(-1);
  let draft = '';

  // The header names the file the body came from: the XSLT input's file, none for the output.
  function useXsltOutput() {
    if (!xslt.result?.output) return;
    xpath.body = xslt.result.output.body;
    delete app.inputPaths.xpath;
  }
  function useXsltInput() {
    xpath.body = xslt.body;
    app.inputPaths.xpath = app.inputPaths.xslt;
  }

  function key(e: KeyboardEvent) {
    if (e.key === 'Enter') {
      historyAt = -1;
      runXpath();
    } else if (e.key === 'ArrowUp' || e.key === 'ArrowDown') {
      const h = xpath.history;
      if (!h.length) return;
      e.preventDefault();
      if (historyAt === -1) {
        if (e.key === 'ArrowDown') return;
        draft = xpath.expression;
        // The expression in the field was just run, so it is the newest entry: ↑ starts before it.
        historyAt = h[h.length - 1] === draft ? h.length - 1 : h.length;
      }
      historyAt = Math.max(0, Math.min(h.length, historyAt + (e.key === 'ArrowUp' ? -1 : 1)));
      if (historyAt === h.length) {
        xpath.expression = draft;
        historyAt = -1;
      } else {
        xpath.expression = h[historyAt];
      }
    } else if (e.key === 'Escape') {
      cursorMenu = null;
    }
  }
</script>

<svelte:window onclick={(e) => { if (cursorMenu && !(e.target as HTMLElement).closest('.cursor-x')) cursorMenu = null; }}
  onkeydown={(e) => { if (e.key === 'Escape') cursorMenu = null; }} />

<div class="xp">
  <div class="bar">
    <label class="expr">XPath
      <input class="mono" bind:value={xpath.expression} onkeydown={key} oninput={() => (historyAt = -1)} placeholder="//Order/@id" aria-label="XPath expression"
        title={xpath.history.length ? '↑ and ↓: the expressions run in this session' : ''} />
    </label>
    <label>Version
      <select bind:value={xpath.version}>
        <option value="1.0">1.0 (JDK)</option>
        <option value="3.1">3.1 (Saxon, as in Content Modifier and Filter)</option>
      </select>
    </label>
  </div>
  <div class="grid" style:grid-template-columns={paneColumns(panes.sizes)}>
    <section class="pane">
      <header><h2 title={app.inputPaths.xpath ?? ''}>XML document{#if app.inputPaths.xpath} <span class="from">· {app.inputPaths.xpath.split('/').pop()}</span><button class="close-file" onclick={() => closeInput('xpath')} title="Close the input file (it stays on disk)" aria-label="Close input file">×</button>{/if}</h2><div class="spacer"></div>
        <span class="cursor-x">
          <button class="small" onclick={atCursor} aria-haspopup="menu" aria-expanded={!!cursorMenu}
            title="The XPath of the element or attribute at the cursor in the document">XPath at cursor ▾</button>
          {#if cursorMenu}
            <div class="menu" role="menu" aria-label="XPath at cursor">
              <button role="menuitem" onclick={() => useXPath(cursorMenu!.exact)} title="Selects this node only">
                <span class="muted">This node</span><code>{cursorMenu.exact}</code></button>
              {#if cursorMenu.general !== cursorMenu.exact}
                <button role="menuitem" onclick={() => useXPath(cursorMenu!.general)} title="Selects this node and the ones like it">
                  <span class="muted">All like it</span><code>{cursorMenu.general}</code></button>
              {/if}
            </div>
          {/if}
        </span>
        <button class="small" onclick={useXsltInput}>Use XSLT input</button>
        <button class="small" onclick={useXsltOutput} disabled={!xslt.result?.output}>Use XSLT output</button>
        <button class="small" onclick={saveInput} disabled={!app.project} title={app.project ? 'Save the input body under inputs/' : 'Open a project to save'}>Save…</button>
      </header>
      {#if cursorNote}<p class="note">{cursorNote}</p>{/if}
      <div class="body"><Editor bind:this={docEditor} bind:value={xpath.body} language="xml" onrun={runXpath} {highlights} label="XML document" /></div>
    </section>
    <Gutter {panes} i={0} />

    <section class="pane side">
      <header><h2>Namespace mapping</h2></header>
      <div class="ns"><KeyValueTable bind:rows={xpath.namespaces} emptyText="Prefix → namespace URI (like the iFlow's Namespace Mapping)" /></div>
      <header><h2>Result{#if xpath.result} <span class="muted">· {xpath.result.count} item(s) · {xpath.result.engine ?? ''}</span>{/if}</h2></header>
      {#if xpath.result && placed && !current}<p class="note">The document changed since this run: its nodes are not marked. Run again.</p>{/if}
      {#if marked.length > MAX_MARKS}<p class="note">The first {MAX_MARKS} of {marked.length} nodes are marked in the document.</p>{/if}
      <div class="res">
        {#if xpath.result}
          {#each xpath.result.diagnostics as d}<div class="err">{d.message}</div>{/each}
          {#each xpath.result.items as it, i}
            <div class="item">
              {#if ranges[i]}
                <button class="k link" onclick={() => show(i)} title="Show it in the document">{i + 1} · {it.kind}{it.name ? ' ' + it.name : ''}{it.type ? ' (' + it.type + ')' : ''} ↗</button>
              {:else}
                <span class="k">{i + 1} · {it.kind}{it.name ? ' ' + it.name : ''}{it.type ? ' (' + it.type + ')' : ''}</span>
              {/if}
              <pre>{it.value}</pre>
            </div>
          {/each}
        {:else}<p class="muted">Press Enter in the expression field or ▶ Run.</p>{/if}
      </div>
    </section>
  </div>
</div>

<style>
  .xp { display: flex; flex-direction: column; height: 100%; min-height: 0; }
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
  .ns { max-height: 30%; overflow: auto; }
  .res { flex: 1; overflow: auto; padding: 8px; font-size: 12px; }
  .item { border-bottom: 1px solid var(--border-soft); padding: 4px 0; }
  .k { color: var(--muted); font-size: 11px; }
  pre { margin: 2px 0 0; font-family: var(--mono); white-space: pre-wrap; word-break: break-all; }
  .err { color: var(--err); font-family: var(--mono); }
  .muted { color: var(--muted); font-weight: 400; }
  @media (max-width: 900px) { .grid { grid-template-columns: 1fr !important; gap: 1px; } .grid > :global(.gutter) { display: none; } }
  .from { color: var(--muted); font-weight: 400; font-family: var(--mono); }
  .link { border: none; background: none; padding: 0; cursor: pointer; text-align: left; }
  .link:hover { color: var(--accent); text-decoration: underline; }
  .note { margin: 0; padding: 4px 10px; font-size: 11px; color: var(--muted); border-bottom: 1px solid var(--border-soft); }
  .cursor-x { position: relative; }
  .menu { position: absolute; top: 100%; left: 0; z-index: 20; margin-top: 2px; min-width: 260px; max-width: 520px; display: flex; flex-direction: column;
    background: var(--bg); border: 1px solid var(--border); border-radius: 5px; box-shadow: 0 4px 14px rgba(0, 0, 0, 0.15); padding: 3px; }
  .menu button { display: flex; flex-direction: column; align-items: flex-start; gap: 1px; border: none; background: none; padding: 5px 8px; border-radius: 4px; text-align: left; }
  .menu button:hover, .menu button:focus-visible { background: var(--hover); }
  .menu code { background: none; border: none; padding: 0; font-size: 12px; word-break: break-all; }
  .menu .muted { font-size: 10px; }
</style>
