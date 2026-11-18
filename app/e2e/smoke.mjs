// End-to-end smoke test of the UI against a running engine in web mode:
//   cpiwb-xml serve --http 7777 --ui app/dist   then   node app/e2e/smoke.mjs <projectDir> [screenshotDir]
import { createRequire } from 'node:module';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

const project = path.resolve(process.argv[2] ?? 'testdata/regression-project');
const shots = process.argv[3];
// A Saxon-HE 12 engine on 7780 is optional; with it the runtime comparison is tested too.
const url = (process.env.CPIWB_URL ?? 'http://127.0.0.1:7777/') + '?engines=saxon-he-12@http://127.0.0.1:7780';
// Resolve playwright via require so a globally installed copy (NODE_PATH) works too.
const { chromium } = createRequire(import.meta.url)('playwright');
const fail = (m) => { console.error('FAIL: ' + m); process.exitCode = 1; };

const browser = await chromium.launch();
const context = await browser.newContext({ viewport: { width: 1440, height: 860 } });
const page = await context.newPage();
const errors = [];
page.on('pageerror', (e) => errors.push(e.message));
page.on('console', (m) => m.type() === 'error' && errors.push(m.text()));
await page.goto(url);
await page.evaluate(() => localStorage.clear());
await page.reload();

// 1) Without a project the editors start empty, point to the examples, and nothing runs.
await page.locator('.status', { hasText: 'engine(s) available' }).waitFor({ timeout: 15000 });
await page.locator('section.mapping .empty-hint', { hasText: 'Examples' }).waitFor({ timeout: 5000 })
  .catch(() => fail('the empty mapping editor does not point to the examples'));
if ((await page.locator('section.mapping .view-lines').innerText()).trim()) fail('the mapping editor is not empty at start');
if ((await page.locator('section.pane .view-lines').first().innerText()).trim()) fail('the input body is not empty at start');
await page.waitForTimeout(1000);
if (await page.locator('section.output .badge').count()) fail('something ran on an empty start');
if (shots) await page.screenshot({ path: path.join(shots, '1-xslt-empty.png') });

// 1-) The Fidelity dialog is modal for the keyboard: focus moves in, Tab stays in, Escape closes and gives focus back.
const fid = page.getByRole('button', { name: /^Fidelity of XSLT Mapping/ });
await fid.focus();
await page.keyboard.press('Enter');
const fidDialog = page.getByRole('dialog', { name: 'Fidelity of XSLT Mapping' });
await fidDialog.waitFor({ timeout: 5000 });
const focusedText = () => page.evaluate(() => document.activeElement?.textContent?.trim() ?? '');
await page.waitForFunction(() => document.activeElement?.closest('[role="dialog"]'), null, { timeout: 5000 })
  .catch(() => fail('opening the Fidelity dialog did not move focus into it'));
await page.keyboard.press('Tab');
await page.keyboard.press('Shift+Tab');
if ((await focusedText()) !== 'Close') fail('Tab left the Fidelity dialog: ' + (await focusedText()));
await page.keyboard.press('Escape');
await fidDialog.waitFor({ state: 'detached', timeout: 5000 }).catch(() => fail('Escape did not close the Fidelity dialog'));
if (!(await fid.evaluate((b) => b === document.activeElement))) fail('closing the Fidelity dialog did not return focus to its button');

// Shared input dialog: both tab boundaries wrap; Escape and Cancel restore focus.
const importer = page.getByRole('button', { name: 'Import…', exact: true }).first();
await importer.focus();
for (const close of ['Escape', 'Cancel']) {
  await page.keyboard.press('Enter');
  const modal = page.locator('dialog.dlg');
  await modal.waitFor();
  const focusIs = (locator) => locator.evaluate((el) => el === document.activeElement);
  if (!(await focusIs(modal.locator('input.mono')))) fail('Import dialog did not focus its input');
  await page.keyboard.press('Shift+Tab');
  if (!(await focusIs(modal.getByRole('button', { name: 'OK' })))) fail('Shift+Tab escaped the dialog');
  await page.keyboard.press('Tab');
  if (!(await focusIs(modal.locator('input.mono')))) fail('Tab escaped the dialog');
  if (close === 'Escape') await page.keyboard.press('Escape');
  else await modal.getByRole('button', { name: 'Cancel' }).click();
  await modal.waitFor({ state: 'detached' });
  if (!(await focusIs(importer))) fail(`${close} did not return focus to Import…`);
}

// With only XML engines, Flow Steps can still run the Encoder and explain a missing Camel engine.
await page.getByRole('tab', { name: 'Flow Steps', exact: true }).click();
await page.getByRole('button', { name: 'Run step' }).click();
await page.getByRole('alert').filter({ hasText: 'requires a Groovy engine (Camel)' }).waitFor({ timeout: 5000 });
await page.getByRole('tab', { name: 'Encoder', exact: true }).click();
const flowBody = page.locator('section[aria-label="Input message"] .view-lines');
await flowBody.click({ position: { x: 20, y: 10 } });
await page.keyboard.insertText('<a/>');
await page.getByRole('button', { name: 'Run step' }).click();
await page.locator('section[aria-label="Step result"] pre', { hasText: 'PGEvPg==' }).waitFor({ timeout: 5000 });
await page.getByRole('tab', { name: 'XSLT Mapping', exact: true }).click();

