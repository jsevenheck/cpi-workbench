// SAP: `function:simple('${property.x}')` in a Filter XPath. Camel Simple gives the value, which goes into the
// XPath as a string literal. The MCP server does the same (app/src-tauri/src/mcp/flow.rs, resolve_simple_functions).

const CALL = /function:simple\(\s*(?:'([^']*)'|"([^"]*)")\s*\)/g;

/** The XPath with each function:simple(...) replaced by its value as an XPath string literal. */
export async function resolveSimpleFunctions(xpath: string, evaluate: (expression: string) => Promise<string>) {
  let out = xpath;
  for (const m of xpath.matchAll(CALL)) {
    const v = await evaluate(m[1] ?? m[2]);
    // A function, not a string: in a replacement string, $$, $& and $1 are patterns, and the value would change.
    out = out.replace(m[0], () => "'" + v.replaceAll("'", "''") + "'");
  }
  return out;
}
