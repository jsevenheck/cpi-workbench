// Stages the engines for `tauri build`:
//   * native XSLT engines -> src-tauri/binaries/<name>-<target-triple>[.exe]  (Tauri externalBin)
//   * Groovy engine jars  -> src-tauri/resources/engines/                    (Tauri resources)
//   * Java runtime for Groovy (optional, from CPIWB_JRE_DIR) -> src-tauri/resources/jre/
// Without a bundled JRE the app uses JAVA_HOME / java on PATH (Java 17+) for Groovy.
//   node scripts/prepare-sidecars.mjs [engineOutDir]
import { execSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const repo = path.resolve(here, '../..');
const outDir = path.resolve(process.argv[2] ?? path.join(repo, 'engine/native/out'));
const tauri = path.join(here, '../src-tauri');
const triple = process.env.TAURI_TARGET_TRIPLE
  ?? execSync('rustc -vV').toString().match(/host: (\S+)/)[1];
const ext = triple.includes('windows') ? '.exe' : '';

const binaries = path.join(tauri, 'binaries');
fs.mkdirSync(binaries, { recursive: true });
for (const name of ['cpiwb-xml-he99', 'cpiwb-xml-he12']) {
  const src = path.join(outDir, name + ext);
  if (!fs.existsSync(src)) {
    console.error(`missing ${src} - build the engines first (engine/native/build-native.sh)`);
    process.exit(1);
  }
  const dst = path.join(binaries, `${name}-${triple}${ext}`);
  fs.copyFileSync(src, dst);
  fs.chmodSync(dst, 0o755);
  console.log(`${src} -> ${dst}`);
}

const engines = path.join(tauri, 'resources/engines');
fs.mkdirSync(engines, { recursive: true });
for (const v of ['v1', 'v2']) {
  const src = path.join(repo, `engine/groovy-${v}/target/cpiwb-groovy-${v}.jar`);
  if (!fs.existsSync(src)) {
    console.error(`missing ${src} - run: mvn -f engine/pom.xml -DskipTests package`);
    process.exit(1);
  }
  fs.copyFileSync(src, path.join(engines, path.basename(src)));
  console.log(`${src} -> ${engines}`);
}

const jreTarget = path.join(tauri, 'resources/jre');
fs.rmSync(jreTarget, { recursive: true, force: true });
fs.mkdirSync(jreTarget, { recursive: true });
if (process.env.CPIWB_JRE_DIR) {
  fs.cpSync(process.env.CPIWB_JRE_DIR, jreTarget, { recursive: true });
  console.log(`${process.env.CPIWB_JRE_DIR} -> ${jreTarget}`);
} else {
  fs.writeFileSync(path.join(jreTarget, 'README.txt'),
    'No Java runtime bundled. Groovy engines use JAVA_HOME or java on PATH (Java 17+).\n');
  console.log('no CPIWB_JRE_DIR set: Groovy will use the system Java');
}