// 1a) A bundled example loads without a project and runs.
await page.getByLabel('Examples').selectOption('xslt-batch');
await page.locator('section.output .view-lines', { hasText: 'SalesOrderBatch' }).waitFor({ timeout: 15000 })
  .catch(async () => fail('the example did not run: ' + (await page.locator('section.output').innerText()).slice(0, 300)));

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
// 2a) History: the example's run and the failed one; the failed one is compared with the example's.
await page.getByRole('tab', { name: /^History/ }).click();
const historyRows = await page.locator('section.output .history tbody tr').count();
if (historyRows < 2) fail('History lists ' + historyRows + ' runs, expected the example and the failed run');
await page.getByRole('button', { name: 'Compare with the previous run' }).click();
await page.locator('section.output .history .compare').waitFor({ timeout: 5000 })
  .catch(async () => fail('no comparison with the previous run: ' + (await page.locator('section.output').innerText()).slice(0, 300)));
if (shots) await page.screenshot({ path: path.join(shots, '1c-history.png') });
await page.getByRole('tab', { name: /^Body/ }).nth(1).click();

// 3) Open the demo project and a mapping from the tree.
await page.getByLabel('Project folder').fill(project);
await page.getByRole('button', { name: 'Open' }).click();
await page.locator('.tree .file').first().waitFor({ timeout: 10000 });
// The scratch mapping still has the broken edit: opening a file asks first, and Cancel keeps the edit.
const mappingFile = page.locator('.tree .file', { hasText: 'order-to-salesorder.xsl' });
const discard = page.getByRole('dialog').filter({ hasText: 'Discard the unsaved changes' });
await mappingFile.click();
await discard.waitFor({ timeout: 5000 }).catch(() => fail('no question before an unsaved mapping is replaced'));
// A destructive question opens on Cancel, so Enter does not discard.
const cancelFocused = (dlg) => dlg.getByRole('button', { name: 'Cancel', exact: true }).evaluate((b) => b === document.activeElement);
if (!(await cancelFocused(discard))) fail('the discard question did not open with Cancel focused');
await discard.getByRole('button', { name: 'Cancel' }).click();
if (!(await page.locator('section.mapping .view-lines').innerText()).includes('<broken')) fail('Cancel did not keep the unsaved mapping');
await mappingFile.click();
await discard.getByRole('button', { name: 'OK' }).click();
// The title holds the bare path; the visible text may end with the unsaved-changes dot.
await page.locator('section.mapping h2[title="mappings/order-to-salesorder.xsl"]').waitFor();

// 2b) Save as… suggests a free name next to the open file and leaves the open file as it is.
const xslBefore = fs.readFileSync(path.join(project, 'mappings/order-to-salesorder.xsl'), 'utf8');
await page.getByRole('button', { name: 'Save as…' }).click();
const suggested = await page.getByRole('dialog').getByRole('textbox').inputValue();
if (suggested !== 'mappings/order-to-salesorder-copy.xsl') fail('Save as suggestion: ' + suggested);
await page.getByRole('dialog').getByRole('button', { name: 'OK' }).click();
await page.locator('section.mapping h2', { hasText: 'order-to-salesorder-copy.xsl' }).waitFor({ timeout: 10000 });
if (fs.readFileSync(path.join(project, 'mappings/order-to-salesorder-copy.xsl'), 'utf8') !== xslBefore) fail('Save as wrote different content');
if (fs.readFileSync(path.join(project, 'mappings/order-to-salesorder.xsl'), 'utf8') !== xslBefore) fail('Save as changed the open file');
// The tree reloads after the save and the copy takes the original's row; a click before that lands on the copy.
await page.locator('.tree .file', { hasText: 'order-to-salesorder-copy.xsl' }).waitFor();
await mappingFile.click();
await page.locator('section.mapping h2[title="mappings/order-to-salesorder.xsl"]').waitFor();

// Hold only the acknowledgement, after a real write to the disposable project.
async function holdSave(destination) {
  const written = Promise.withResolvers();
  const release = Promise.withResolvers();
  const handler = async (route) => {
    const req = route.request().postDataJSON();
    if (req?.method !== 'file.write' || req.params.path !== destination) return route.fallback();
    const response = await route.fetch();
    const envelope = await response.json();
    if (!envelope.result?.saved) {
      written.reject(new Error(`file.write failed: ${JSON.stringify(envelope)}`));
      return route.fulfill({ response });
    }
    written.resolve(req.params.content);
    await release.promise;
    await route.fulfill({ response });
  };
  await page.route('**/rpc', handler);
  return {
    written: written.promise,
    async release() {
      release.resolve();
      await page.unrouteAll({ behavior: 'wait' });
    },
  };
}

// 3a) Edits made while Save waits must remain dirty, and closing must ask before losing them.
const mappingPath = 'mappings/order-to-salesorder.xsl';
const mappingPane = page.locator('section.mapping');
const mappingSave = mappingPane.getByRole('button', { name: 'Save', exact: true });
const mappingClose = mappingPane.getByRole('button', { name: 'Close file', exact: true });
const mappingDirty = mappingPane.getByTitle('Unsaved changes');
async function appendMapping(text) {
  await mappingPane.getByRole('textbox', { name: 'XSLT mapping', exact: true }).focus();
  await page.keyboard.press('Control+End');
  await page.keyboard.insertText(text);
}
await appendMapping('\n<!-- before save -->');
await mappingDirty.waitFor({ timeout: 5000 });
const savePending = await holdSave(mappingPath);
await mappingSave.click();
const savedText = await savePending.written;
await appendMapping('\n<!-- after save snapshot -->');
await savePending.release();
await mappingPane.locator('button:not([disabled])', { hasText: /^Save$/ }).waitFor({ timeout: 5000 });
const onDisk = fs.readFileSync(path.join(project, mappingPath), 'utf8');
if (onDisk !== savedText || !onDisk.includes('before save') || onDisk.includes('after save snapshot')) fail('Save did not write the snapshot');
if (!(await mappingDirty.isVisible())) fail('the edit made while Save waited was marked saved');
await mappingClose.click();
await discard.getByRole('button', { name: 'Cancel' }).click({ timeout: 5000 });
await mappingSave.click();
await mappingDirty.waitFor({ state: 'hidden', timeout: 5000 });
if (!fs.readFileSync(path.join(project, mappingPath), 'utf8').includes('after save snapshot')) fail('the next Save did not write the later edit');

