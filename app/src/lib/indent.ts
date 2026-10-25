// Re-indenters for the mapping and script editors. They only change the whitespace at the start of
// lines where that whitespace cannot reach the result, so formatting never changes what a mapping
// or script does. Lines they cannot judge stay as they are.

const XSLT_NS = 'http://www.w3.org/1999/XSL/Transform';

/** Attributes of XSLT instructions that hold XPath, where whitespace outside string literals is free. */
const XPATH_ATTRS = new Set(['select', 'test', 'match', 'use', 'group-by', 'group-adjacent',
  'group-starting-with', 'group-ending-with']);

/** The indent unit a file already uses (the smallest step between indented lines), else `fallback`. */
export function detectUnit(text: string, fallback: string): string {
  if (/^\t/m.test(text)) return '\t';
  let min = 0;
  for (const m of text.matchAll(/^( +)\S/gm)) {
    if (m[1].length >= 2 && (min === 0 || m[1].length < min)) min = m[1].length;
  }
  return min === 2 || min === 4 ? ' '.repeat(min) : fallback;
}

function leading(line: string) {
  return line.length - line.trimStart().length;
}

/** Moves a line by `delta` columns; a negative delta removes at most the line's own indentation. */
function shift(line: string, delta: number) {
  if (delta >= 0) return ' '.repeat(delta) + line;
  return line.slice(Math.min(-delta, leading(line)));
}

/**
 * Re-indents an XSLT stylesheet (or any XML) by element depth. A line is moved only when the text
 * before it is whitespace between element tags, which XSLT strips from a stylesheet. Text lines,
 * xsl:text, xml:space="preserve", CDATA and comment bodies keep their whitespace. Lines that continue
 * a start tag move with it, except inside attribute values that are not XPath.
 */
