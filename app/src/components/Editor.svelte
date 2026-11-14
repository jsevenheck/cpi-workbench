<script lang="ts">
  import { onDestroy, onMount } from 'svelte';
  import { monaco } from '../lib/monaco';
  import type { Diagnostic } from '../lib/types';

  interface Props {
    value: string;
    language?: string;
    readOnly?: boolean;
    markers?: Diagnostic[];
    /** Text ranges (character offsets) to mark, e.g. the nodes an XPath selected. */
    highlights?: { start: number; end: number }[];
    /** When set, a side-by-side diff (original → value) is shown instead of a plain editor. */
    original?: string | null;
    onchange?: (v: string) => void;
    onrun?: () => void;
    onsave?: () => void;
    label?: string;
  }

  let {
    value = $bindable(''),
    language = 'xml',
    readOnly = false,
    markers = [],
    highlights = [],
    original = null,
    onchange,
    onrun,
    onsave,
    label = 'Editor',
  }: Props = $props();

  let host: HTMLDivElement;
  let editor: monaco.editor.IStandaloneCodeEditor | null = null;
  let diff: monaco.editor.IStandaloneDiffEditor | null = null;
  let model: monaco.editor.ITextModel | null = null;
  let originalModel: monaco.editor.ITextModel | null = null;
  let applying = false;

  const common: monaco.editor.IEditorOptions & monaco.editor.IGlobalEditorOptions = {
    automaticLayout: true,
    minimap: { enabled: false },
    fontSize: 13,
    scrollBeyondLastLine: false,
    wordWrap: 'off',
    renderWhitespace: 'selection',
    fixedOverflowWidgets: true,
    // Suggestions only where the app provides them (Script API after a dot) or on Ctrl+Space.
    quickSuggestions: false,
    wordBasedSuggestions: 'off',
  };

  function create() {
    dispose();
    model = monaco.editor.createModel(value ?? '', language);
    model.onDidChangeContent(() => {
      if (applying || !model) return;
      value = model.getValue();
      onchange?.(value);
    });
    if (original !== null && original !== undefined) {
      originalModel = monaco.editor.createModel(original, language);
      diff = monaco.editor.createDiffEditor(host, { ...common, readOnly: true, renderSideBySide: true,
        ariaLabel: label, originalEditable: false });
      diff.setModel({ original: originalModel, modified: model });
    } else {
      editor = monaco.editor.create(host, { ...common, model, readOnly, ariaLabel: label });
      editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter, () => onrun?.());
      editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS, () => onsave?.());
    }
    applyMarkers();
    highlightIds = [];
    applyHighlights();
  }

  function dispose() {
    editor?.dispose();
    diff?.dispose();
    model?.dispose();
    originalModel?.dispose();
    editor = diff = null;
    model = originalModel = null;
  }

  function applyMarkers() {
    if (!model) return;
    monaco.editor.setModelMarkers(model, 'cpiwb', (markers ?? []).filter((m) => m.line).map((m) => ({
      severity: m.severity === 'error' ? monaco.MarkerSeverity.Error
        : m.severity === 'warning' ? monaco.MarkerSeverity.Warning : monaco.MarkerSeverity.Info,
      message: (m.code ? m.code + ': ' : '') + m.message,
      startLineNumber: m.line!,
      startColumn: m.column && m.column > 0 ? Math.max(1, m.column - 1) : 1,
      endLineNumber: m.line!,
      endColumn: m.column && m.column > 0 ? m.column + 1 : model!.getLineMaxColumn(Math.min(m.line!, model!.getLineCount())),
    })));
  }

  let highlightIds: string[] = [];
  function applyHighlights() {
    if (!model || !editor) return;
    const m = model;
    highlightIds = editor.deltaDecorations(highlightIds, (highlights ?? []).map((h) => {
      const s = m.getPositionAt(h.start);
      const e = m.getPositionAt(h.end);
      return {
        range: new monaco.Range(s.lineNumber, s.column, e.lineNumber, e.column),
        options: { className: 'cpiwb-match', overviewRuler: { color: '#e0a800', position: monaco.editor.OverviewRulerLane.Center } },
      };
    }));
  }

  /** The cursor as a character offset, or null without an editor. */
  export function cursorOffset(): number | null {
    const p = editor?.getPosition();
    return p && model ? model.getOffsetAt(p) : null;
  }

  /** Selects a text range and scrolls it into view. */
  export function selectRange(start: number, end: number) {
    if (!editor || !model) return;
    const s = model.getPositionAt(start);
    const e = model.getPositionAt(end);
    const range = new monaco.Range(s.lineNumber, s.column, e.lineNumber, e.column);
    editor.setSelection(range);
    editor.revealRangeInCenterIfOutsideViewport(range);
    editor.focus();
  }

  export function revealLine(line: number) {
    editor?.revealLineInCenter(line);
    editor?.setPosition({ lineNumber: line, column: 1 });
    editor?.focus();
  }

  // Monaco scrolls virtually. When its hidden textarea gets focus far right, the browser can also
  // scroll Monaco's container natively, which shifts the text out of view for good; undo that.
  function nativeScroll(e: Event) {
    const t = e.target as HTMLElement;
    const monacoBox = t.classList?.contains('monaco-scrollable-element') || t.classList?.contains('overflow-guard');
    if (monacoBox && (t.scrollLeft !== 0 || t.scrollTop !== 0)) {
      t.scrollLeft = 0;
      t.scrollTop = 0;
    }
  }

  onMount(() => {
    create();
    host.addEventListener('scroll', nativeScroll, true);
  });
  onDestroy(dispose);

  // External value changes (file loaded, result arrived) → update the model without echo.
  $effect(() => {
    const v = value ?? '';
    if (model && model.getValue() !== v) {
      applying = true;
      model.pushEditOperations([], [{ range: model.getFullModelRange(), text: v }], () => null);
      applying = false;
      // New content (file loaded, new result): start at the left edge again.
      editor?.setScrollLeft(0);
      diff?.getModifiedEditor().setScrollLeft(0);
    }
  });

  $effect(() => {
    if (model && monaco.editor.getModel(model.uri) && model.getLanguageId() !== language) {
      monaco.editor.setModelLanguage(model, language);
    }
  });

  $effect(() => {
    markers;
    applyMarkers();
  });

  $effect(() => {
    highlights;
    value; // offsets belong to the text they were found in
    applyHighlights();
  });

  // Switching between plain and diff mode needs a new editor instance.
  let diffMode = $derived(original !== null && original !== undefined);
  let lastMode: boolean | undefined;
  $effect(() => {
    const m = diffMode;
    if (lastMode !== undefined && m !== lastMode && host) create();
    lastMode = m;
  });
  $effect(() => {
    if (originalModel && original != null && originalModel.getValue() !== original) originalModel.setValue(original);
  });
</script>

<div class="editor" bind:this={host} role="region" aria-label={label}></div>

<style>
  .editor {
    width: 100%;
    height: 100%;
    min-height: 80px;
    overflow: hidden;
  }
</style>
