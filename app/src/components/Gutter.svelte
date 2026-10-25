<script lang="ts">
  import { savePaneSizes } from '../lib/split.svelte';

  /** Drag handle between pane `i` and pane `i + 1` of a grid laid out with paneColumns(). */
  let { panes, i }: { panes: { key: string; defaults: number[]; sizes: number[] }; i: number } = $props();

  const MIN = 160;

  function widths(gutter: HTMLElement) {
    const cells = [...gutter.parentElement!.children].filter((c) => !c.classList.contains('gutter')) as HTMLElement[];
    return [cells[i].getBoundingClientRect().width, cells[i + 1].getBoundingClientRect().width];
  }

  /** Moves the border by `dx` pixels, keeping the sum of both fr values. */
  function shift(a: number, b: number, dx: number) {
    const d = Math.max(MIN - a, Math.min(b - MIN, dx));
    const total = panes.sizes[i] + panes.sizes[i + 1];
    panes.sizes[i] = (total * (a + d)) / (a + b);
    panes.sizes[i + 1] = total - panes.sizes[i];
  }

  function down(e: PointerEvent) {
    if (e.button !== 0) return;
    const gutter = e.currentTarget as HTMLElement;
    const [a, b] = widths(gutter);
    const x0 = e.clientX;
    gutter.setPointerCapture(e.pointerId);
    document.body.classList.add('resizing');
    const move = (ev: PointerEvent) => shift(a, b, ev.clientX - x0);
    const up = () => {
      gutter.removeEventListener('pointermove', move);
      gutter.removeEventListener('pointerup', up);
      gutter.removeEventListener('pointercancel', up);
      document.body.classList.remove('resizing');
      savePaneSizes(panes);
    };
    gutter.addEventListener('pointermove', move);
    gutter.addEventListener('pointerup', up);
    gutter.addEventListener('pointercancel', up);
    e.preventDefault();
  }

  function key(e: KeyboardEvent) {
    if (e.key !== 'ArrowLeft' && e.key !== 'ArrowRight') return;
    const [a, b] = widths(e.currentTarget as HTMLElement);
    shift(a, b, e.key === 'ArrowLeft' ? -24 : 24);
    savePaneSizes(panes);
    e.preventDefault();
  }

  function reset() {
    panes.sizes.splice(0, panes.sizes.length, ...panes.defaults);
    savePaneSizes(panes);
  }
</script>

<!-- A focusable separator is a widget (WAI-ARIA window splitter); svelte's check treats it as static. -->
<!-- svelte-ignore a11y_no_noninteractive_tabindex, a11y_no_noninteractive_element_interactions -->
<div class="gutter" role="separator" aria-orientation="vertical" aria-label="Resize panes" tabindex="0"
  title="Drag to resize, double-click to reset" onpointerdown={down} onkeydown={key} ondblclick={reset}></div>

<style>
  .gutter { background: var(--border); cursor: col-resize; touch-action: none; min-height: 0; }
  .gutter:hover, .gutter:focus-visible { background: var(--accent); outline: none; }
  :global(body.resizing) { cursor: col-resize; user-select: none; }
  :global(body.resizing iframe), :global(body.resizing .monaco-editor) { pointer-events: none; }
</style>
