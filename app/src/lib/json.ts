/**
 * Pretty-prints JSON by re-indenting its tokens. Unlike JSON.stringify(JSON.parse(...)), numbers keep
 * their spelling (15.50 stays 15.50, 1e3 stays 1e3) and large integers keep every digit.
 * Throws the JSON.parse error for invalid input.
 */
export function formatJson(text: string, indent = '  '): string {
  JSON.parse(text);
  let out = '';
  let depth = 0;
  let i = 0;
  const newline = () => '\n' + indent.repeat(depth);
  while (i < text.length) {
    const c = text[i];
    if (c === '"') {
      let j = i + 1;
      while (text[j] !== '"') j += text[j] === '\\' ? 2 : 1;
      out += text.slice(i, j + 1);
      i = j + 1;
      continue;
    }
    if (c === '{' || c === '[') {
      // Keep empty objects and arrays on one line.
      let j = i + 1;
      while (/\s/.test(text[j])) j++;
      if (text[j] === (c === '{' ? '}' : ']')) {
        out += c + text[j];
        i = j + 1;
        continue;
      }
      depth++;
      out += c + newline();
    } else if (c === '}' || c === ']') {
      depth--;
      out += newline() + c;
    } else if (c === ',') {
      out += ',' + newline();
    } else if (c === ':') {
      out += ': ';
    } else if (!/\s/.test(c)) {
      out += c;
    }
    i++;
  }
  return out + '\n';
}
