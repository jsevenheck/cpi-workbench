<script lang="ts">
  import Editor from './Editor.svelte';
  import { languageFor } from '../lib/monaco';
  import { fileView, saveFileView, closeFileView, useAsInput, confirm, app } from '../lib/store.svelte';

  let dirty = $derived(fileView.content !== fileView.saved);

  async function save() {
    try {
      await saveFileView();
    } catch (e) {
      app.error = (e as Error).message;
    }
  }

  async function close() {
    if (dirty && !(await confirm(`Discard the changes to ${fileView.path}?`))) return;
    closeFileView();
  }
</script>

<section class="pane" aria-label="File">
  <header>
    <h2 title={fileView.path ?? ''}>{fileView.path}{#if dirty}<span class="dirty" title="Unsaved changes">●</span>{/if}</h2>
    <div class="spacer"></div>
    <button class="small" onclick={() => fileView.path && useAsInput(fileView.path)}
      title={`Load this file as the input body of the ${app.stepTool.toUpperCase()} step`}>Use as input</button>
    <button class="small" onclick={save} title="Save (Ctrl+S)">Save</button>
    <button class="small" onclick={close}>Close</button>
  </header>
  <div class="body">
    {#key fileView.path}
      <Editor bind:value={fileView.content} language={languageFor(fileView.path ?? '')} onsave={save} label="File" />
    {/key}
  </div>
</section>

<style>
  .pane { background: var(--bg); display: flex; flex-direction: column; height: 100%; min-height: 0; }
  header { display: flex; align-items: center; gap: 8px; padding: 6px 10px; border-bottom: 1px solid var(--border); min-height: 34px; box-sizing: border-box; }
  h2 { font-size: 12px; font-weight: 600; margin: 0; white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .spacer { flex: 1; }
  .dirty { color: var(--accent); margin-left: 4px; }
  .body { flex: 1; min-height: 0; position: relative; }
</style>
