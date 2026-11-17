<script lang="ts" generics="R extends XsltResult | GroovyResult">
  import CompareView from './CompareView.svelte';
  import { app } from '../lib/store.svelte';
  import type { Profile } from '../lib/engine';
  import type { GroovyResult, XsltResult } from '../lib/types';
  import { timeOf, type HistoryEntry } from '../lib/history';

  let { history, result, resultAt, profile, language = 'xml' }: {
    history: HistoryEntry<R>[];
    /** The result the tab shows now: side A of a comparison. */
    result: R | null;
    /** The time of the run that gave it. */
    resultAt: number | null;
    profile: Profile;
    language?: string;
  } = $props();

  /** The run compared with the shown one, by its time. */
  let picked = $state<number | null>(null);
  let entry = $derived(history.find((e) => e.at === picked) ?? null);
  let current = $derived(result ? history.find((e) => e.at === resultAt) ?? null : null);
  let previous = $derived(history.find((e) => e !== current && (!current || e.at < current.at)) ?? null);

  function label(id: Profile) {
    return app.profiles.find((p) => p.id === id)?.label ?? id;
  }

  function size(r: R) {
    const body = r.output?.body;
    return body == null ? '' : body.length + ' chars';
  }
</script>

<div class="history">
  {#if history.length === 0}
    <p class="hint">No runs yet. Each run of this tab is kept here for this session (the last 20), to compare with a later one.</p>
  {:else}
    <div class="list">
      <div class="bar">
        <button class="small" disabled={!previous || !result} onclick={() => (picked = previous?.at ?? null)}
          title="Compare the shown result with the run before it">Compare with the previous run</button>
        {#if entry}<button class="small" onclick={() => (picked = null)}>Close comparison</button>{/if}
      </div>
      <table>
        <thead><tr><th>Time</th><th>Ran</th><th>Runtime</th><th>Status</th><th>Body</th><th></th></tr></thead>
        <tbody>
          {#each history as e (e.at)}
            <tr class:picked={e.at === picked} class:current={e === current}>
              <td class="mono">{timeOf(e.at)}</td>
              <td class="mono what" title={e.what}>{e.what}</td>
              <td>{label(e.profile)}</td>
              <td><span class="badge" class:ok={e.result.ok} class:bad={!e.result.ok}>{e.result.ok ? 'OK' : 'error'}</span></td>
              <td>{size(e.result)}</td>
              <td>
                {#if e === current}<span class="muted">shown</span>
                {:else}<button class="small" disabled={!result} onclick={() => (picked = e.at)}>Compare</button>{/if}
              </td>
            </tr>
          {/each}
        </tbody>
      </table>
    </div>
    {#if entry && result}
      <div class="cmp">
        <CompareView {profile} {result} {language} kind="run" labelA="Shown result"
          labelB={`Run at ${timeOf(entry.at)} (${label(entry.profile)})`}
          compare={{ profile: entry.profile, result: entry.result, error: '' }} />
      </div>
    {/if}
  {/if}
</div>

<style>
  .history { display: flex; flex-direction: column; height: 100%; min-height: 0; font-size: 12px; }
  .list { overflow: auto; max-height: 40%; flex-shrink: 0; padding: 8px; box-sizing: border-box; }
  .bar { display: flex; gap: 6px; margin-bottom: 6px; }
  .hint { color: var(--muted); margin: 0; padding: 12px; }
  .muted { color: var(--muted); }
  .mono { font-family: var(--mono); }
  .what { max-width: 220px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
  table { border-collapse: collapse; width: 100%; }
  th, td { text-align: left; padding: 2px 6px; border-bottom: 1px solid var(--border-soft); vertical-align: middle; }
  th { color: var(--muted); font-weight: 600; }
  tr.picked td { background: var(--surface); }
  .badge { font-size: 10px; padding: 1px 6px; border-radius: 8px; text-transform: uppercase; background: var(--surface); }
  .badge.ok { background: var(--ok-bg); color: var(--ok); }
  .badge.bad { background: var(--err-bg); color: var(--err); }
  .cmp { flex: 1; min-height: 0; border-top: 1px solid var(--border); }
</style>
