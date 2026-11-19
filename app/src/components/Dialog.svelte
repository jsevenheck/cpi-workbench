<script lang="ts">
  import { dialog, closeDialog } from '../lib/store.svelte';

  function modal(node: HTMLDialogElement) {
    const previous = document.activeElement;
    const native = typeof node.showModal === 'function';
    const initial = node.querySelector<HTMLElement>('[data-initial]');
    const keepFocus = (e: FocusEvent) => { if (!node.contains(e.target as Node)) initial?.focus(); };
    // The fallback leaves the rest of the page reachable for assistive technology unless it is marked inert.
    const background: Element[] = [];
    if (native) node.showModal();
    else { // Older WKWebView versions without showModal.
      node.setAttribute('open', '');
      node.classList.add('fallback');
      document.addEventListener('focusin', keepFocus);
      for (const el of node.parentElement?.children ?? []) {
        if (el === node || el.hasAttribute('inert')) continue;
        el.setAttribute('inert', '');
        el.setAttribute('aria-hidden', 'true');
        background.push(el);
      }
    }
    initial?.focus();
    if (initial instanceof HTMLInputElement) initial.select();
    return { destroy() {
      if (native) node.close();
      else document.removeEventListener('focusin', keepFocus);
      for (const el of background) {
        el.removeAttribute('inert');
        el.removeAttribute('aria-hidden');
      }
      // <body>: the opener was already gone when the dialog opened (a menu closes as its item is picked).
      if (previous instanceof HTMLElement && previous !== document.body && previous.isConnected) previous.focus();
      else restoreFocus();
    } };
  }

  /** The opener is gone (e.g. a context menu's item): the active tool tab, else the main region, not <body>. */
  function restoreFocus() {
    const target = document.querySelector<HTMLElement>('.tools [role="tab"].active:enabled') ?? document.querySelector<HTMLElement>('main');
    target?.focus();
  }

  function containTab(e: KeyboardEvent) {
    if (e.key === 'Escape') { e.preventDefault(); closeDialog(false); return; }
    if (e.key !== 'Tab') return;
    const controls = (e.currentTarget as HTMLDialogElement).querySelectorAll<HTMLElement>('input:enabled, button:enabled');
    const first = controls[0], last = controls[controls.length - 1];
    if (e.shiftKey ? document.activeElement === first : document.activeElement === last) {
      e.preventDefault();
      (e.shiftKey ? last : first)?.focus();
    }
  }
</script>

{#if dialog.open}
  <dialog class="dlg" aria-modal="true" aria-label={dialog.title} use:modal onkeydown={containTab}
    oncancel={(e) => { e.preventDefault(); closeDialog(false); }}
    onclick={(e) => { if (e.target === e.currentTarget) closeDialog(false); }}>
    <form onsubmit={(e) => { e.preventDefault(); closeDialog(true); }}>
      {#if dialog.input}
        <label>{dialog.title}
          <input class="mono" bind:value={dialog.value} data-initial />
        </label>
      {:else}
        <p class="msg">{dialog.title}</p>
      {/if}
      {#if dialog.checkboxLabel}
        <label class="cb"><input type="checkbox" bind:checked={dialog.checked} /> {dialog.checkboxLabel}</label>
      {/if}
      <div class="btns">
        <button type="button" data-initial={dialog.input ? undefined : ''} onclick={() => closeDialog(false)}>Cancel</button>
        <button type="submit" class="primary">{dialog.okLabel}</button>
      </div>
    </form>
  </dialog>
{/if}

<style>
  .dlg::backdrop { background: rgba(0, 0, 0, 0.35); }
  .dlg:global(.fallback) { position: fixed; inset: 0; z-index: 100; margin: auto; height: fit-content;
    box-shadow: 0 0 0 100vmax rgba(0, 0, 0, 0.35); }
  /* Block pointer interaction outside the non-native modal. */
  .dlg:global(.fallback)::before { content: ''; position: fixed; inset: 0; z-index: -1; }
  .dlg { background: var(--bg); color: var(--fg); border: 1px solid var(--border); border-radius: 8px; padding: 0; width: min(440px, calc(100vw - 32px)); box-shadow: 0 8px 30px rgba(0,0,0,.25); }
  form { padding: 16px; display: flex; flex-direction: column; gap: 10px; }
  label { display: flex; flex-direction: column; gap: 6px; font-size: 13px; }
  .msg { margin: 0; font-size: 13px; white-space: pre-wrap; }
  .cb { flex-direction: row; align-items: center; font-size: 12px; }
  input:not([type='checkbox']) { padding: 6px; font-size: 13px; }
  .btns { display: flex; justify-content: flex-end; gap: 8px; }
</style>
