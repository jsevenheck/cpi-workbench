<script lang="ts">
  // How well a step's behaviour is backed, from docs/fidelity.md (bundled when the app is built): the weakest basis of
  // its rows, and all rows on a click. Offline: the page is part of the app, nothing is fetched.
  import md from '../../../docs/fidelity.md?raw';
  import { parseFidelity, plainText, rowsFor, weakest, type StepId } from '../lib/fidelity';

  interface Props { step: StepId; label: string }
  let { step, label }: Props = $props();

  const ALL = parseFidelity(md);
  const TEXT: Record<string, string> = {
    Tenant: 'seen on a tenant',
    Documented: "follows SAP's documentation or Camel's / Saxon's behaviour; not seen on a tenant",
    Assumption: 'partly an assumption: neither documented nor observed',
    'Not supported': 'refused by the simulator or a known gap',
  };

  let rows = $derived(rowsFor(ALL, step));
  let basis = $derived(weakest(rows));
  let open = $state(false);
</script>

{#if rows.length}
  <!-- No basis: every row of the step is "Not supported", which says what the simulator lacks, not how it is backed. -->
  <button class="small fid {basis === 'Tenant' ? 'tenant' : basis === 'Documented' ? 'doc' : basis ? 'assume' : ''}" onclick={() => (open = true)}
    title="Fidelity of {label}: {TEXT[basis ?? 'Not supported']}. Click for details." aria-label="Fidelity of {label}: {basis ?? 'Not supported'}">
    Fidelity: {basis === 'Tenant' ? 'tenant-verified' : basis === 'Documented' ? 'documented' : basis ? 'assumptions' : 'gaps only'}
  </button>
{/if}

{#if open}
  <div class="backdrop" role="presentation" onclick={() => (open = false)}>
    <div class="dlg" role="dialog" aria-modal="true" aria-label="Fidelity of {label}" tabindex="-1"
      onclick={(e) => e.stopPropagation()} onkeydown={(e) => { if (e.key === 'Escape') open = false; }}>
      <h2>Fidelity: {label}</h2>
      <p class="hint">How the simulator's behaviour compares with CPI, from the fidelity page. F… and Q… are findings and
        questions in the project's plan.</p>
      <ul>
        {#each rows as r}
          <li>
            <div class="head"><span class="basis b-{r.basis.replace(' ', '-').toLowerCase()}">{r.basis}</span> <strong>{plainText(r.area)}</strong></div>
            <p>{plainText(r.status)}</p>
          </li>
        {/each}
      </ul>
      <div class="btns"><button class="primary" onclick={() => (open = false)}>Close</button></div>
    </div>
  </div>
{/if}

<style>
  .fid { white-space: nowrap; }
  .fid.tenant { color: var(--ok); border-color: var(--ok); }
  .fid.doc { color: var(--accent); border-color: var(--accent); }
  .fid.assume { color: var(--warn); border-color: var(--warn); }
  .backdrop { position: fixed; inset: 0; background: rgba(0, 0, 0, 0.35); display: flex; align-items: center; justify-content: center; z-index: 100; }
  .dlg { background: var(--bg); color: var(--fg); border: 1px solid var(--border); border-radius: 8px; padding: 16px;
    width: min(720px, calc(100vw - 32px)); max-height: calc(100vh - 64px); overflow: auto; box-shadow: 0 8px 30px rgba(0,0,0,.25);
    display: flex; flex-direction: column; gap: 10px; }
  h2 { margin: 0; font-size: 15px; }
  .hint { margin: 0; font-size: 12px; color: var(--muted); }
  ul { list-style: none; margin: 0; padding: 0; display: flex; flex-direction: column; gap: 10px; }
  li p { margin: 4px 0 0; font-size: 12px; line-height: 1.45; }
  .head { font-size: 13px; }
  .basis { font-size: 11px; padding: 1px 6px; border-radius: 4px; border: 1px solid currentColor; }
  .b-tenant { color: var(--ok); }
  .b-documented { color: var(--accent); }
  .b-assumption { color: var(--warn); }
  .b-not-supported { color: var(--err); }
  .btns { display: flex; justify-content: flex-end; }
</style>
