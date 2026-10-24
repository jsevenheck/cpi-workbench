// Copies the native engines into src-tauri/binaries/<name>-<target-triple>[.exe], where Tauri's
// bundler expects external binaries. Run after engine/native/build-native.sh.
//   node scripts/prepare-sidecars.mjs [engineOutDir]
import { execSync } from 'node:child_process';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const outDir = path.resolve(process.argv[2] ?? path.join(here, '../../engine/native/out'));
const target = path.join(here, '../src-tauri/binaries');
const triple = process.env.TAURI_TARGET_TRIPLE
  ?? execSync('rustc -vV').toString().match(/host: (\S+)/)[1];
const ext = triple.includes('windows') ? '.exe' : '';
fs.mkdirSync(target, { recursive: true });
for (const name of ['cpiwb-xml-he99', 'cpiwb-xml-he12']) {
  const src = path.join(outDir, name + ext);
  if (!fs.existsSync(src)) {
    console.error(`missing ${src} - build the engines first (engine/native/build-native.sh)`);
    process.exit(1);
  }
  const dst = path.join(target, `${name}-${triple}${ext}`);
  fs.copyFileSync(src, dst);
  fs.chmodSync(dst, 0o755);
  console.log(`${src} -> ${dst}`);
}