export function reindentXml(text: string, unit = detectUnit(text, '  ')): string {
  const xslPrefixes = new Set<string>();
  for (const m of text.matchAll(/xmlns:([\w.-]+)\s*=\s*["']([^"']*)["']/g)) if (m[2] === XSLT_NS) xslPrefixes.add(m[1]);
  if (xslPrefixes.size === 0) xslPrefixes.add('xsl');

  const lines = text.split('\n');
  const out: string[] = [];
  // Parser state, carried across lines.
  let mode: 'text' | 'tag' | 'attr' | 'comment' | 'cdata' | 'pi' | 'doctype' = 'text';
  let depth = 0;
  let preserve = 0; // > 0 inside xsl:text or xml:space="preserve"
  const stack: boolean[] = []; // per open element: does it preserve whitespace?
  let textSinceTag = false; // non-whitespace text since the last element tag
  let tagName = '';
  let tagClosing = false;
  let tagPreserve = false;
  let tagDelta = 0; // how far the current start tag's first line moved
  let attrName = '';
  let attrQuote = '';
  let attrLiteral = ''; // open string literal inside an XPath attribute
  let pendingAttr = '';

  const isXsl = (name: string) => xslPrefixes.has(name.split(':')[0]) && name.includes(':');

  for (const line of lines) {
    const trimmed = line.trimStart();
    let result = line;
    if (mode !== 'tag' && mode !== 'attr') tagDelta = 0;
    if (mode === 'text' && preserve === 0 && trimmed.startsWith('<') && !trimmed.startsWith('<![CDATA[') && !textSinceTag) {
      const d = trimmed.startsWith('</') ? depth - 1 : depth;
      result = trimmed === '' ? '' : unit.repeat(Math.max(0, d)) + trimmed;
      tagDelta = result.length - trimmed.length - leading(line);
    } else if (mode === 'text' && trimmed === '' && preserve === 0 && !textSinceTag) {
      result = '';
    } else if (mode === 'tag' && preserve === 0) {
      result = trimmed === '' ? '' : shift(line, tagDelta);
    } else if (mode === 'attr' && preserve === 0 && isXsl(tagName) && XPATH_ATTRS.has(attrName) && !attrLiteral) {
      result = shift(line, tagDelta);
    } else if (mode === 'text' && line.trim() === '' && preserve === 0) {
      result = line;
    }
    out.push(result);

    // Advance the parser over the original line.
    const s = line + '\n';
    for (let i = 0; i < s.length; i++) {
      const c = s[i];
      switch (mode) {
        case 'text':
          if (c === '<') {
            if (s.startsWith('<!--', i)) { mode = 'comment'; i += 3; }
            else if (s.startsWith('<![CDATA[', i)) { mode = 'cdata'; i += 8; textSinceTag = true; }
            else if (s.startsWith('<!', i)) { mode = 'doctype'; }
            else if (s.startsWith('<?', i)) { mode = 'pi'; i += 1; }
            else {
              mode = 'tag';
              tagClosing = s[i + 1] === '/';
              const m = /^<\/?([\w:.-]+)/.exec(s.slice(i));
              tagName = m ? m[1] : '';
              tagPreserve = isXsl(tagName) && tagName.endsWith(':text');
              pendingAttr = '';
              textSinceTag = false;
            }
          } else if (!/\s/.test(c)) {
            textSinceTag = true;
          }
          break;
        case 'tag':
          if (c === '"' || c === "'") {
            mode = 'attr';
            attrQuote = c;
            attrName = pendingAttr;
            attrLiteral = '';
          } else if (c === '>') {
            mode = 'text';
            textSinceTag = false;
            if (tagClosing) {
              depth--;
              const p = stack.pop();
              if (p) preserve--;
            } else if (s[i - 1] !== '/') {
              depth++;
              stack.push(tagPreserve);
              if (tagPreserve) preserve++;
            }
          } else if (/[\w:.-]/.test(c)) {
            const m = /^[\w:.-]+/.exec(s.slice(i));
            if (m && !tagName.endsWith(m[0])) pendingAttr = m[0];
            if (m) i += m[0].length - 1;
          }
          break;
        case 'attr':
          if (c === attrQuote && !attrLiteral) {
            if (attrName === 'xml:space' && /preserve$/.test(s.slice(0, i))) tagPreserve = true;
            mode = 'tag';
          } else if ((c === '"' || c === "'") && c !== attrQuote) {
            attrLiteral = attrLiteral === c ? '' : attrLiteral || c;
          }
          break;
        case 'comment':
          if (s.startsWith('-->', i)) { mode = 'text'; i += 2; }
          break;
        case 'cdata':
          if (s.startsWith(']]>', i)) { mode = 'text'; i += 2; }
          break;
        case 'pi':
          if (s.startsWith('?>', i)) { mode = 'text'; i += 1; }
          break;
        case 'doctype':
          if (c === '>') mode = 'text';
          break;
      }
    }
  }
  return out.join('\n');
}

/**
 * Re-indents a Groovy script by bracket depth: one unit per line that opens {, [ or (, closing
 * brackets at the start of a line go back to the opener's level, and a line starting with `.` or `?.`
 * continues the previous one. Lines inside multi-line strings keep their whitespace; block comment
 * lines starting with `*` line up under the opening `/*`.
 */