// 3b) Save As may finish after the original path is closed and reopened. It must not retarget that editor.
const staleCopy = 'mappings/save-ack-copy.xsl';
const copyPending = await holdSave(staleCopy);
await mappingPane.getByRole('button', { name: 'Save as…', exact: true }).click();
await page.getByRole('dialog').getByRole('textbox').fill(staleCopy);
await page.getByRole('dialog').getByRole('button', { name: 'OK' }).click();
const copiedText = await copyPending.written;
await mappingClose.click();
await mappingPane.getByRole('heading', { name: 'No mapping open' }).waitFor({ timeout: 5000 });
await mappingFile.click();
await mappingPane.locator(`h2[title="${mappingPath}"]`).waitFor({ timeout: 5000 });
await appendMapping('\n<!-- reopened editor -->');
await copyPending.release();
await mappingPane.locator('button:not([disabled])', { hasText: /^Save$/ }).waitFor({ timeout: 5000 });
if (fs.readFileSync(path.join(project, staleCopy), 'utf8') !== copiedText) fail('Save As did not write the snapshot');
if (!(await mappingPane.locator(`h2[title="${mappingPath}"]`).isVisible()) || !(await mappingDirty.isVisible())) {
  fail('the late Save As changed the path or cleared the dirty marker of the reopened document');
}
await mappingClose.click();
await discard.getByRole('button', { name: 'OK' }).click({ timeout: 5000 });

// 3c) Two tabs use the same engine (and compare engine if present) with the same relative names.
const scopedXsl = 'mappings/tab-scope.xsl';
const scopedData = 'mappings/tab-scope.xml';
const tabMapping = '<xsl:stylesheet xmlns:xsl="http://www.w3.org/1999/XSL/Transform" version="2.0">'
  + '<xsl:output method="text"/><xsl:template match="/">'
  + '<xsl:value-of select="doc(\'tab-scope.xml\')/value"/></xsl:template></xsl:stylesheet>';
const writeScopeData = (dir, value) => fs.writeFileSync(path.join(dir, scopedData), `<value>${value}</value>`);
const openScopeMapping = (tab) => tab.locator('.tree .file', { hasText: 'tab-scope.xsl' }).click();
const scopeBody = (tab, text) => tab.locator('section.output .view-lines', { hasText: text }).waitFor({ timeout: 10000 });
const fillScopeInput = async (tab) => {
  await tab.locator('.tree .file', { hasText: 'tab-scope-input.xml' }).click();
  await tab.locator('section.input h2', { hasText: 'tab-scope-input.xml' }).waitFor({ timeout: 5000 });
};
const scopeRun = (tab) => tab.getByTitle('Run (Ctrl+Enter)').click();
const scopeCompare = (tab) => tab.locator('section.output .compare .same').waitFor({ timeout: 10000 })
  .catch(async (e) => { throw new Error('Project scope comparison: ' + await tab.locator('section.output').innerText(), { cause: e }); });
const otherRoot = fs.mkdtempSync(path.join(os.tmpdir(), 'cpiwb-tab-'));
const otherTab = await context.newPage();
try {
  for (const [dir, value] of [[project, 'first-tab'], [otherRoot, 'second-tab']]) {
    fs.mkdirSync(path.join(dir, 'mappings'), { recursive: true });
    fs.mkdirSync(path.join(dir, 'inputs'), { recursive: true });
    fs.writeFileSync(path.join(dir, 'inputs/tab-scope-input.xml'), '<input/>');
    fs.writeFileSync(path.join(dir, scopedXsl), tabMapping);
    writeScopeData(dir, value);
  }
  await page.getByTitle('Reload file list').click();
  await openScopeMapping(page);
  await fillScopeInput(page);
  await scopeRun(page);
  await scopeBody(page, 'first-tab');
  const compareScope = await compare.isEnabled();
  if (compareScope) {
    await compare.click();
    await scopeCompare(page);
  }

  await otherTab.goto(url);
  // The new tab restores the last project from browser storage; changing it must not affect the first tab.
  await otherTab.getByTitle('Close project').click();
  await otherTab.getByLabel('Project folder').fill(otherRoot);
  await otherTab.getByRole('button', { name: 'Open', exact: true }).click();
  await openScopeMapping(otherTab);
  await fillScopeInput(otherTab);
  await scopeRun(otherTab);
  await scopeBody(otherTab, 'second-tab');
  if (compareScope) {
    await otherTab.getByRole('button', { name: 'Compare', exact: true }).click();
    await scopeCompare(otherTab);
  }

  writeScopeData(project, 'first-tab-updated');
  await scopeRun(page);
  if (compareScope) {
    await scopeCompare(page);
    await page.getByRole('button', { name: 'Comparing', exact: true }).click();
  }
  await scopeBody(page, 'first-tab-updated');
  await appendMapping('\n<!-- only the first tab saves -->');
  await mappingSave.click();
  await mappingDirty.waitFor({ state: 'hidden', timeout: 5000 });
  if (!fs.readFileSync(path.join(project, scopedXsl), 'utf8').includes('only the first tab saves')
      || fs.readFileSync(path.join(otherRoot, scopedXsl), 'utf8') !== tabMapping) fail('Save changed the wrong tab’s project');

  await otherTab.getByTitle('Close project').click();
  await otherTab.getByLabel('Project folder').waitFor({ timeout: 5000 });
  writeScopeData(project, 'first-tab-still-open');
  await scopeRun(page);
  await scopeBody(page, 'first-tab-still-open');
} finally {
  await otherTab.close();
  fs.rmSync(otherRoot, { recursive: true, force: true });
}

