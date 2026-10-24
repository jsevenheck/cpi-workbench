<script lang="ts">
  import { app, tests, runAllTests, loadCase, caseRelPath } from '../lib/store.svelte';

  async function openCase(dir: string) {
    const p = caseRelPath(dir);
    if (p) await loadCase(p).catch((e) => (app.error = e.message));
  }
</script>

<div class="tests">
  <div class="bar">
    <button class="primary" onclick={runAllTests} disabled={!app.project || tests.running}>{tests.running ? 'Running…' : 'Run all tests'}</button>
    {#if tests.summary}
      <span class="sum"><b class="ok">{tests.summary.passed} passed</b> · <b class:bad={tests.summary.failed > 0}>{tests.summary.failed} failed</b> · {tests.summary.skipped} skipped</span>
    {/if}
    <span class="muted">Cases live in <code>tests/&lt;name&gt;/case.json</code>. The same cases run headless: <code>cpiwb-xml test tests/</code></span>
  </div>
  {#if !app.project}
    <p class="muted pad">Open a project folder first.</p>
  {:else if tests.results.length === 0}
    <p class="muted pad">No results yet. Save a test case from the XSLT view ("Save as test"), then run all tests.</p>
  {:else}
    <table>
      <thead><tr><th>Status</th><th>Case</th><th>Profile</th><th>Time</th><th>Details</th></tr></thead>
      <tbody>
        {#each tests.results as c}
          <tr class={c.status}>
            <td><span class="st {c.status}">{c.status}</span></td>
            <td><button class="link" onclick={() => openCase(c.dir)}>{c.name}</button></td>
            <td>{c.profile ?? ''}</td>
            <td>{c.ms} ms</td>
            <td class="msgs">{#each c.messages as m}<div>{m}</div>{/each}</td>
          </tr>
        {/each}
      </tbody>
    </table>
  {/if}
</div>

<style>
  .tests { height: 100%; overflow: auto; }
  .bar { display: flex; align-items: center; gap: 12px; padding: 8px 10px; border-bottom: 1px solid var(--border); flex-wrap: wrap; font-size: 12px; }
  .muted { color: var(--muted); }
  .pad { padding: 12px; font-size: 13px; }
  .ok { color: var(--ok); }
  .bad { color: var(--err); }
  table { width: 100%; border-collapse: collapse; font-size: 12px; }
  th { text-align: left; color: var(--muted); padding: 6px 10px; border-bottom: 1px solid var(--border); }
  td { padding: 6px 10px; border-bottom: 1px solid var(--border-soft); vertical-align: top; }
  .st { font-size: 10px; text-transform: uppercase; padding: 1px 6px; border-radius: 8px; background: var(--surface); }
  .st.passed { background: var(--ok-bg); color: var(--ok); }
  .st.failed, .st.error { background: var(--err-bg); color: var(--err); }
  .msgs { font-family: var(--mono); color: var(--muted); }
  .link { background: none; border: none; color: var(--accent); cursor: pointer; padding: 0; font: inherit; text-align: left; }
</style>