export function reindentGroovy(text: string, unit = detectUnit(text, '    ')): string {
  const lines = text.split('\n');
  const out: string[] = [];
  type Mode = 'code' | 'block' | 'sq' | 'dq' | 'tsq' | 'tdq' | 'slashy' | 'dslashy';
  // A string mode with ${...} pushes 'code' with its own brace counter.
  const modes: { mode: Mode; braces: number }[] = [{ mode: 'code', braces: 0 }];
  const levels: number[] = []; // indent level inside each open bracket
  let lastSignificant = ''; // last code character (or word) before a possible slashy string

  const top = () => modes[modes.length - 1];

  for (const line of lines) {
    const trimmed = line.trimStart();
    const m = top().mode;
    let level = levels.length ? levels[levels.length - 1] : 0;
    let result = line;
    if (modes.length === 1 && m === 'code') {
      if (trimmed === '') result = '';
      else {
        // Closers at the start of the line belong to the opener's line.
        let k = 0;
        while (k < trimmed.length && k < levels.length && '}])'.includes(trimmed[k])) k++;
        if (k > 0) level = levels[levels.length - k] - 1;
        const cont = /^(\?\.|\.(?!\.))/.test(trimmed) ? 1 : 0;
        result = unit.repeat(level + cont) + trimmed;
      }
    } else if (modes.length === 2 && m === 'block' && trimmed.startsWith('*')) {
      result = unit.repeat(level) + ' ' + trimmed;
    }
    out.push(result);

    // Level of this line as written now, for brackets opened on it.
    const lineLevel = Math.floor((result.length - result.trimStart().length) / Math.max(1, unit.length));
    const s = line + '\n';
    for (let i = 0; i < s.length; i++) {
      const c = s[i];
      const t = top();
      switch (t.mode) {
        case 'code':
          if (c === '/' && s[i + 1] === '/') { i = s.length; break; }
          if (c === '/' && s[i + 1] === '*') { modes.push({ mode: 'block', braces: 0 }); i++; break; }
          if (s.startsWith("'''", i)) { modes.push({ mode: 'tsq', braces: 0 }); i += 2; break; }
          if (s.startsWith('"""', i)) { modes.push({ mode: 'tdq', braces: 0 }); i += 2; break; }
          if (c === "'") { modes.push({ mode: 'sq', braces: 0 }); break; }
          if (c === '"') { modes.push({ mode: 'dq', braces: 0 }); break; }
          if (s.startsWith('$/', i)) { modes.push({ mode: 'dslashy', braces: 0 }); i++; break; }
          if (c === '/' && (lastSignificant === '' || /^[=(,[!&|?:{};+\-*%<>~]$/.test(lastSignificant)
              || ['return', 'in', 'case', 'assert'].includes(lastSignificant))) {
            modes.push({ mode: 'slashy', braces: 0 });
            break;
          }
          if (modes.length > 1) {
            // Inside ${...} of a string: only track the braces that end it.
            if (c === '{') t.braces++;
            else if (c === '}') {
              if (t.braces === 0) { modes.pop(); break; }
              t.braces--;
            }
          } else if ('{[('.includes(c)) {
            // Several brackets opened on one line indent their content by one level, not several.
            levels.push(lineLevel + 1);
          } else if ('}])'.includes(c)) {
            levels.pop();
          }
          if (!/\s/.test(c)) {
            if (/\w/.test(c)) {
              const w = /^\w+/.exec(s.slice(i))![0];
              lastSignificant = w;
              i += w.length - 1;
            } else lastSignificant = c;
          }
          break;
        case 'block':
          if (s.startsWith('*/', i)) { modes.pop(); i++; }
          break;
        case 'sq':
          if (c === '\\') i++;
          else if (c === "'" || c === '\n') { modes.pop(); lastSignificant = 'x'; }
          break;
        case 'dq':
          if (c === '\\') i++;
          else if (s.startsWith('${', i)) { modes.push({ mode: 'code', braces: 0 }); i++; }
          else if (c === '"' || c === '\n') { modes.pop(); lastSignificant = 'x'; }
          break;
        case 'tsq':
          if (c === '\\') i++;
          else if (s.startsWith("'''", i)) { modes.pop(); i += 2; lastSignificant = 'x'; }
          break;
        case 'tdq':
          if (c === '\\') i++;
          else if (s.startsWith('${', i)) { modes.push({ mode: 'code', braces: 0 }); i++; }
          else if (s.startsWith('"""', i)) { modes.pop(); i += 2; lastSignificant = 'x'; }
          break;
        case 'slashy':
          if (c === '\\') i++;
          else if (s.startsWith('${', i)) { modes.push({ mode: 'code', braces: 0 }); i++; }
          else if (c === '/') { modes.pop(); lastSignificant = 'x'; }
          break;
        case 'dslashy':
          if (s.startsWith('/$', i)) { modes.pop(); i++; lastSignificant = 'x'; }
          else if (c === '$' && (s[i + 1] === '$' || s[i + 1] === '/')) i++;
          else if (s.startsWith('${', i)) { modes.push({ mode: 'code', braces: 0 }); i++; }
          break;
      }
    }
  }
  return out.join('\n');
}
