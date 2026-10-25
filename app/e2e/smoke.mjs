// End-to-end smoke test of the UI against a running engine in web mode:
//   cpiwb-xml serve --http 7777 --ui app/dist   then   node app/e2e/smoke.mjs <projectDir> [screenshotDir]
import { createRequire } from 'node:module';
import fs from 'node:fs';
import path from 'node:path';

const project = path.resolve(process.argv[2] ?? 'examples/demo-project');
const shots = process.argv[3];
// A Saxon-HE 12 engine on 7780 is optional; with it the runtime comparison is tested too.
const url = (process.env.CPIWB_URL ?? 'http://127.0.0.1:7777/') + '?engines=saxon-he-12@http://127.0.0.1:7780';
// Resolve playwright via require so a globally installed copy (NODE_PATH) works too.
const { chromium } = createRequire(import.meta.url)('playwright');
const fail = (m) => { console.error('FAIL: ' + m); process.exitCode = 1; };

const browser = await chromium.launch();
const page = await browser.newPage({ viewport: { width: 1440, height: 860 } });
const errors = [];
page.on('pageerror', (e) => errors.push(e.message));
page.on('console', (m) => m.type() === 'error' && errors.push(m.text()));
await page.goto(url);
await page.evaluate(() => localStorage.clear());
await page.reload();

// 1) Scratch mode: the sample mapping runs on load.
await page.locator('.badge.ok').first().waitFor({ timeout: 15000 });
const out = await page.locator('section.output .view-lines').innerText();
if (!out.includes('SalesOrder')) fail('sample output missing SalesOrder: ' + out.slice(0, 200));
await page.getByRole('tab', { name: /^Headers/ }).nth(1).click();
if (!(await page.locator('section.output tr.added').first().innerText()).includes('OrderNumber')) fail('OrderNumber header not marked as added');
if (shots) await page.screenshot({ path: path.join(shots, '1-xslt-scratch.png') });

// 1b) Compare runtimes (only when the second XSLT engine is running).
const compare = page.getByRole('button', { name: 'Compare', exact: true });
if (await compare.isEnabled()) {
  await compare.click();
  await page.locator('section.output .compare .same').waitFor({ timeout: 15000 });
  if (shots) await page.screenshot({ path: path.join(shots, '1b-xslt-compare.png') });
  await page.getByRole('button', { name: 'Comparing' }).click();
} else {
  console.log('note: saxon-he-12 engine not running, comparison not tested');
}

// 2) A compile error shows up with its line.
await page.locator('section.mapping .view-lines').click({ position: { x: 40, y: 10 } }); // the element is as wide as the longest line
await page.keyboard.press('Control+End');
await page.keyboard.type('<broken');
await page.locator('.badge.bad').waitFor({ timeout: 10000 });
await page.getByRole('tab', { name: /^Body/ }).nth(1).click();
if (!(await page.locator('.diag.error').first().innerText()).match(/line \d+/)) fail('compile error without line');
if (shots) await page.screenshot({ path: path.join(shots, '2-xslt-error.png') });
await page.keyboard.press('Control+z');

// 3) Open the demo project and a mapping from the tree.
await page.getByLabel('Project folder').fill(project);
await page.getByRole('button', { name: 'Open' }).click();
await page.locator('.tree .file').first().waitFor({ timeout: 10000 });
await page.locator('.tree .file', { hasText: 'order-to-salesorder.xsl' }).click();
await page.locator('section.mapping h2', { hasText: 'order-to-salesorder.xsl' }).waitFor();

// 4) Open a test case: loads input, headers and runs.
await page.locator('.tree .file', { hasText: 'standard-order/case.json' }).click();
await page.locator('.badge.ok').waitFor({ timeout: 10000 });
await page.getByRole('tab', { name: /^Parameters/ }).click();
const params = await page.locator('.params table').innerText();
if (!params.includes('SenderSystem') || !params.includes('header')) fail('parameter binding table: ' + params);
if (shots) await page.screenshot({ path: path.join(shots, '3-xslt-case.png') });

// 5) Run all tests.
await page.getByRole('tab', { name: 'Tests' }).click();
await page.getByRole('button', { name: 'Run all tests' }).click();
await page.locator('.sum').waitFor({ timeout: 15000 });
const sum = await page.locator('.sum').innerText();
if (!/\d+ passed/.test(sum) || !/0 failed/.test(sum)) fail('test summary: ' + sum);
if (shots) await page.screenshot({ path: path.join(shots, '4-tests.png') });

// 6) XSD validation of an invalid document.
await page.getByRole('tab', { name: 'XSD Validation' }).click();
await page.getByRole('tab', { name: 'XSLT Mapping' }).click();
await page.locator('.tree .file', { hasText: 'salesorder.xsd' }).click();
await page.getByRole('button', { name: 'Use XSLT output' }).click();
await page.getByRole('button', { name: 'Validate' }).click();
await page.locator('.verdict').waitFor({ timeout: 10000 });
const verdict = await page.locator('.verdict').innerText();
if (!verdict.includes('Valid')) fail('xsd verdict: ' + verdict);
if (shots) await page.screenshot({ path: path.join(shots, '5-xsd.png') });

// 7) XPath.
await page.getByRole('tab', { name: 'XPath' }).click();
await page.getByLabel('XPath expression').fill('count(//Item)');
await page.getByRole('button', { name: 'Evaluate' }).click();
await page.locator('.res .item').first().waitFor({ timeout: 10000 });
if (shots) await page.screenshot({ path: path.join(shots, '6-xpath.png') });

if (errors.length) fail('browser errors: ' + errors.join(' | '));
await browser.close();
console.log(process.exitCode ? 'smoke test FAILED' : 'smoke test passed');
