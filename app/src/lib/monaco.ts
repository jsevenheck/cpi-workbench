// Monaco is bundled locally (no CDN) so the app works offline. Only the languages we need are loaded.
import * as monaco from 'monaco-editor/esm/vs/editor/editor.api';
import 'monaco-editor/esm/vs/basic-languages/xml/xml.contribution';
import 'monaco-editor/esm/vs/basic-languages/java/java.contribution';
import 'monaco-editor/esm/vs/language/json/monaco.contribution';
import 'monaco-editor/esm/vs/editor/contrib/find/browser/findController';
import 'monaco-editor/esm/vs/editor/contrib/folding/browser/folding';
import 'monaco-editor/esm/vs/editor/contrib/bracketMatching/browser/bracketMatching';
import 'monaco-editor/esm/vs/editor/contrib/wordHighlighter/browser/wordHighlighter';
import 'monaco-editor/esm/vs/editor/contrib/comment/browser/comment';
import 'monaco-editor/esm/vs/editor/contrib/multicursor/browser/multicursor';
import 'monaco-editor/esm/vs/editor/contrib/hover/browser/hoverContribution';
import 'monaco-editor/esm/vs/editor/contrib/suggest/browser/suggestController';
import 'monaco-editor/esm/vs/editor/contrib/snippet/browser/snippetController2';
import EditorWorker from 'monaco-editor/esm/vs/editor/editor.worker?worker';
import JsonWorker from 'monaco-editor/esm/vs/language/json/json.worker?worker';
import { memberAccess, membersFor } from './completions';

(self as any).MonacoEnvironment = {
  getWorker(_: unknown, label: string) {
    return label === 'json' ? new JsonWorker() : new EditorWorker();
  },
};

// Disposing an editor (switching views) cancels Monaco's pending work, which can reject with its
// CancellationError where no one awaits it. Monaco itself treats it as expected, not as an error; the
// check is the one of its isCancellationError (vs/base/common/errors).
window.addEventListener('unhandledrejection', (e) => {
  if (e.reason instanceof Error && e.reason.name === 'Canceled' && e.reason.message === 'Canceled') e.preventDefault();
});

// Groovy scripts (language "java"): SAP Script API members after `message.`, `messageLog.`, ... .
monaco.languages.registerCompletionItemProvider('java', {
  triggerCharacters: ['.'],
  provideCompletionItems(model, position) {
    const prefix = model.getValueInRange({ startLineNumber: position.lineNumber, startColumn: 1,
      endLineNumber: position.lineNumber, endColumn: position.column });
    const access = memberAccess(prefix);
    const members = access ? membersFor(model.getValue(), access.receiver) : null;
    if (!access || !members) return { suggestions: [] };
    const range = new monaco.Range(position.lineNumber, position.column - access.partial.length, position.lineNumber, position.column);
    return {
      suggestions: members.map((m, i) => ({
        label: { label: m.name, detail: `(${m.params})`, description: m.returns },
        kind: monaco.languages.CompletionItemKind.Method,
        detail: `${m.returns} ${m.name}(${m.params})`,
        documentation: 'SAP Script API',
        insertText: m.params ? `${m.name}($0)` : `${m.name}()`,
        insertTextRules: monaco.languages.CompletionItemInsertTextRule.InsertAsSnippet,
        sortText: String(i).padStart(3, '0'),
        range,
      })),
    };
  },
});

const dark = window.matchMedia('(prefers-color-scheme: dark)');
const applyTheme = () => monaco.editor.setTheme(dark.matches ? 'vs-dark' : 'vs');
dark.addEventListener('change', applyTheme);
applyTheme();

export function languageFor(path: string): string {
  const p = path.toLowerCase();
  if (p.endsWith('.json')) return 'json';
  if (p.endsWith('.groovy') || p.endsWith('.gsh') || p.endsWith('.java')) return 'java';
  if (p.endsWith('.xml') || p.endsWith('.xsl') || p.endsWith('.xslt') || p.endsWith('.xsd') || p.endsWith('.wsdl')) return 'xml';
  return 'plaintext';
}

export { monaco };
