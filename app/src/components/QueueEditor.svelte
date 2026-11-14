<script lang="ts">
  import { formatQueue, parseQueue, type Queue, type QueueEntry } from '../lib/queue';

  /**
   * A String[] argument of a Message Mapping function: contexts of values, edited as rows. The text form
   * (one JSON array per context, see queue.ts) stays the source of truth, so test cases and agents keep it;
   * Text shows it for copy and paste.
   */
  interface Props {
    /** Undefined until the argument has been typed (groovy.args starts empty). */
    value: string | null | undefined;
    /** Accessible name of the argument, e.g. "Argument 1"; the fields add their position to it. */
    label: string;
    onchange?: () => void;
  }
  let { value = $bindable(), label, onchange }: Props = $props();

  let asText = $state(false);
  let parsed = $derived.by((): { queue: Queue; error: string } => {
    try {
      return { queue: parseQueue(value ?? ''), error: '' };
    } catch (e) {
      return { queue: [[]], error: (e as Error).message };
    }
  });
  let showText = $derived(asText || parsed.error !== '');

  type EntryKind = 'value' | 'suppress' | 'nil' | 'null';
  function kindOf(e: QueueEntry): EntryKind {
    if (e === null) return 'null';
    if (typeof e === 'object') return 'suppress' in e ? 'suppress' : 'nil';
    return 'value';
  }
  function entryFor(kind: EntryKind, text: string): QueueEntry {
    return kind === 'suppress' ? { suppress: true } : kind === 'nil' ? { xsiNil: true } : kind === 'null' ? null : text;
  }

  function write(q: Queue) {
    value = formatQueue(q.length ? q : [[]]);
    onchange?.();
  }
  /** A copy to change; the derived queue itself must stay as parsed. */
  function edit(f: (q: Queue) => void) {
    const q = parsed.queue.map((c) => [...c]);
    f(q);
    write(q);
  }
</script>

<div class="queue">
  {#if showText}
    <textarea class="mono" rows="4" bind:value oninput={() => onchange?.()} aria-label={label}
      placeholder={'One context per line, e.g.\n["A", "B"]\n["C", {"suppress": true}]'}></textarea>
    {#if parsed.error}<p class="err">{parsed.error}</p>{/if}
  {:else}
    <p class="intro">The values of the source field, as the mapping passes them in: one box per context (a context
      change starts the next one, e.g. per parent element).</p>
    {#each parsed.queue as ctx, c}
      <div class="ctx" role="group" aria-label="{label}, context {c + 1}">
        <div class="ctx-head">
          <span>Context {c + 1}</span>
          <button class="icon" onclick={() => edit((q) => q.splice(c, 1))} title="Remove this context"
            aria-label="Remove context {c + 1}">×</button>
        </div>
        {#each ctx as entry, v}
          {@const kind = kindOf(entry)}
          <div class="val">
            <input class="mono" value={kind === 'value' ? entry as string : ''} disabled={kind !== 'value'}
              placeholder={kind === 'suppress' ? 'SUPPRESS' : kind === 'nil' ? 'xsi:nil' : kind === 'null' ? 'null' : ''}
              aria-label="{label}, context {c + 1}, value {v + 1}"
              oninput={(e) => edit((q) => { q[c][v] = (e.currentTarget as HTMLInputElement).value; })} />
            <select value={kind} class:plain={kind === 'value'} aria-label="Kind of value {v + 1} in context {c + 1}"
              title="text: the field's value · SUPPRESS: a suppressed value (no content, still a place in the context) · xsi:nil: a field marked nil · null: no value"
              onchange={(e) => edit((q) => { q[c][v] = entryFor((e.currentTarget as HTMLSelectElement).value as EntryKind, ''); })}>
              <option value="value">text</option>
              <option value="suppress">SUPPRESS</option>
              <option value="nil">xsi:nil</option>
              <option value="null">null</option>
            </select>
            <button class="icon" onclick={() => edit((q) => q[c].splice(v, 1))} title="Remove this value"
              aria-label="Remove value {v + 1} of context {c + 1}">×</button>
          </div>
        {/each}
        <button class="small add" onclick={() => edit((q) => q[c].push(''))}>+ Value</button>
      </div>
    {/each}
  {/if}
  <div class="actions">
    {#if !showText}<button class="small" onclick={() => edit((q) => q.push(['']))}
      title="A new context: the values after a context change">+ Context</button>{/if}
    <button class="small" class:on={showText} aria-pressed={showText} disabled={parsed.error !== ''}
      onclick={() => (asText = !asText)} title="One context per line as a JSON array, as in case.json">Text</button>
  </div>
</div>

<style>
  .queue { display: flex; flex-direction: column; gap: 6px; }
  textarea { font-size: 12px; resize: vertical; }
  .ctx { border: 1px solid var(--border); border-radius: 5px; padding: 4px 6px 6px; display: flex; flex-direction: column; gap: 4px; }
  .ctx-head { display: flex; justify-content: space-between; align-items: center; color: var(--muted); font-size: 11px; }
  .val { display: flex; gap: 4px; align-items: center; }
  .val input { flex: 1; min-width: 0; font-size: 12px; }
  .val select { font-size: 11px; }
  /* The usual case stays quiet; a special value stands out. */
  .val select.plain { color: var(--muted); border-color: transparent; background: transparent; }
  .intro { margin: 0; font-size: 11px; color: var(--muted); }
  .add { align-self: flex-start; }
  .actions { display: flex; gap: 6px; }
  .on { border-color: var(--accent); color: var(--accent); }
  .err { color: var(--err); font-size: 11px; margin: 0; }
</style>
