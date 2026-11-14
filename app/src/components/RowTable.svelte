<script lang="ts" generics="R extends Record<string, any>">
  /**
   * Rows of named fields, each field with its caption above it, so that a row with many fields wraps in a
   * narrow pane instead of needing a wide table (value mappings have six). Used for the platform data,
   * attachments and SOAP headers of the Groovy tab.
   */
  interface Column {
    /** The row's field, or a function of the row when the field depends on it (an attachment's content). */
    field: string | ((row: R) => string);
    label: string;
    type?: 'text' | 'number' | 'checkbox' | 'select';
    options?: { value: string; label: string }[];
    placeholder?: string | ((row: R) => string);
    /** A cell that shows something the table does not edit. */
    disabled?: (row: R) => boolean;
    /** Grid columns the cell takes (default 1). */
    span?: number;
  }
  interface Props {
    rows: R[];
    columns: Column[];
    newRow: () => R;
    /** Cells per line before a row wraps. */
    perLine?: number;
    /** Accessible name of a row, e.g. "Value mapping"; fields are "<label> <row number>". */
    addLabel?: string;
    emptyText?: string;
    disabled?: boolean;
    onchange?: () => void;
  }
  let { rows = $bindable([]), columns, newRow, perLine = 3, addLabel = '+ Add', emptyText = 'None', disabled = false,
    onchange }: Props = $props();

  const fieldOf = (c: Column, row: R) => (typeof c.field === 'function' ? c.field(row) : c.field);
  const placeholderOf = (c: Column, row: R) => (typeof c.placeholder === 'function' ? c.placeholder(row) : c.placeholder ?? '');

  /** The remove button ends a row's first line: after the cell that fills it, or after the last cell. */
  let removeAfter = $derived.by(() => {
    let filled = 0;
    for (let k = 0; k < columns.length; k++) {
      filled += columns[k].span ?? 1;
      if (filled >= perLine) return k;
    }
    return columns.length - 1;
  });

  function set(i: number, c: Column, value: unknown) {
    (rows[i] as Record<string, unknown>)[fieldOf(c, rows[i])] = value;
    onchange?.();
  }
  function add() {
    rows = [...rows, newRow()];
    onchange?.();
  }
  function remove(i: number) {
    rows = rows.filter((_, j) => j !== i);
    onchange?.();
  }
</script>

<div class="rows">
  {#if rows.length === 0}
    <p class="empty">{emptyText}</p>
  {/if}
  {#each rows as row, i (i)}
    <div class="row" style:grid-template-columns={`repeat(${perLine}, minmax(0, 1fr)) auto`}>
      {#each columns as c, k}
        {@const off = disabled || (c.disabled?.(row) ?? false)}
        <label class="cell" class:check={c.type === 'checkbox'} style:grid-column={c.span ? `span ${c.span}` : undefined}>
          <span class="cap">{c.label}</span>
          {#if c.type === 'checkbox'}
            <input type="checkbox" checked={row[fieldOf(c, row)] === true} disabled={off} aria-label="{c.label} {i + 1}"
              onchange={(e) => set(i, c, (e.currentTarget as HTMLInputElement).checked)} />
          {:else if c.type === 'select'}
            <select value={row[fieldOf(c, row)]} disabled={off} aria-label="{c.label} {i + 1}"
              onchange={(e) => set(i, c, (e.currentTarget as HTMLSelectElement).value)}>
              {#each c.options ?? [] as o}<option value={o.value}>{o.label}</option>{/each}
            </select>
          {:else}
            <input class="mono" inputmode={c.type === 'number' ? 'numeric' : undefined} value={off && c.disabled ? '' : row[fieldOf(c, row)] ?? ''}
              placeholder={placeholderOf(c, row)} disabled={off} aria-label="{c.label} {i + 1}"
              oninput={(e) => set(i, c, (e.currentTarget as HTMLInputElement).value)} />
          {/if}
        </label>
        {#if k === removeAfter}
          <button class="icon remove" title="Remove" aria-label="Remove row {i + 1}" {disabled} onclick={() => remove(i)}>✕</button>
        {/if}
      {/each}
    </div>
  {/each}
  <button class="small add" {disabled} onclick={add}>{addLabel}</button>
</div>

<style>
  .rows { display: flex; flex-direction: column; gap: 6px; }
  .row { display: grid; gap: 4px 6px; align-items: end; padding: 5px 6px; border: 1px solid var(--border-soft); border-radius: 5px; }
  .cell { display: flex; flex-direction: column; gap: 1px; min-width: 0; }
  .cell.check { flex-direction: row; align-items: center; gap: 5px; padding-bottom: 3px; }
  .cap { font-size: 10px; color: var(--muted); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
  .cell input:not([type='checkbox']), .cell select { width: 100%; box-sizing: border-box; font-size: 12px; }
  .remove { align-self: end; grid-column: -2 / -1; }
  .add { align-self: flex-start; }
  .empty { margin: 0; color: var(--muted); font-size: 12px; }
</style>
