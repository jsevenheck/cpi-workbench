<script lang="ts">
  import { onDestroy, onMount } from 'svelte';
  import { monaco } from '../lib/monaco';
  import type { Diagnostic } from '../lib/types';

  interface Props {
    value: string;
    language?: string;
    readOnly?: boolean;
    markers?: Diagnostic[];
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

  const common: monaco.editor.IEditorOptions = {
    automaticLayout: true,
    minimap: { enabled: false },
    fontSize: 13,
    scrollBeyondLastLine: false,
    wordWrap: 'off',
    renderWhitespace: 'selection',
    fixedOverflowWidgets: true,
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

  export function revealLine(line: number) {
    editor?.revealLineInCenter(line);
    editor?.setPosition({ lineNumber: line, column: 1 });
    editor?.focus();
  }

  onMount(create);
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
