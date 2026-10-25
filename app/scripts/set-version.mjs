// Sets the version everywhere it is recorded: node app/scripts/set-version.mjs 0.2.0
// App: package.json, package-lock.json, tauri.conf.json, Cargo.toml, Cargo.lock.
// Engines: the Maven poms and XmlEngine.VERSION / GroovyEngine.VERSION (reported by the engines' `describe`).
// The release tag is v<version>; the release job checks that app and engines agree with it.
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

// SemVer 2.0.0 without build metadata (semver.org): no leading zeros, dot-separated pre-release.
const SEMVER = /^(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)(-(0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*)(\.(0|[1-9]\d*|\d*[A-Za-z-][0-9A-Za-z-]*))*)?$/;
const version = process.argv[2];
if (!SEMVER.test(version ?? '')) {
  console.error('usage: node app/scripts/set-version.mjs <major.minor.patch[-pre]> (SemVer, e.g. 0.2.0 or 0.2.0-rc.1)');
  process.exit(1);
}
const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../..');

/** Replaces the version that each pattern's second group holds; fails if a pattern finds nothing. */
const edit = (rel, ...patterns) => {
  const file = path.join(repo, rel);
  let s = fs.readFileSync(file, 'utf8');
  for (const re of patterns) {
    if (!re.test(s)) throw new Error(`${rel}: version not found (${re})`);
    s = s.replace(re, (_, pre, _old, post) => pre + version + post);
  }
  fs.writeFileSync(file, s);
  console.log(`${rel}: ${version}`);
};

edit('app/package.json', /("version":\s*")([^"]+)(")/);
// The lock file names the root package twice: at the top and under packages[""].
edit('app/package-lock.json', /^(\{\s*"name":\s*"[^"]+",\s*"version":\s*")([^"]+)(")/,
  /("packages":\s*\{\s*"":\s*\{\s*"name":\s*"[^"]+",\s*"version":\s*")([^"]+)(")/);
edit('app/src-tauri/tauri.conf.json', /("version":\s*")([^"]+)(")/);
edit('app/src-tauri/Cargo.toml', /(\[package\][^[]*?\r?\nversion\s*=\s*")([^"]+)(")/);
edit('app/src-tauri/Cargo.lock', /(\[\[package\]\]\r?\nname = "cpi-workbench"\r?\nversion = ")([^"]+)(")/);

// The parent pom's own version, and the <parent> version in every module.
edit('engine/pom.xml', /(<artifactId>cpiwb-engine-parent<\/artifactId>\s*<version>)([^<]+)(<\/version>)/);
for (const m of ['core', 'xml-he99', 'xml-he12', 'groovy-v1', 'groovy-v2']) {
  edit(`engine/${m}/pom.xml`, /(<artifactId>cpiwb-engine-parent<\/artifactId>\s*<version>)([^<]+)(<\/version>)/);
}
edit('engine/xml/src/main/java/io/cpiwb/xml/XmlEngine.java', /(String VERSION = ")([^"]+)(")/);
edit('engine/groovy/src/main/java/io/cpiwb/groovy/GroovyEngine.java', /(String VERSION = ")([^"]+)(")/);
