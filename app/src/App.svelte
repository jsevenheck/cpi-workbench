<script lang="ts">
  import { onMount } from 'svelte';
  import Sidebar from './components/Sidebar.svelte';
  import XsltView from './components/XsltView.svelte';
  import XsdView from './components/XsdView.svelte';
  import XPathView from './components/XPathView.svelte';
  import TestsView from './components/TestsView.svelte';
  import Dialog from './components/Dialog.svelte';
  import { app, xslt, init, runXslt, runXsd, runXpath } from './lib/store.svelte';

  onMount(async () => {
    await init();
    if (app.tool === 'xslt' && !xslt.result) runXslt();
  });

  let profile = $derived(app.profiles.find((p) => p.id === app.profile));

  function run() {
    if (app.tool === 'xslt') runXslt();
    else if (app.tool === 'xsd') runXsd();
    else if (app.tool === 'xpath') runXpath();
  }

  function onProfile() {
    if (app.tool === 'xslt') runXslt();
  }

  const tools = [
    { id: 'xslt', label: 'XSLT Mapping' },
    { id: 'xsd', label: 'XSD Validation' },
    { id: 'xpath', label: 'XPath' },
    { id: 'tests', label: 'Tests' },
  ] as const;
</script>

<div class="app">
  <header class="top">
    <div class="brand"><img src="./icon.svg" alt="" width="20" height="20" /> CPI Workbench</div>
    <div class="tools" role="tablist" aria-label="Tools">
      {#each tools as t}
        <button role="tab" class:active={app.tool === t.id} onclick={() => (app.tool = t.id)}>{t.label}</button>
      {/each}
      <button role="tab" disabled title="Groovy Script v1/v2 simulation is the next phase (see PLAN.md)">Groovy (next)</button>
    </div>
    <div class="spacer"></div>
    {#if app.tool !== 'tests'}
      <label class="profile" title={profile?.note ?? ''}>Runtime
        <select bind:value={app.profile} onchange={onProfile} aria-label="Runtime profile">
          {#each app.profiles as p}
            <option value={p.id} disabled={!p.available}>{p.label}{p.available ? '' : ' (not installed)'}</option>
          {/each}
        </select>
      </label>
    {/if}
    {#if app.tool === 'xslt'}
      <label class="live" title="Re-run on every change"><input type="checkbox" bind:checked={xslt.live} /> Live</label>
    {/if}
    {#if app.tool !== 'tests'}
      <button class="primary" onclick={run} title="Run (Ctrl+Enter)">▶ Run</button>
    {/if}
  </header>

  {#if app.error}
    <div class="banner error" role="alert">
      <span>{app.error}</span>
      <button class="icon" onclick={() => (app.error = '')} aria-label="Dismiss">✕</button>
    </div>
  {/if}

  <div class="main">
    <Sidebar />
    <main class="content">
      {#if app.tool === 'xslt'}<XsltView />
      {:else if app.tool === 'xsd'}<XsdView />
      {:else if app.tool === 'xpath'}<XPathView />
      {:else}<TestsView />{/if}
    </main>
  </div>

  <footer class="status">
    <span>{app.status || 'Ready'}</span>
    <span class="spacer"></span>
    {#if app.engineInfo}
      <span title="Engine">{app.engineInfo.nativeImage ? 'native' : 'JVM'} engine {app.engineInfo.version} · offline</span>
    {:else}
      <span class="err">engine not reachable</span>
    {/if}
  </footer>
</div>

<Dialog />

<style>
  .app { display: flex; flex-direction: column; height: 100vh; }
  .top { display: flex; align-items: center; gap: 10px; padding: 6px 10px; border-bottom: 1px solid var(--border); background: var(--surface); flex-wrap: wrap; }
  .brand { display: flex; align-items: center; gap: 6px; font-weight: 700; font-size: 14px; margin-right: 8px; }
  .tools { display: flex; gap: 2px; flex-wrap: wrap; }
  .tools button { border: none; background: none; padding: 6px 10px; border-radius: 6px; color: var(--muted); font-weight: 600; font-size: 12px; cursor: pointer; }
  .tools button.active { background: var(--active); color: var(--accent); }
  .tools button:disabled { opacity: 0.5; cursor: default; }
  .spacer { flex: 1; }
  .profile { display: flex; align-items: center; gap: 6px; font-size: 12px; color: var(--muted); }
  .live { display: flex; align-items: center; gap: 4px; font-size: 12px; }
  .banner { display: flex; align-items: center; gap: 8px; padding: 6px 10px; font-size: 12px; }
  .banner span { flex: 1; white-space: pre-wrap; }
  .banner.error { background: var(--err-bg); color: var(--err); border-bottom: 1px solid var(--border); }
  .main { flex: 1; display: flex; min-height: 0; }
  .content { flex: 1; min-width: 0; min-height: 0; }
  .status { display: flex; gap: 8px; font-size: 11px; color: var(--muted); padding: 3px 10px; border-top: 1px solid var(--border); background: var(--surface); }
  .err { color: var(--err); }
  @media (max-width: 800px) { .main { flex-direction: column; } .app { height: auto; min-height: 100vh; } .content { height: 140vh; } }
</style>