// 4) Open a test case: loads input, headers and runs.
await page.locator('.tree .file', { hasText: 'standard-order/case.json' }).click();
// Wait for the case's value (the case's run, not an earlier one); step 6 validates this run's output.
await page.getByRole('tab', { name: /^Parameters/ }).click();
await page.locator('.params tr', { hasText: 'WEBSHOP' }).waitFor({ timeout: 10000 })
  .catch(() => fail('SenderSystem=WEBSHOP missing from the parameter table'));
const params = await page.locator('.params table').innerText();
if (!params.includes('header')) fail('parameter binding table: ' + params);
// A header the mapping sets is marked as added.
await page.getByRole('tab', { name: /^Headers/ }).nth(1).click();
await page.locator('section.output tr.added', { hasText: 'SAP_ApplicationID' }).waitFor({ timeout: 5000 })
  .catch(() => fail('SAP_ApplicationID header not marked as added'));
if (shots) await page.screenshot({ path: path.join(shots, '3-xslt-case.png') });

// 6) XSD validation of an invalid document.
await page.getByRole('tab', { name: 'XSD Validation' }).click();
await page.getByRole('tab', { name: 'XSLT Mapping' }).click();
await page.locator('.tree .file', { hasText: 'salesorder.xsd' }).click();
await page.getByRole('button', { name: 'Use XSLT output' }).click();
await page.getByTitle('Run (Ctrl+Enter)').click();
await page.locator('.verdict').waitFor({ timeout: 10000 });
const verdict = await page.locator('.verdict').innerText();
if (!verdict.includes('Valid')) fail('xsd verdict: ' + verdict);
if (shots) await page.screenshot({ path: path.join(shots, '5-xsd.png') });

// 6b) An XSD test case opens in the XSD view and validates.
await page.locator('.tree .file', { hasText: 'invalid-salesorder/case.json' }).click();
await page.locator('.verdict', { hasText: 'Invalid' }).waitFor({ timeout: 10000 });
if (!(await page.locator('.result').innerText()).includes('Item')) fail('xsd case diagnostics lack Item');

// 6b) File tree context menu: copy, rename and delete a file.
const menuOn = async (name) => {
  await page.locator('.tree .file', { hasText: name }).first().click({ button: 'right' });
  return page.getByRole('menu');
};
const answer = async (value) => {
  if (value !== undefined) await page.getByRole('dialog').getByRole('textbox').fill(value);
  await page.getByRole('dialog').getByRole('button', { name: 'OK' }).click();
};
await (await menuOn('salesorder.xsd')).getByRole('menuitem', { name: 'Copy…' }).click();
await answer('schemas/copy.xsd');
await page.locator('.tree .file', { hasText: 'copy.xsd' }).waitFor({ timeout: 10000 });
await (await menuOn('copy.xsd')).getByRole('menuitem', { name: 'Rename…' }).click();
await answer('schemas/renamed.xsd');
await page.locator('.tree .file', { hasText: 'renamed.xsd' }).waitFor({ timeout: 10000 });
// The menu item that opened the dialog is gone: focus goes to the active tool tab, not to <body>.
if (!(await page.evaluate(() => document.activeElement?.matches('.tools [role="tab"].active, main')))) {
  fail('closing the Rename dialog left focus on ' + (await page.evaluate(() => document.activeElement?.outerHTML.slice(0, 80))));
}
if (fs.existsSync(path.join(project, 'schemas/copy.xsd'))) fail('rename left schemas/copy.xsd behind');
if (fs.readFileSync(path.join(project, 'schemas/renamed.xsd'), 'utf8') !== fs.readFileSync(path.join(project, 'schemas/salesorder.xsd'), 'utf8')) {
  fail('renamed copy differs from schemas/salesorder.xsd');
}
await (await menuOn('renamed.xsd')).getByRole('menuitem', { name: 'Delete…' }).click();
await answer();
await page.locator('.tree .file', { hasText: 'renamed.xsd' }).waitFor({ state: 'detached', timeout: 10000 });
if (fs.existsSync(path.join(project, 'schemas/renamed.xsd'))) fail('delete left schemas/renamed.xsd behind');

