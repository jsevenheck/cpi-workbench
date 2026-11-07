<script lang="ts">
  import { checkForUpdate, type UpdateCheck } from '../lib/version';

  let open = $state(false);
  let version = $state('');
  let checking = $state(false);
  let check = $state<UpdateCheck | null>(null);
  let error = $state('');

  async function toggle() {
    open = !open;
    if (open && !version) {
      const { getVersion } = await import('@tauri-apps/api/app');
      version = await getVersion();
    }
  }

  /** Only on this click: the app makes no network request at start-up. */
  async function runCheck() {
    checking = true;
    error = '';
    check = null;
    try {
      check = await checkForUpdate(version);
    } catch (e) {
      error = (e as Error).message ?? String(e);
    } finally {
      checking = false;
    }
  }

  async function openRelease(url: string) {
    const { invoke } = await import('@tauri-apps/api/core');
    try {
      await invoke('open_release_page', { url });
    } catch (e) {
      error = String(e);
    }
  }
</script>

<div class="settings">
  <button class="icon gear" onclick={toggle} aria-label="Settings" aria-expanded={open} title="Settings">⚙</button>
  {#if open}
    <div class="backdrop" role="presentation" onclick={() => (open = false)}></div>
    <div class="menu" role="dialog" aria-label="Settings" tabindex="-1" onkeydown={(e) => e.key === 'Escape' && (open = false)}>
      <p><b>CPI Workbench</b> {version}</p>
      <button onclick={runCheck} disabled={checking || !version}>{checking ? 'Checking…' : 'Check for updates'}</button>
      <p class="hint">This button asks github.com for the latest release. The app never checks on its own and never contacts a tenant.</p>
      {#if error}<p class="err">{error}</p>{/if}
      {#if check && !check.newer}<p>This is the latest version{check.latest !== version ? ` (latest release: ${check.latest})` : ''}.</p>{/if}
      {#if check?.newer}
        <p>Version {check.latest} is available. Download and install it from its release page.</p>
        <button class="primary" onclick={() => check?.newer && openRelease(check.url)}>Open release page</button>
      {/if}
    </div>
  {/if}
</div>

<style>
  .settings { position: relative; }
  .gear { font-size: 16px; line-height: 1; padding: 4px 6px; }
  .backdrop { position: fixed; inset: 0; z-index: 90; }
  .menu { position: absolute; right: 0; top: calc(100% + 6px); z-index: 91; width: 280px; background: var(--bg); color: var(--fg);
    border: 1px solid var(--border); border-radius: 8px; padding: 12px; box-shadow: 0 8px 30px rgba(0, 0, 0, 0.2);
    display: flex; flex-direction: column; gap: 8px; font-size: 12px; }
  p { margin: 0; }
  .hint { color: var(--muted); font-size: 11px; }
  .err { color: var(--err); white-space: pre-wrap; }
</style>
