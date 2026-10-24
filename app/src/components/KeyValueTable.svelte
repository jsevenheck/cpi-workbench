<script lang="ts">
  import { TYPES, type Row } from '../lib/types';

  interface Props {
    rows: Row[];
    readOnly?: boolean;
    /** Names to highlight, e.g. from the result's change set. */
    added?: string[];
    changed?: string[];
    removed?: string[];
    onchange?: () => void;
    emptyText?: string;
  }

  let { rows = $bindable([]), readOnly = false, added = [], changed = [], removed = [], onchange,
    emptyText = 'None' }: Props = $props();

  function add() {
    rows = [...rows, { name: '', value: '', type: 'String' }];
    onchange?.();
  }

  function remove(i: number) {
    rows = rows.filter((_, j) => j !== i);
    onchange?.();
  }

  function cls(name: string) {
    if (added.includes(name)) return 'added';
    if (changed.includes(name)) return 'changed';
    return '';
  }
</script>

<div class="kv">
  {#if rows.length === 0 && removed.length === 0}
    <p class="empty">{emptyText}</p>
  {:else}
    <table>
      <thead>
        <tr>
          {#if !readOnly}<th class="on" title="Enabled">On</th>{/if}
          <th>Name</th>
          <th>Value</th>
          <th class="type">Type</th>
          {#if !readOnly}<th class="act"></th>{/if}
        </tr>
      </thead>
      <tbody>
        {#each rows as row, i (i)}
          <tr class={readOnly ? cls(row.name) : ''} class:off={row.disabled}>
            {#if readOnly}
              <td class="mono">{row.name}{#if cls(row.name)}<span class="tag">{cls(row.name)}</span>{/if}</td>
              <td class="mono value">{row.value ?? 'null'}</td>
              <td class="type">{row.type}</td>
            {:else}
              <td class="on"><input type="checkbox" checked={!row.disabled} aria-label="Enabled"
                  onchange={(e) => { row.disabled = !(e.target as HTMLInputElement).checked; onchange?.(); }} /></td>
              <td><input class="mono" placeholder="name" bind:value={row.name} oninput={() => onchange?.()} aria-label="Name" /></td>
              <td><input class="mono" placeholder="value" bind:value={row.value} oninput={() => onchange?.()} aria-label="Value" disabled={row.type === 'null'} /></td>
              <td class="type">
                <select bind:value={row.type} onchange={() => onchange?.()} aria-label="Type">
                  {#each TYPES as t}<option value={t}>{t}</option>{/each}
                </select>
              </td>
              <td class="act"><button class="icon" title="Remove" aria-label="Remove row" onclick={() => remove(i)}>✕</button></td>
            {/if}
          </tr>
        {/each}
        {#each removed as name}
          <tr class="removed"><td class="mono">{name}<span class="tag">removed</span></td><td></td><td></td></tr>
        {/each}
      </tbody>
    </table>
  {/if}
  {#if !readOnly}
    <button class="small" onclick={add}>+ Add</button>
  {/if}
</div>

<style>
  .kv { padding: 8px; overflow: auto; height: 100%; box-sizing: border-box; }
  table { width: 100%; border-collapse: collapse; font-size: 12px; }
  th { text-align: left; font-weight: 600; color: var(--muted); padding: 4px; border-bottom: 1px solid var(--border); }
  td { padding: 2px 4px; border-bottom: 1px solid var(--border-soft); vertical-align: middle; }
  td input:not([type='checkbox']), td select { width: 100%; box-sizing: border-box; }
  .type { width: 110px; }
  .on { width: 28px; text-align: center; }
  .act { width: 28px; }
  .value { word-break: break-all; }
  .empty { color: var(--muted); font-size: 12px; margin: 4px 0 8px; }
  tr.off td { opacity: 0.5; }
  tr.added { background: var(--added-bg); }
  tr.changed { background: var(--changed-bg); }
  tr.removed { background: var(--removed-bg); text-decoration: line-through; }
  .tag { margin-left: 6px; font-size: 10px; text-transform: uppercase; color: var(--muted); text-decoration: none; display: inline-block; }
  button.small { margin-top: 6px; }
</style>