// 7) XPath.
await page.getByRole('tab', { name: 'XPath' }).click();
// The XPath tab starts empty too; the test case's input from the XSLT tab is the document.
await page.getByRole('button', { name: 'Use XSLT input' }).click();
await page.getByLabel('XPath expression').fill('count(//Item)');
await page.getByTitle('Run (Ctrl+Enter)').click();
await page.locator('.res .item').first().waitFor({ timeout: 10000 });
if (shots) await page.screenshot({ path: path.join(shots, '6-xpath.png') });
// Closing the input while a run is under way: its answer must not come back (review F09).
let holdXpath;
const xpathHeld = new Promise((r) => (holdXpath = r));
await page.route(/\/rpc$/, async (route) => {
  if (route.request().postData()?.includes('"xpath.eval"')) await xpathHeld;
  await route.continue().catch(() => {});
});
const bodyBefore = await page.evaluate(() => document.querySelector('section.pane .view-lines')?.textContent ?? '');
await page.getByTitle('Run (Ctrl+Enter)').click();
await page.waitForTimeout(300);
await page.locator('.tree .file', { hasText: 'standard-order/input.xml' }).click({ button: 'right' });
await page.getByRole('menu').getByRole('menuitem', { name: 'Use as input' }).click();
await page.locator('section.pane').first().getByRole('button', { name: 'Close input file' }).click();
holdXpath();
await page.waitForTimeout(1500);
await page.unroute(/\/rpc$/);
if (await page.locator('.res .item').count()) fail('the result of a run started before the input was closed came back');
if (!bodyBefore) fail('no document before the held run');
await page.getByRole('button', { name: 'Use XSLT input' }).click();

// The nodes an expression selects are marked in the document; a result opens its place.
await page.getByLabel('XPath expression').fill('//Item/@sku');
await page.getByLabel('XPath expression').press('Enter');
await page.locator('.res button.link', { hasText: 'attribute sku' }).nth(1).waitFor({ timeout: 10000 })
  .catch(async () => fail('XPath results do not link to their place: ' + await page.locator('.res').innerText()));
// Monaco draws decorations on a later frame: wait for them rather than count at once.
await page.waitForFunction(() => document.querySelectorAll('section.pane .cpiwb-match').length >= 2, null, { timeout: 5000 })
  .catch(async () => fail('the selected attributes are not marked in the document: ' + await page.locator('section.pane .cpiwb-match').count()));
// The XPath of the attribute at the cursor: this node and all like it; the choice goes into the field and runs.
await page.locator('.res button.link').nth(1).click();
await page.getByRole('button', { name: /XPath at cursor/ }).click();
const menu = await page.getByRole('menu', { name: 'XPath at cursor' }).innerText();
if (!/\/Item\[2\]\/@sku/.test(menu) || !/Item\/@sku/.test(menu)) fail('XPath at cursor: ' + menu);
await page.getByRole('menuitem').first().click();
const atCursor = await page.getByLabel('XPath expression').inputValue();
if (!/\/Item\[2\]\/@sku$/.test(atCursor)) fail('XPath at cursor did not fill the field: ' + atCursor);
await page.locator('.res .item', { hasText: /m-200/i }).waitFor({ timeout: 10000 }).catch(() => fail('the XPath at cursor did not run'));
// ↑ brings back the expression before it, ↓ the current one.
await page.getByLabel('XPath expression').press('ArrowUp');
if ((await page.getByLabel('XPath expression').inputValue()) !== '//Item/@sku') fail('↑ in the XPath field: ' + await page.getByLabel('XPath expression').inputValue());
await page.getByLabel('XPath expression').press('ArrowDown');
if ((await page.getByLabel('XPath expression').inputValue()) !== atCursor) fail('↓ in the XPath field');
// Edited after the run: nothing stays marked, and the result says why.
await page.locator('section.pane .view-lines').first().click({ position: { x: 40, y: 10 } });
await page.keyboard.press('Control+End');
await page.keyboard.type(' ');
await page.locator('.note', { hasText: 'document changed since this run' }).waitFor({ timeout: 5000 })
  .catch(() => fail('no note that the marks belong to the text before the edit'));
// Monaco draws decorations on the next animation frame: wait for the marks to go rather than count at once.
await page.locator('section.pane .cpiwb-match').first().waitFor({ state: 'detached', timeout: 5000 })
  .catch(() => fail('marks stayed after the document changed'));

// 8) Files that are not steps open in the File tab and leave the input alone; inputs/ holds saved inputs.
await page.getByRole('tab', { name: 'XSLT Mapping' }).click();
const inputEditor = page.locator('section.input .view-lines');
const inputBefore = await inputEditor.innerText();
await page.locator('.tree .file', { hasText: 'value-mappings.json' }).click();
await page.locator('section[aria-label="File"] h2', { hasText: 'platform/value-mappings.json' }).waitFor({ timeout: 10000 });
await page.getByRole('tab', { name: 'XSLT Mapping' }).click();
// The editor is created anew after the tab switch; wait until it has rendered.
let inputAfter = '';
for (let i = 0; i < 20 && inputAfter !== inputBefore; i++) {
  await page.waitForTimeout(250);
  inputAfter = await inputEditor.innerText();
}
if (inputAfter !== inputBefore) fail(`opening a platform file changed the input body: ${JSON.stringify(inputBefore.slice(0, 80))} -> ${JSON.stringify(inputAfter.slice(0, 80))}`);
await page.locator('section.input').getByRole('button', { name: 'Save…' }).click();
await answer('inputs/order.xml');
await page.locator('.tree .file', { hasText: 'order.xml' }).waitFor({ timeout: 10000 });
if (!fs.existsSync(path.join(project, 'inputs/order.xml'))) fail('input not saved to inputs/order.xml');
await (await menuOn('value-mappings.json')).getByRole('menuitem', { name: 'Use as input' }).click();
// Only XML engines run here: a JSON body has nowhere to go and must not replace the XSLT input.
await page.locator('.banner.error', { hasText: 'only a Groovy script takes a JSON body' }).waitFor({ timeout: 10000 })
  .catch(() => fail('"Use as input" with JSON and no Groovy engine gave no error'));
