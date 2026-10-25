<script lang="ts">
  import { dialog, closeDialog } from '../lib/store.svelte';

  let input: HTMLInputElement | undefined = $state();
  let ok: HTMLButtonElement | undefined = $state();
  $effect(() => {
    if (dialog.open) queueMicrotask(() => { if (dialog.input) { input?.focus(); input?.select(); } else ok?.focus(); });
  });
</script>

{#if dialog.open}
  <div class="backdrop" role="presentation" onclick={() => closeDialog(false)}>
    <div class="dlg" role="dialog" aria-modal="true" aria-label={dialog.title} tabindex="-1"
      onclick={(e) => e.stopPropagation()} onkeydown={(e) => { if (e.key === 'Escape') closeDialog(false); }}>
      {#if dialog.input}
        <label>{dialog.title}
          <input class="mono" bind:this={input} bind:value={dialog.value} onkeydown={(e) => e.key === 'Enter' && closeDialog(true)} />
        </label>
      {:else}
        <p class="msg">{dialog.title}</p>
      {/if}
      {#if dialog.checkboxLabel}
        <label class="cb"><input type="checkbox" bind:checked={dialog.checked} /> {dialog.checkboxLabel}</label>
      {/if}
      <div class="btns">
        <button onclick={() => closeDialog(false)}>Cancel</button>
        <button class="primary" bind:this={ok} onclick={() => closeDialog(true)}>OK</button>
      </div>
    </div>
  </div>
{/if}

<style>
  .backdrop { position: fixed; inset: 0; background: rgba(0, 0, 0, 0.35); display: flex; align-items: center; justify-content: center; z-index: 100; }
  .dlg { background: var(--bg); color: var(--fg); border: 1px solid var(--border); border-radius: 8px; padding: 16px; width: min(440px, calc(100vw - 32px)); box-shadow: 0 8px 30px rgba(0,0,0,.25); display: flex; flex-direction: column; gap: 10px; }
  label { display: flex; flex-direction: column; gap: 6px; font-size: 13px; }
  .msg { margin: 0; font-size: 13px; white-space: pre-wrap; }
  .cb { flex-direction: row; align-items: center; font-size: 12px; }
  input:not([type='checkbox']) { padding: 6px; font-size: 13px; }
  .btns { display: flex; justify-content: flex-end; gap: 8px; }
</style>
