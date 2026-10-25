<script lang="ts">
  import Editor from './Editor.svelte';
  import { app, otherProfile, type CompareRun } from '../lib/store.svelte';
  import type { Profile } from '../lib/engine';
  import type { GroovyResult, Row, XsltResult } from '../lib/types';

  type Result = XsltResult | GroovyResult;

  let { profile, result, compare, language = 'xml' }: {
    profile: Profile;
    result: Result | null;
    compare: CompareRun<Result> | null;
    language?: string;
  } = $props();

  interface Side {
    label: string;
    result: Result | null;
    error: string;
  }

  function label(id: Profile) {
    return app.profiles.find((p) => p.id === id)?.label ?? id;
  }

  function firstError(r: Result | null, error: string) {
    if (error) return error;
    return r?.diagnostics.find((d) => d.severity === 'error')?.message ?? '';
  }

  let a = $derived<Side>({ label: label(profile), result, error: '' });
  let b = $derived<Side | null>(compare ? { label: label(compare.profile), result: compare.result, error: compare.error } : null);

  interface Diff {
    name: string;
    a: Row | undefined;
    b: Row | undefined;
  }

  function diffRows(x: Row[] = [], y: Row[] = []): Diff[] {
    const out: Diff[] = [];
    const names = new Set([...x.map((r) => r.name), ...y.map((r) => r.name)]);
    for (const n of names) {
      const ra = x.find((r) => r.name === n);
      const rb = y.find((r) => r.name === n);
      if (ra?.value !== rb?.value || ra?.type !== rb?.type) out.push({ name: n, a: ra, b: rb });
    }
    return out.sort((p, q) => p.name.localeCompare(q.name));
  }

  let headers = $derived(diffRows(a.result?.output?.headers, b?.result?.output?.headers));
  let properties = $derived(diffRows(a.result?.output?.properties, b?.result?.output?.properties));
  let bodiesDiffer = $derived(a.result?.output?.body !== b?.result?.output?.body);
  let statusDiffers = $derived(!!b && (a.result?.ok ?? false) !== (b.result?.ok ?? false));
  let bothOk = $derived(!!a.result?.ok && !!b?.result?.ok);
  let same = $derived(!!b && !statusDiffers && !bodiesDiffer && headers.length === 0 && properties.length === 0);

  function cell(r: Row | undefined) {
    return r === undefined ? '—' : `${r.value ?? 'null'} (${r.type})`;
  }
</script>

<div class="compare">
  {#if !b && otherProfile(profile)}
    <p class="hint">Running on {label(otherProfile(profile)!)}…</p>
  {:else if !b}
    <p class="hint">No second runtime of this kind is available. In web mode start it and add it with <code>?engines=</code>.</p>
  {:else}
    <div class="summary">
      <div class="sides">
        {#each [a, b] as s, i}
          <div class="side">
            <span class="tag">{i === 0 ? 'A' : 'B'}</span>
            <span class="name" title={s.label}>{s.label}</span>
            {#if s.result || s.error}
              <span class="badge" class:ok={s.result?.ok} class:bad={!s.result?.ok}>{s.result?.ok ? 'OK' : 'error'}</span>
            {/if}
            {#if firstError(s.result, s.error)}<div class="err mono">{firstError(s.result, s.error)}</div>{/if}
          </div>
        {/each}
      </div>
      {#if !bothOk}
        <p class="hint">{a.result?.ok || b.result?.ok ? 'Only one runtime succeeded' : 'Both runtimes failed'}; output is not compared.</p>
      {:else if same}
        <p class="same">Same result on both runtimes: body, headers and properties are identical.</p>
      {:else}
        {#each [['Headers', headers], ['Properties', properties]] as [title, rows]}
          {#if (rows as Diff[]).length}
            <h3>{title} that differ</h3>
            <table>
              <thead><tr><th>Name</th><th>A</th><th>B</th></tr></thead>
              <tbody>
                {#each rows as Diff[] as d}
                  <tr><td class="mono">{d.name}</td><td class="mono">{cell(d.a)}</td><td class="mono">{cell(d.b)}</td></tr>
                {/each}
              </tbody>
            </table>
          {/if}
        {/each}
        {#if a.result?.output && b.result?.output}
          <h3>Body {bodiesDiffer ? '(A left, B right)' : 'is identical'}</h3>
        {/if}
      {/if}
    </div>
    {#if bothOk && a.result?.output && b.result?.output && bodiesDiffer}
      <div class="diff">
        <Editor value={b.result.output.body} original={a.result.output.body} {language} readOnly label="Body difference between runtimes" />
      </div>
    {/if}
  {/if}
</div>

<style>
  .compare { display: flex; flex-direction: column; height: 100%; min-height: 0; font-size: 12px; }
  .summary { padding: 8px; overflow: auto; max-height: 55%; flex-shrink: 0; box-sizing: border-box; }
  .compare > .hint { padding: 12px; }
  .hint { color: var(--muted); margin: 0; }
  .summary .hint { margin-top: 10px; }
  .sides { display: grid; grid-template-columns: 1fr 1fr; gap: 8px; }
  .side { border: 1px solid var(--border-soft); border-radius: 4px; padding: 6px; min-width: 0; }
  .tag { font-weight: 700; color: var(--accent); margin-right: 4px; }
  .name { font-weight: 600; }
  .badge { font-size: 10px; padding: 1px 6px; border-radius: 8px; margin-left: 6px; text-transform: uppercase; background: var(--surface); }
  .badge.ok { background: var(--ok-bg); color: var(--ok); }
  .badge.bad { background: var(--err-bg); color: var(--err); }
  .err { color: var(--err); margin-top: 4px; white-space: pre-wrap; word-break: break-word; }
  .mono { font-family: var(--mono); }
  .same { color: var(--ok); margin: 10px 0 0; }
  h3 { font-size: 11px; text-transform: uppercase; color: var(--muted); margin: 12px 0 4px; }
  table { border-collapse: collapse; width: 100%; }
  th, td { text-align: left; padding: 2px 6px; border-bottom: 1px solid var(--border-soft); vertical-align: top; word-break: break-word; }
  th { color: var(--muted); font-weight: 600; }
  .diff { flex: 1; min-height: 160px; position: relative; border-top: 1px solid var(--border); }
</style>