if ((await inputEditor.innerText()).includes('sourceAgency')) fail('JSON replaced the XSLT input');
await page.locator('.banner.error').getByRole('button', { name: 'Dismiss' }).click();
await (await menuOn('standard-order/input.xml')).getByRole('menuitem', { name: 'Use as input' }).click();
await page.locator('section.input h2', { hasText: '· input.xml' }).waitFor({ timeout: 10000 })
  .catch(() => fail('"Use as input" did not load standard-order/input.xml'));
await page.locator('.tree .file', { hasText: 'order.xml' }).click();
await page.locator('section.input .view-lines', { hasText: inputBefore.split('\n')[0].trim() }).waitFor({ timeout: 10000 })
  .catch(() => fail('clicking inputs/order.xml did not load it as input'));
// The XSD tab loads files from inputs/ into its own document and names the file.
await page.getByRole('tab', { name: 'XSD Validation' }).click();
await page.locator('.tree .file', { hasText: 'order.xml' }).click();
await page.locator('section h2', { hasText: '· order.xml' }).waitFor({ timeout: 10000 })
  .catch(() => fail('XSD tab does not show inputs/order.xml as its document'));
if (!(await page.locator('.tree .file', { hasText: 'order.xml' }).getAttribute('class')).includes('active')) fail('inputs/order.xml not marked in the tree');
// The XSLT output replaces the document, so the header no longer names the file.
await page.getByRole('button', { name: 'Use XSLT output' }).click();
await page.locator('section h2', { hasText: '· order.xml' }).waitFor({ state: 'detached', timeout: 5000 })
  .catch(() => fail('XSD header still names order.xml after Use XSLT output'));
await page.getByRole('tab', { name: 'XSLT Mapping' }).click();
await page.getByRole('tab', { name: 'value-mappings.json' }).click();
await page.locator('section[aria-label="File"]').getByRole('button', { name: 'Close file' }).click();
await page.getByRole('tab', { name: 'value-mappings.json' }).waitFor({ state: 'detached', timeout: 5000 });

// 8b) The × in a pane closes the open file and leaves it on disk: first the input, then the test case's mapping.
await page.getByRole('tab', { name: 'XSLT Mapping' }).click();
const orderXml = fs.readFileSync(path.join(project, 'inputs/order.xml'), 'utf8');
await page.locator('section.input').getByRole('button', { name: 'Close input file' }).click();
await page.locator('section.input h2', { hasText: '· order.xml' }).waitFor({ state: 'detached', timeout: 5000 })
  .catch(() => fail('the input header still names order.xml after closing it'));
// Monaco renders on the next frame.
await page.waitForFunction(() => !document.querySelector('section.input .view-lines')?.textContent?.trim(), null, { timeout: 5000 })
  .catch(() => fail('closing the input left its body in the editor'));
if (fs.readFileSync(path.join(project, 'inputs/order.xml'), 'utf8') !== orderXml) fail('closing the input changed inputs/order.xml');
// Unsaved changes: Cancel keeps the file open, OK closes it without writing the changes.
const caseXsl = fs.readFileSync(path.join(project, 'mappings/order-to-salesorder.xsl'), 'utf8');
await page.locator('section.mapping .view-lines').click({ position: { x: 40, y: 10 } });
await page.keyboard.press('Control+End');
await page.keyboard.type('<!-- edit -->');
const closeMapping = page.locator('section.mapping').getByRole('button', { name: 'Close file' });
await closeMapping.click();
await discard.getByRole('button', { name: 'Cancel' }).click().catch(() => fail('no question before closing a changed mapping'));
await page.locator('section.mapping h2[title="From test case tests/standard-order/case.json"]').waitFor({ timeout: 5000 })
  .catch(() => fail('Cancel closed the mapping anyway'));
await closeMapping.click();
await discard.getByRole('button', { name: 'OK' }).click();
await page.locator('section.mapping h2', { hasText: 'No mapping open' }).waitFor({ timeout: 5000 })
  .catch(() => fail('the mapping pane does not say that no mapping is open'));
await page.locator('section.mapping .empty-hint').waitFor({ timeout: 5000 }).catch(() => fail('no hint in the empty mapping editor'));
if (fs.readFileSync(path.join(project, 'mappings/order-to-salesorder.xsl'), 'utf8') !== caseXsl) fail('closing the mapping changed the file');
if ((await mappingFile.getAttribute('class')).includes('active')) fail('the closed mapping is still marked in the tree');
// Opening it again from the tree works as before.
await mappingFile.click();
await page.locator('section.mapping h2[title="mappings/order-to-salesorder.xsl"]').waitFor({ timeout: 5000 });

// 9) The gutter between input and mapping resizes the panes.
const inputPane = page.locator('section.input');
const w0 = (await inputPane.boundingBox()).width;
const g = await page.locator('.xslt > .gutter').first().boundingBox();
await page.mouse.move(g.x + g.width / 2, g.y + g.height / 2);
await page.mouse.down();
await page.mouse.move(g.x + 150, g.y + g.height / 2, { steps: 5 });
await page.mouse.up();
const w1 = (await inputPane.boundingBox()).width;
if (Math.abs(w1 - w0 - 150) > 10) fail(`input pane width ${w0} -> ${w1}, expected +150`);
if (shots) await page.screenshot({ path: path.join(shots, '7-resized.png') });

