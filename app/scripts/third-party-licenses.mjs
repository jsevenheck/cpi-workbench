// Writes the licence texts of the desktop app's own dependencies: the Rust crates the shell links
// (normal dependencies for the target platform) and the npm production dependencies the UI bundles.
// Identical licence texts are printed once, with every package that uses them.
//   node scripts/third-party-licenses.mjs <out-file>
import { execFileSync, execSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const app = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const out = process.argv[2];
if (!out) {
  console.error('usage: node scripts/third-party-licenses.mjs <out-file>');
  process.exit(1);
}
const triple = process.env.TAURI_TARGET_TRIPLE
  ?? execSync('rustc -vV').toString().match(/host: (\S+)/)[1];

const LICENCE_FILE = /^(licen[cs]e|copying|notice)([-._].*)?$/i;
/** The licence and notice files at the top of a package folder. */
const licenceFiles = (dir) => fs.readdirSync(dir)
  .filter((f) => LICENCE_FILE.test(f) && fs.statSync(path.join(dir, f)).isFile())
  .sort()
  .map((f) => fs.readFileSync(path.join(dir, f), 'utf8').replace(/\r\n/g, '\n').trim());

const packages = []; // { name, version, license, texts }

// Rust: walk the resolve graph from the shell crate along normal (non-dev, non-build) edges.
const meta = JSON.parse(execFileSync('cargo', ['metadata', '--format-version', '1', '--locked',
  '--manifest-path', path.join(app, 'src-tauri/Cargo.toml'), '--filter-platform', triple],
  { maxBuffer: 256 * 1024 * 1024 }).toString());
const byId = new Map(meta.packages.map((p) => [p.id, p]));
const nodes = new Map(meta.resolve.nodes.map((n) => [n.id, n]));
const seen = new Set();
const todo = [meta.resolve.root];
while (todo.length) {
  const id = todo.pop();
  if (seen.has(id)) continue;
  seen.add(id);
  for (const d of nodes.get(id).deps) {
    if (d.dep_kinds.some((k) => k.kind === null)) todo.push(d.pkg);
  }
}
seen.delete(meta.resolve.root);
for (const id of seen) {
  const p = byId.get(id);
  packages.push({ name: `${p.name} (crate)`, version: p.version, license: p.license ?? p.license_file ?? 'unknown',
    texts: licenceFiles(path.dirname(p.manifest_path)) });
}

// npm: production dependencies from the lock file.
const lock = JSON.parse(fs.readFileSync(path.join(app, 'package-lock.json'), 'utf8'));
for (const [key, p] of Object.entries(lock.packages)) {
  if (!key.startsWith('node_modules/') || p.dev) continue;
  const dir = path.join(app, key);
  if (!fs.existsSync(dir)) continue; // optional dependency for another platform
  const name = key.slice(key.lastIndexOf('node_modules/') + 'node_modules/'.length);
  const license = p.license ?? JSON.parse(fs.readFileSync(path.join(dir, 'package.json'), 'utf8')).license;
  packages.push({ name: `${name} (npm)`, version: p.version, license: license ?? 'unknown', texts: licenceFiles(dir) });
}

packages.sort((a, b) => a.name.localeCompare(b.name) || a.version.localeCompare(b.version));
const groups = new Map(); // licence text -> packages
const bare = [];
for (const p of packages) {
  if (!p.texts.length) { bare.push(p); continue; }
  const text = p.texts.join('\n\n');
  if (!groups.has(text)) groups.set(text, []);
  groups.get(text).push(p);
}

const line = '='.repeat(78);
const label = (p) => `${p.name} ${p.version} (${p.license})`;
let s = 'CPI Workbench desktop app: third-party licences\n\n'
  + 'The Rust crates of the desktop shell and the npm packages of the UI, with the licence texts\n'
  + 'their packages contain. The engines\' components are listed in README.md in this folder.\n';
for (const [text, ps] of groups) s += `\n${line}\n${ps.map(label).join('\n')}\n${line}\n\n${text}\n`;
if (bare.length) {
  s += `\n${line}\nPackages without a licence file; their licence is the SPDX expression shown\n`
    + `(the texts of these licences appear above or in Apache-2.0.txt)\n${line}\n\n${bare.map(label).join('\n')}\n`;
}
fs.mkdirSync(path.dirname(out), { recursive: true });
fs.writeFileSync(out, s);
console.log(`${out}: ${packages.length} packages, ${groups.size} licence texts, ${bare.length} without a file`);
