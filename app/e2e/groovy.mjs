// Browser test of the Groovy view in web mode with three engines:
//   cpiwb-xml-he99 serve --http 7777 --ui app/dist
//   java -jar cpiwb-groovy-v1.jar serve --http 7778
//   java -jar cpiwb-groovy-v2.jar serve --http 7779
//   node app/e2e/groovy.mjs <projectDir> [screenshotDir]
import { createRequire } from 'node:module';
import path from 'node:path';

const { chromium } = createRequire(import.meta.url)('playwright');
const project = path.resolve(process.argv[2] ?? 'examples/demo-project');
const shots = process.argv[3];
const url = (process.env.CPIWB_URL ?? 'http://127.0.0.1:7777/')
  + '?engines=groovy-1.x@http://127.0.0.1:7778,groovy-2.x@http://127.0.0.1:7779';
const fail = (m) => { console.error('FAIL: ' + m); process.exitCode = 1; };

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1440, height: 860 } });
const errors = [];
page.on('pageerror', (e) => errors.push(e.message));
await page.goto(url);
await page.evaluate(() => localStorage.clear());
await page.reload();
await page.locator('.status', { hasText: 'engine(s) available' }).waitFor({ timeout: 20000 });

// 1) Sample script on Groovy 1.x.
await page.getByRole('tab', { name: 'Groovy Script' }).click();
await page.locator('section.output .badge.ok').waitFor({ timeout: 20000 });
const body = await page.locator('section.output .view-lines').innerText();
if (!body.includes('ProcessedOrder')) fail('groovy output: ' + body.slice(0, 200));
await page.getByRole('tab', { name: /^MPL/ }).click();
if (!(await page.locator('section.output .pad').innerText()).includes('Order')) fail('MPL property missing');
await page.getByRole('tab', { name: /^Console/ }).click();
if (!(await page.locator('section.output .console').innerText()).includes('Processing order 4711')) fail('console output missing');
if (shots) await page.screenshot({ path: path.join(shots, 'g1-groovy-v1.png') });

// 2) Switch to 2.x: the untouched sample switches to the v2 API and still works.
await page.getByLabel('Runtime profile').selectOption('groovy-2.x');
await page.waitForTimeout(500);
await page.locator('section.output .badge.ok').waitFor({ timeout: 20000 });
const script = await page.locator('section.script .view-lines').innerText();
if (!script.includes('com.sap.it.script.v2.api.Message')) fail('v2 sample not loaded');
await page.locator('section.output footer', { hasText: 'Groovy 4.0.29' }).waitFor({ timeout: 10000 });

// 3) A runtime error is reported with its script line.
await page.locator('section.script .view-lines').click();
await page.keyboard.press('Control+a');
await page.keyboard.insertText("import com.sap.it.script.v2.api.Message\ndef Message processData(Message message) {\n    def items = null\n    message.setProperty('n', items.size())\n    return message\n}\n");
await page.locator('section.output .badge.bad').waitFor({ timeout: 20000 });
await page.locator('section.output').getByRole('tab', { name: /^Body/ }).click();
const diag = await page.locator('section.output .diag.error').first().innerText();
if (!diag.includes('line 4') || !diag.includes('NullPointerException')) fail('runtime error: ' + diag);
if (shots) await page.screenshot({ path: path.join(shots, 'g2-groovy-error.png') });

// 4) Open the demo project and run the Groovy test case.
await page.getByLabel('Project folder').fill(project);
await page.getByRole('button', { name: 'Open' }).click();
await page.locator('.tree .file', { hasText: 'groovy-enrich-order/case.json' }).click();
await page.locator('section.output .badge.ok').waitFor({ timeout: 20000 });
await page.getByRole('tab', { name: /^API calls/ }).click();
const calls = await page.locator('section.output .pad').innerText();
if (!calls.includes('getMappedValue') || !calls.includes('getUserCredential')) fail('api calls: ' + calls);
if (shots) await page.screenshot({ path: path.join(shots, 'g3-groovy-case.png') });

// 4a) Compare runtimes: the 1.x demo script uses XmlParser without import, which Groovy 4 rejects.
await page.getByRole('button', { name: 'Compare', exact: true }).click();
await page.locator('section.output .compare .side .badge.bad').waitFor({ timeout: 20000 });
const cmp = await page.locator('section.output .compare').innerText();
if (!cmp.includes('Groovy Script 2.x') || !cmp.includes('XmlParser')) fail('compare: ' + cmp.slice(0, 400));
if (shots) await page.screenshot({ path: path.join(shots, 'g3b-compare.png') });
await page.getByRole('button', { name: 'Comparing' }).click();

// 4a2) A case with an attachment file: the app keeps it while the case is open.
await page.locator('.tree .file', { hasText: 'groovy-attachment/case.json' }).click();
await page.locator('section.output .badge.ok').waitFor({ timeout: 20000 });
const extras = await page.locator('.extras').innerText();
if (!extras.includes('1 attachment')) fail('case extras: ' + extras);
await page.locator('section.output').getByRole('tab', { name: /^Headers/ }).click();
const outHeaders = await page.locator('section.output').innerText();
if (!outHeaders.includes('Delivery DN-4711')) fail('attachment header: ' + outHeaders.slice(0, 400));

// 4b) Import an exported iFlow (zip) and open its Groovy step.
if (process.env.CPIWB_IFLOW_ZIP) {
  await page.getByRole('button', { name: 'Import iFlow' }).click();
  await page.locator('.dlg input.mono').fill(process.env.CPIWB_IFLOW_ZIP);
  await page.locator('.dlg').getByRole('button', { name: 'OK' }).click();
  await page.locator('.steps .step', { hasText: 'Enrich order' }).click();
  await page.locator('section.script h2', { hasText: 'enrich.groovy' }).waitFor({ timeout: 10000 });
  const fn = await page.getByLabel('Function name').inputValue();
  if (fn !== 'enrich') fail('function from iFlow step: ' + fn);
  const prof = await page.getByLabel('Runtime profile').inputValue();
  if (prof !== 'groovy-2.x') fail('profile from iFlow step: ' + prof);
  if (shots) await page.screenshot({ path: path.join(shots, 'g5-iflow.png') });
}

// 5) All tests across all engines.
await page.getByRole('tab', { name: 'Tests' }).click();
await page.getByRole('button', { name: 'Run all tests' }).click();
await page.locator('.sum').waitFor({ timeout: 30000 });
const sum = await page.locator('.sum').innerText();
if (!/6 passed/.test(sum) || !/0 failed/.test(sum)) fail('test summary: ' + sum);
if (shots) await page.screenshot({ path: path.join(shots, 'g4-tests.png') });

if (errors.length) fail('browser errors: ' + errors.join(' | '));
await browser.close();
console.log(process.exitCode ? 'groovy e2e FAILED' : 'groovy e2e passed');