// 10) "+ Script" suggests a free name and asks before it overwrites an existing file.
fs.writeFileSync(path.join(project, 'scripts/new-script.groovy'), '// keep me\n');
await page.getByTitle('Reload file list').click();
// The reload is asynchronous: "+ Script" must see the new file, or it suggests the taken name.
await page.locator('.tree .file', { hasText: 'new-script.groovy' }).waitFor({ timeout: 10000 });
await page.getByRole('button', { name: '+ Script' }).click();
const newName = await page.getByRole('dialog').getByRole('textbox').inputValue();
if (newName !== 'scripts/new-script-2.groovy') fail('+ Script suggestion: ' + newName);
// Another spelling of the same path is the same file.
await page.getByRole('dialog').getByRole('textbox').fill('scripts/./new-script.groovy');
await page.getByRole('dialog').getByRole('button', { name: 'OK' }).click();
await page.getByRole('dialog').filter({ hasText: 'exists. Overwrite it?' }).getByRole('button', { name: 'Cancel' }).click()
  .catch(() => fail('no overwrite question for an existing script'));
if (fs.readFileSync(path.join(project, 'scripts/new-script.groovy'), 'utf8') !== '// keep me\n') fail('+ Script overwrote an existing file');

// 10a) Save As must ask even when the existing file is deeper than the engine's eight-level listing.
const unlistedCopy = 'mappings/one/two/three/four/five/six/seven/eight/hidden-copy.xsl';
const unlistedFile = path.join(project, unlistedCopy);
const expectedCopy = fs.readFileSync(path.join(project, 'mappings/order-to-salesorder.xsl'), 'utf8');
const overwrite = page.getByRole('dialog').filter({ hasText: 'exists. Overwrite it?' });
fs.mkdirSync(path.dirname(unlistedFile), { recursive: true });
fs.writeFileSync(unlistedFile, '<!-- original at an unlisted path -->');
for (const button of ['Cancel', 'Overwrite']) {
  await mappingPane.getByRole('button', { name: 'Save as…', exact: true }).click();
  await answer(unlistedCopy);
  await overwrite.waitFor({ timeout: 5000 });
  if (!(await cancelFocused(overwrite))) fail('the overwrite question did not open with Cancel focused');
  if (fs.readFileSync(unlistedFile, 'utf8') !== '<!-- original at an unlisted path -->') fail('Save As wrote before confirmation');
  await overwrite.getByRole('button', { name: button, exact: true }).click();
  await mappingPane.locator('button:not([disabled])', { hasText: /^Save$/ }).waitFor({ timeout: 5000 });
  if (button === 'Cancel' && fs.readFileSync(unlistedFile, 'utf8') !== '<!-- original at an unlisted path -->') fail('Cancel changed the unlisted file');
}
if (fs.readFileSync(unlistedFile, 'utf8') !== expectedCopy) fail('Save As did not write the confirmed replacement');
if (fs.readFileSync(path.join(project, 'mappings/order-to-salesorder.xsl'), 'utf8') !== expectedCopy) fail('Save As changed the source');
// Save As onto the editor's own file is a plain save of the same document: no overwrite question.
fs.writeFileSync(unlistedFile, '<!-- changed on disk -->');
await mappingPane.getByRole('button', { name: 'Save as…', exact: true }).click();
await answer(unlistedCopy);
for (let i = 0; i < 50 && fs.readFileSync(unlistedFile, 'utf8') !== expectedCopy; i++) await page.waitForTimeout(100);
if (await overwrite.count()) {
  fail('Save As onto the open file asked to overwrite it');
  await overwrite.getByRole('button', { name: 'Cancel', exact: true }).click();
}
if (fs.readFileSync(unlistedFile, 'utf8') !== expectedCopy) fail('Save As onto the open file did not save it');

// 10b) If a rename encounters a file created just as it writes, Cancel must preserve both files.
const raceDestination = 'schemas/appeared-during-rename.xsd';
let racingCreatorRan = false;
const createBeforeWrite = async (route) => {
  const rpc = route.request().postDataJSON();
  if (!racingCreatorRan && rpc?.method === 'file.write' && rpc.params.path === raceDestination) {
    racingCreatorRan = true;
    fs.writeFileSync(path.join(project, raceDestination), 'concurrent writer', { flag: 'wx' });
  }
  await route.continue();
};
const originalSchema = path.join(project, 'schemas/salesorder.xsd');
const schemaBeforeRace = fs.readFileSync(originalSchema, 'utf8');
await page.route('**/rpc', createBeforeWrite);
try {
  await (await menuOn('salesorder.xsd')).getByRole('menuitem', { name: 'Rename…' }).click();
  await answer(raceDestination);
  await overwrite.getByRole('button', { name: 'Cancel', exact: true }).click({ timeout: 5000 });
  await overwrite.waitFor({ state: 'hidden', timeout: 5000 });
  if (!racingCreatorRan || fs.readFileSync(path.join(project, raceDestination), 'utf8') !== 'concurrent writer') fail('rename replaced a file created by a concurrent writer');
  if (!fs.existsSync(originalSchema) || fs.readFileSync(originalSchema, 'utf8') !== schemaBeforeRace) fail('cancelled rename changed its source');
} finally {
  await page.unroute('**/rpc', createBeforeWrite);
}

// 11) Closing the project asks before it drops unsaved changes in the File tab; Cancel keeps it open.
await page.locator('.tree .file', { hasText: 'value-mappings.json' }).click();
await page.locator('section[aria-label="File"] .view-lines').click({ position: { x: 40, y: 10 } });
await page.keyboard.press('Control+End');
await page.keyboard.type(' ');
await page.getByTitle('Close project').click();
await page.getByRole('dialog').filter({ hasText: 'Discard the unsaved changes' }).getByRole('button', { name: 'Cancel' }).click()
  .catch(() => fail('no question before Close project drops File tab changes'));
