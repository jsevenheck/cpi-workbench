// Sets the app version everywhere the installers read it: node app/scripts/set-version.mjs 0.2.0
// (package.json, package-lock.json, tauri.conf.json, Cargo.toml, Cargo.lock). The release tag is v<version>.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const version = process.argv[2];
if (!/^\d+\.\d+\.\d+(-[0-9A-Za-z.-]+)?$/.test(version ?? '')) {
  console.error('usage: node app/scripts/set-version.mjs <major.minor.patch[-pre]>');
  process.exit(1);
}
const app = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');

/** Replaces the version that each pattern's second group holds; fails if a pattern finds nothing. */
const edit = (rel, ...patterns) => {
  const file = path.join(app, rel);
  let s = fs.readFileSync(file, 'utf8');
  for (const re of patterns) {
    if (!re.test(s)) throw new Error(`${rel}: version not found (${re})`);
    s = s.replace(re, (_, pre, _old, post) => pre + version + post);
  }
  fs.writeFileSync(file, s);
  console.log(`${rel}: ${version}`);
};

edit('package.json', /("version":\s*")([^"]+)(")/);
// The lock file names the root package twice: at the top and under packages[""].
edit('package-lock.json', /^(\{\s*"name":\s*"[^"]+",\s*"version":\s*")([^"]+)(")/,
  /("packages":\s*\{\s*"":\s*\{\s*"name":\s*"[^"]+",\s*"version":\s*")([^"]+)(")/);
edit('src-tauri/tauri.conf.json', /("version":\s*")([^"]+)(")/);
edit('src-tauri/Cargo.toml', /(\[package\][^[]*?\r?\nversion\s*=\s*")([^"]+)(")/);
edit('src-tauri/Cargo.lock', /(\[\[package\]\]\r?\nname = "cpi-workbench"\r?\nversion = ")([^"]+)(")/);
