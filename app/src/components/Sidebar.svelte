<script lang="ts">
  import { isDesktop, pickFolder } from '../lib/engine';
  import { app, open, closeProject, openFile, refreshFiles, writeFile, ask, xslt, xsd } from '../lib/store.svelte';

  let dirInput = $state('');
  let filter = $state('');
  let collapsed = $state<Record<string, boolean>>({});

  async function openDialog() {
    try {
      const dir = isDesktop ? await pickFolder() : dirInput.trim();
      if (dir) await open(dir);
    } catch (e) {
      app.error = (e as Error).message;
    }
  }

  async function initProject() {
    if (!app.project) return;
    await open(app.project.root, true).catch((e) => (app.error = e.message));
  }

  async function newFile(kind: 'xsl' | 'xsd') {
    const def = kind === 'xsl' ? 'mappings/new-mapping.xsl' : 'schemas/new-schema.xsd';
    const r = await ask(kind === 'xsl' ? 'New mapping (path)' : 'New schema (path)', def);
    if (!r) return;
    const content = kind === 'xsl'
      ? `<?xml version="1.0" encoding="UTF-8"?>\n<xsl:stylesheet version="3.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform"\n    xmlns:hci="http://sap.com/it/" exclude-result-prefixes="hci">\n  <xsl:param name="exchange"/>\n  <xsl:output method="xml" indent="yes"/>\n\n  <xsl:template match="/">\n    <xsl:copy-of select="."/>\n  </xsl:template>\n</xsl:stylesheet>\n`
      : `<?xml version="1.0" encoding="UTF-8"?>\n<xs:schema xmlns:xs="http://www.w3.org/2001/XMLSchema" elementFormDefault="qualified">\n  <xs:element name="Root" type="xs:string"/>\n</xs:schema>\n`;
    try {
      await writeFile(r.value, content);
      await refreshFiles();
      await openFile(r.value);
    } catch (e) {
      app.error = (e as Error).message;
    }
  }

  const RELEVANT = /\.(xsl|xslt|xsd|xml|json|groovy|txt|csv)$/i;
  let files = $derived((app.project?.files ?? []).filter((f) => !f.dir && RELEVANT.test(f.path)
    && (!filter || f.path.toLowerCase().includes(filter.toLowerCase()))));
  let groups = $derived.by(() => {
    const g = new Map<string, string[]>();
    for (const f of files) {
      const top = f.path.includes('/') ? f.path.split('/')[0] : '.';
      if (!g.has(top)) g.set(top, []);
      g.get(top)!.push(f.path);
    }
    const order = ['mappings', 'schemas', 'tests', 'scripts'];
    return [...g.entries()].sort((a, b) => (order.indexOf(a[0]) + 1 || 99) - (order.indexOf(b[0]) + 1 || 99) || a[0].localeCompare(b[0]));
  });

  function label(path: string, group: string) {
    const rest = group === '.' ? path : path.slice(group.length + 1);
    return rest;
  }

  function isActive(path: string) {
    return path === xslt.mappingPath || path === xsd.schemaPath || path === xslt.casePath;
  }
</script>

<aside class="side">
  <div class="proj">
    {#if app.project}
      <div class="root" title={app.project.root}>{app.project.root.split(/[\\/]/).pop()}</div>
      <div class="row">
        <button class="small" onclick={() => refreshFiles()} title="Reload file list">↻</button>
        <button class="small" onclick={() => newFile('xsl')}>+ Mapping</button>
        <button class="small" onclick={() => newFile('xsd')}>+ Schema</button>
        <button class="small" onclick={initProject} title="Create mappings/, schemas/, tests/ and cpi-workbench.json">Init</button>
        <button class="small" onclick={closeProject} title="Close project">✕</button>
      </div>
      <input class="filter" placeholder="Filter files" bind:value={filter} aria-label="Filter files" />
    {:else}
      <p class="muted">Open a project folder (e.g. an exported iFlow's <code>src/main/resources</code> or your own mapping repo).</p>
      {#if !isDesktop}
        <input class="mono" placeholder="/path/to/project" bind:value={dirInput} onkeydown={(e) => e.key === 'Enter' && openDialog()} aria-label="Project folder" />
      {/if}
      <button class="primary" onclick={openDialog}>{isDesktop ? 'Open folder…' : 'Open'}</button>
      <p class="muted small">Without a project you can still try mappings in the editor.</p>
    {/if}
  </div>
  {#if app.project}
    <nav class="tree" aria-label="Project files">
      {#each groups as [group, paths]}
        <button class="group" onclick={() => (collapsed[group] = !collapsed[group])}>{collapsed[group] ? '▸' : '▾'} {group === '.' ? '(root)' : group}</button>
        {#if !collapsed[group]}
          {#each paths as p}
            <button class="file" class:active={isActive(p)} title={p} onclick={() => openFile(p)}>{label(p, group)}</button>
          {/each}
        {/if}
      {/each}
      {#if files.length === 0}<p class="muted pad">No mapping, schema or XML files.</p>{/if}
    </nav>
  {/if}
</aside>

<style>
  .side { width: 240px; min-width: 200px; border-right: 1px solid var(--border); display: flex; flex-direction: column; background: var(--surface); min-height: 0; }
  .proj { padding: 10px; border-bottom: 1px solid var(--border); display: flex; flex-direction: column; gap: 6px; }
  .root { font-weight: 600; font-size: 13px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  .row { display: flex; gap: 4px; flex-wrap: wrap; }
  .filter, .proj input { width: 100%; box-sizing: border-box; }
  .muted { color: var(--muted); font-size: 12px; margin: 0; }
  .small { font-size: 11px; }
  .pad { padding: 10px; }
  .tree { overflow: auto; flex: 1; padding: 4px 0; }
  .group { display: block; width: 100%; text-align: left; border: none; background: none; color: var(--muted); font-size: 11px; font-weight: 600; text-transform: uppercase; padding: 6px 10px 2px; cursor: pointer; }
  .file { display: block; width: 100%; text-align: left; border: none; background: none; color: var(--fg); font-size: 12px; padding: 3px 10px 3px 22px; cursor: pointer; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; font-family: var(--mono); }
  .file:hover { background: var(--hover); }
  .file.active { background: var(--active); color: var(--accent); }
  @media (max-width: 800px) { .side { width: 100%; border-right: none; border-bottom: 1px solid var(--border); max-height: 35vh; } }
</style>