if (!(await page.locator('.tree .file', { hasText: 'value-mappings.json' }).isVisible())) fail('Cancel closed the project anyway');
// The × on the File tab in the tool bar closes the file too, after the same question.
await page.getByRole('button', { name: 'Close file tab' }).click();
await page.getByRole('dialog').filter({ hasText: 'Discard the unsaved changes' }).getByRole('button', { name: 'OK' }).click()
  .catch(() => fail('no question before the File tab closes with unsaved changes'));
await page.getByRole('tab', { name: 'value-mappings.json' }).waitFor({ state: 'detached', timeout: 5000 })
  .catch(() => fail('the × on the File tab did not close it'));

// 12) Live checks modules as well as the editor. Use an inert Java declaration and verify that only Run
// uses the manual RPC, including after that stylesheet has already been run once.
const xsltRpc = [];
const observeXsltRpc = (req) => {
  if (req.method() !== 'POST' || !req.url().endsWith('/rpc')) return;
  const method = req.postDataJSON()?.method;
  if (method === 'xslt.preview' || method === 'xslt.run') xsltRpc.push(method);
};
const shell = (attributes, inner) => '<xsl:stylesheet version="2.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform"'
  + attributes + '>' + inner + '</xsl:stylesheet>';
fs.writeFileSync(path.join(project, 'mappings/preview-main.xsl'), shell('', '<xsl:include href="preview-module.xsl"/>'));
fs.writeFileSync(path.join(project, 'mappings/preview-module.xsl'), shell(' xmlns:math="java:java.lang.Math"',
  '<xsl:output method="text"/><xsl:template match="/">manual-preview-test</xsl:template>'));
await page.getByTitle('Reload file list').click();
page.on('request', observeXsltRpc);
await page.locator('.tree .file', { hasText: 'preview-main.xsl' }).click();
const blockedPreview = page.locator('section.output .badge', { hasText: 'Run required' });
await blockedPreview.waitFor({ timeout: 10000 });
if (!xsltRpc.includes('xslt.preview') || xsltRpc.includes('xslt.run')) fail('opening a mapping did not use restricted preview');
await page.locator('section.output').getByRole('tab', { name: 'Body', exact: true }).click();
await page.locator('section.input').getByRole('tab', { name: 'Body', exact: true }).click();
const previewInput = page.getByRole('textbox', { name: 'Input body', exact: true });
await previewInput.focus();
await page.keyboard.press('Control+A');
await page.keyboard.insertText('<input/>');
await page.getByTitle('Run (Ctrl+Enter)').click();
await page.locator('section.output .view-lines', { hasText: 'manual-preview-test' }).waitFor({ timeout: 10000 })
  .catch(async (e) => { throw new Error('manual run: ' + await page.locator('section.output').innerText(), { cause: e }); });
if (!xsltRpc.includes('xslt.run')) fail('Run did not use the manual XSLT RPC');
xsltRpc.length = 0;
await previewInput.focus();
await page.keyboard.press('End');
await page.keyboard.type(' ');
await blockedPreview.waitFor({ timeout: 10000 });
if (!xsltRpc.includes('xslt.preview') || xsltRpc.includes('xslt.run')) fail('Live reused manual XSLT approval');
page.off('request', observeXsltRpc);

// 13) A link from CPI Helper's xsltDebugX plugin that points at the app: the traced message and the mapping
// arrive in the XSLT tab and the data leaves the address. The mapping is someone's code: it runs on Run only.
/** A link's data as CPI Helper's plugins write it: JSON, raw DEFLATE, URL-safe Base64 without padding. */
async function shareData(data) {
  const stream = new Blob([JSON.stringify(data)]).stream().pipeThrough(new CompressionStream('deflate-raw'));
  return Buffer.from(await new Response(stream).arrayBuffer()).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
const shared = await shareData({ xml: '<Order id="from-trace"/>',
  xslt: '<xsl:stylesheet version="3.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform"><xsl:param name="Sender"/>'
    + '<xsl:template match="/"><Traced by="{$Sender}" id="{Order/@id}"/></xsl:template></xsl:stylesheet>',
  headers: [{ name: 'Sender', value: 'TENANT_TRACE' }], properties: [] });
await page.evaluate((hash) => { location.hash = hash; }, '#share/' + shared);
await page.locator('.status', { hasText: 'Imported from CPI Helper: body, 1 header, mapping' }).waitFor({ timeout: 10000 })
  .catch(async () => fail('the shared link was not imported: ' + (await page.locator('.status').innerText()) + ' ' + (await page.locator('.banner.error').allInnerTexts())));
await page.locator('section.mapping h2', { hasText: 'mapping.xsl (unsaved)' }).waitFor({ timeout: 5000 })
  .catch(() => fail('the imported mapping is not an unsaved mapping'));
if (!(await page.locator('.status').innerText()).includes('has not run')) fail('the imported mapping is not announced as not run');
await page.waitForTimeout(1500);
if (await page.locator('section.output .badge.ok, section.output .badge.bad').count()) fail('a mapping from a link ran without Run');
await page.getByTitle('Run (Ctrl+Enter)').click();
await page.locator('section.output .view-lines', { hasText: 'TENANT_TRACE' }).waitFor({ timeout: 15000 })
  .catch(async () => fail('the imported mapping did not run on the imported message: ' + (await page.locator('section.output').innerText()).slice(0, 300)));
if ((await page.evaluate(() => location.hash)) !== '') fail('the shared data stayed in the address');

if (errors.length) fail('browser errors: ' + errors.join(' | '));
await browser.close();
console.log(process.exitCode ? 'smoke test FAILED' : 'smoke test passed');
