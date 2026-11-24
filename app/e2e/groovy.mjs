// Browser test of the Groovy view in web mode with three engines:
//   cpiwb-xml-he99 serve --http 7777 --ui app/dist
//   java -jar cpiwb-groovy-v1.jar serve --http 7778
//   java -jar cpiwb-groovy-v2.jar serve --http 7779
//   node app/e2e/groovy.mjs <projectDir> [screenshotDir]
import { createRequire } from 'node:module';
import fs from 'node:fs';
import path from 'node:path';
import { gunzipSync } from 'node:zlib';

const { chromium } = createRequire(import.meta.url)('playwright');
const project = path.resolve(process.argv[2] ?? 'testdata/regression-project');
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

const runIt = () => page.getByRole('button', { name: /^\W*Run$/ }).click();
// The function picker lists the script's functions; any other name goes in under "Other name…".
const OTHER = '\u0000other';
const fnSelect = () => page.getByLabel('Function', { exact: true });
const fnName = async () => {
  const v = await fnSelect().inputValue();
  return v === OTHER ? page.getByLabel('Function name').inputValue() : v;
};
const setOtherFn = async (name) => {
  await fnSelect().selectOption(OTHER);
  await page.getByLabel('Function name').fill(name);
};
/** Waits until the output queue (one context per line, as in case.json) is exactly `text`. */
const waitQueue = (text, message) => page.waitForFunction((t) => document.querySelector('section.output .queue-out')?.getAttribute('data-queue') === t,
  text, { timeout: 20000 }).catch(async () => fail(`${message}: ${(await page.locator('section.output').innerText()).slice(0, 400)}`));
const inputTitle = () => page.locator('section.input h2').innerText();
/** Opens a file from the tree and starts it once it is open: opening alone runs no script. */
async function openAndRun(file, statusText) {
  await page.locator('.tree .file', { hasText: file }).click();
  await page.locator('.status', { hasText: statusText }).waitFor({ timeout: 10000 });
  await runIt();
}

// 1) The Groovy tab starts empty and runs nothing; a script typed in runs on Groovy 1.x.
await page.getByRole('tab', { name: 'Groovy Script' }).click();
await page.locator('section.script .empty-hint', { hasText: 'Examples' }).waitFor({ timeout: 5000 })
  .catch(() => fail('the empty script editor does not point to the examples'));
await page.waitForTimeout(1000);
if (await page.locator('section.output .badge').count()) fail('something ran on an empty start');
const typeInto = async (pane, text) => {
  await page.locator(`${pane} .view-lines`).click({ position: { x: 40, y: 10 } });
  await page.keyboard.press('Control+a');
  await page.keyboard.insertText(text);
};
await typeInto('section.input', '<Order id="4711">\n  <Item sku="M-100" qty="2"/>\n  <Item sku="M-200" qty="1"/>\n</Order>\n');
await typeInto('section.script', "import com.sap.gateway.ip.core.customdev.util.Message\n"
  + "def Message processData(Message message) {\n"
  + "    def order = new XmlSlurper().parseText(message.getBody(String))\n"
  + "    messageLogFactory.getMessageLog(message)?.setStringProperty('Order', order.@id.text())\n"
  + "    println \"Processing order ${order.@id}\"\n"
  + "    message.setBody('<ProcessedOrder/>')\n"
  + "    return message\n}\n");
await runIt();
await page.locator('section.output .badge.ok').waitFor({ timeout: 20000 });
// Monaco renders the output a moment after the badge, so wait for the text itself.
await page.locator('section.output .view-lines', { hasText: 'ProcessedOrder' }).waitFor({ timeout: 10000 })
  .catch(async () => fail('groovy output: ' + (await page.locator('section.output .view-lines').innerText()).slice(0, 200)));
await page.getByRole('tab', { name: /^MPL/ }).click();
if (!(await page.locator('section.output .pad').innerText()).includes('Order')) fail('MPL property missing');
await page.getByRole('tab', { name: /^Console/ }).click();
if (!(await page.locator('section.output .console').innerText()).includes('Processing order 4711')) fail('console output missing');
if (shots) await page.screenshot({ path: path.join(shots, 'g1-groovy-v1.png') });

// 2) A Groovy 2.x example loads without a project, waits for Run and runs on Groovy 4.
await page.getByLabel('Examples').selectOption('groovy-xml');
await page.getByRole('dialog').filter({ hasText: 'Discard the unsaved changes' }).getByRole('button', { name: 'OK' }).click();
await page.locator('.status', { hasText: 'has not run' }).waitFor({ timeout: 10000 });
if ((await page.getByLabel('Runtime profile').inputValue()) !== 'groovy-2.x') fail('the 2.x example did not switch to 2.x');
await runIt();
await page.locator('section.output .badge.ok').waitFor({ timeout: 20000 });
await page.locator('section.output footer', { hasText: 'Groovy 4.0.29' }).waitFor({ timeout: 10000 });

// 3) A runtime error is reported with its script line.
await page.locator('section.script .view-lines').click({ position: { x: 40, y: 10 } }); // the element is as wide as the longest line
await page.keyboard.press('Control+a');
await page.keyboard.insertText("import com.sap.it.script.v2.api.Message\ndef Message processData(Message message) {\n    def items = null\n    message.setProperty('n', items.size())\n    return message\n}\n");
await page.locator('section.output .badge.bad').waitFor({ timeout: 20000 });
await page.locator('section.output').getByRole('tab', { name: /^Body/ }).click();
const diag = await page.locator('section.output .diag.error').first().innerText();
if (!diag.includes('line 4') || !diag.includes('NullPointerException')) fail('runtime error: ' + diag);
if (shots) await page.screenshot({ path: path.join(shots, 'g2-groovy-error.png') });

// 3b) Script API completions after `message.` (v2 members for a v2 script).
await page.locator('section.script .view-lines').click({ position: { x: 40, y: 10 } });
await page.keyboard.press('Control+End');
await page.keyboard.type('message.reset');
await page.locator('.suggest-widget .monaco-list-row', { hasText: 'resetBodyStream' }).first().waitFor({ timeout: 5000 })
  .catch(() => fail('no Script API completions after message.'));
if (shots) await page.screenshot({ path: path.join(shots, 'g2b-completions.png') });
await page.keyboard.press('Escape');

// 4) Open the demo project and run the Groovy test case.
await page.getByLabel('Project folder').fill(project);
await page.getByRole('button', { name: 'Open' }).click();
await page.locator('.tree .file', { hasText: 'groovy-enrich-order/case.json' }).click();
// The script typed in step 3 is unsaved, so the case asks before it replaces the script.
await page.getByRole('dialog').filter({ hasText: 'Discard the unsaved changes' }).getByRole('button', { name: 'OK' }).click();
// Opening does not run a script (it runs with the user's rights and may come from someone else): Run starts it.
await page.locator('.status', { hasText: 'it has not run' }).waitFor({ timeout: 10000 })
  .catch(() => fail('opening a Groovy case did not say that it has not run'));
if (await page.locator('section.output .badge.ok, section.output .badge.bad').count()) fail('a Groovy case ran when it was opened');
await runIt();
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

// 4a1) Platform data as tables: a change applies to the next run before it is saved; Save writes the file.
const inputTab = (name) => page.locator('section.input').getByRole('tab', { name });
const vmFile = path.join(project, 'platform/value-mappings.json');
await inputTab(/^Platform/).click();
const target = page.getByLabel('Target value 1');
const soldTo = await target.inputValue();
if (soldTo !== '0010000077') fail('value mapping table does not show the project file: ' + soldTo);
await target.fill('SOLD-TO-EDITED');
await page.locator('section[aria-label="Value mappings"] .dirty').waitFor({ timeout: 5000 })
  .catch(() => fail('a changed value mapping is not marked as unsaved'));
await page.locator('section.output').getByRole('tab', { name: /^Properties/ }).click();
await page.locator('section.output tr', { hasText: 'SOLD-TO-EDITED' }).waitFor({ timeout: 20000 })
  .catch(() => fail('an unsaved value mapping did not reach the run'));
if (fs.readFileSync(vmFile, 'utf8').includes('SOLD-TO-EDITED')) fail('the value mapping file changed before Save');
await page.getByRole('button', { name: 'Save Value mappings' }).click();
await page.locator('.status', { hasText: 'Saved platform/value-mappings.json' }).waitFor({ timeout: 10000 });
const savedVm = JSON.parse(fs.readFileSync(vmFile, 'utf8'));
if (savedVm[0].targetValue !== 'SOLD-TO-EDITED' || savedVm.length !== 2) fail('saved value mappings: ' + JSON.stringify(savedVm));
// The Secure Store's password is kept when another field changes, and the Partner Directory's alternative partners stay.
if (!(await page.locator('section[aria-label="Partner Directory"]').innerText()).includes('alternative partner')) fail('no note on the partners\' other data');
await target.fill(soldTo);
await page.getByRole('button', { name: 'Save Value mappings' }).click();
await page.locator('section[aria-label="Value mappings"] .dirty').waitFor({ state: 'detached', timeout: 10000 })
  .catch(() => fail('the value mappings still count as unsaved after Save'));

// 4a1b) Another project replaces unsaved table rows, after a question: they must not land in its runs or files.
const otherProject = project + '-other';
fs.rmSync(otherProject, { recursive: true, force: true });
fs.cpSync(project, otherProject, { recursive: true });
await target.fill('UNSAVED-IN-A');
const openFolder = async (dir) => {
  await page.getByTitle('Close project').click().catch(() => {});
  await page.getByRole('dialog').filter({ hasText: 'Discard the unsaved platform data' }).getByRole('button', { name: 'OK' }).click({ timeout: 3000 }).catch(() => {});
  await page.getByLabel('Project folder').fill(dir);
  if (await page.getByRole('button', { name: 'Open file' }).count()) fail('the Platform tab offers a file with no project open: '
    + dir + ' ' + JSON.stringify(await page.getByRole('button', { name: 'Open file' }).evaluateAll((es) => es.map((e) => e.closest('section')?.getAttribute('aria-label')))));
  await page.getByRole('button', { name: 'Open', exact: true }).click();
  await page.locator('.tree .file').first().waitFor({ timeout: 10000 });
};
await page.getByTitle('Close project').click();
await page.getByRole('dialog').filter({ hasText: 'Discard the unsaved platform data' }).getByRole('button', { name: 'Cancel' }).click()
  .catch(() => fail('no question before Close project drops unsaved platform rows'));
if ((await target.inputValue()) !== 'UNSAVED-IN-A') fail('Cancel did not keep the unsaved row');
await openFolder(otherProject);
await inputTab(/^Platform/).click();
await page.getByLabel('Target value 1').waitFor({ timeout: 10000 });
if ((await page.getByLabel('Target value 1').inputValue()) !== soldTo) fail('the other project shows the first one\'s unsaved row: ' + await page.getByLabel('Target value 1').inputValue());
if (fs.readFileSync(path.join(otherProject, 'platform/value-mappings.json'), 'utf8').includes('UNSAVED-IN-A')) fail('an unsaved row reached the other project\'s file');
// A save that answers after its project closed must not touch the tables of the project open by then (review F01).
await page.getByLabel('Target value 1').fill('SAVED-IN-OTHER');
let releaseWrite;
const writeHeld = new Promise((r) => (releaseWrite = r));
// The write reaches the engine at once (it writes into the open project); only its answer is held back.
await page.route(/\/rpc$/, async (route) => {
  if (!route.request().postData()?.includes('"file.write"')) return route.continue().catch(() => {});
  const response = await route.fetch();
  await writeHeld;
  await route.fulfill({ response }).catch(() => {});
});
await page.getByRole('button', { name: 'Save Value mappings' }).click();
await page.waitForTimeout(300);
await openFolder(project);
releaseWrite();
await page.waitForTimeout(1500);
await page.unroute(/\/rpc$/);
await inputTab(/^Platform/).click();
if (!(await page.getByLabel('Target value 1').count())) fail('a late save of another project emptied this project\'s table');
else if ((await page.getByLabel('Target value 1').inputValue()) !== soldTo) fail('a late save changed the open project\'s table');
if (await page.locator('section[aria-label="Value mappings"] .dirty').count()) fail('a late save of another project marks this table as changed');
if (!fs.readFileSync(path.join(otherProject, 'platform/value-mappings.json'), 'utf8').includes('SAVED-IN-OTHER')) fail('the held save did not reach its own project');
fs.rmSync(otherProject, { recursive: true, force: true });
await page.locator('.tree .file', { hasText: 'groovy-enrich-order/case.json' }).click();
await page.locator('section.script h2', { hasText: 'enrich-order.groovy' }).waitFor({ timeout: 10000 });

// 4a2) A case with an attachment file: it shows as a row of the Attachments tab, with the file's project path.
await page.locator('.tree .file', { hasText: 'groovy-attachment/case.json' }).click();
// The previous case's result stays visible until this run ends, so wait for content, not the badge.
await inputTab(/^Attachments\s*1/).waitFor({ timeout: 20000 }).catch(() => fail('the case\'s attachment is not counted on the Attachments tab'));
await inputTab(/^Attachments/).click();
if ((await page.getByLabel('Content 1').inputValue()) !== 'tests/groovy-attachment/delivery-note.txt') fail('attachment file path: ' + await page.getByLabel('Content 1').inputValue());
await page.locator('.status', { hasText: 'read a message attachment' }).waitFor({ timeout: 10000 });
await runIt();
await page.locator('section.output').getByRole('tab', { name: /^Headers/ }).click();
await page.locator('section.output tr', { hasText: 'DN-4711' }).waitFor({ timeout: 20000 })
  .catch(async () => fail('attachment header: ' + (await page.locator('section.output').innerText()).slice(0, 400)));
// The same attachment as text typed in the tab.
await page.getByLabel('Content from 1').selectOption('text');
await page.getByLabel('Name 1').fill('delivery-note.txt');
await page.getByLabel('Content 1').fill('Delivery DN-TYPED');
await page.locator('section.output tr', { hasText: 'DN-TYPED' }).waitFor({ timeout: 20000 })
  .catch(async () => fail('attachment typed as text: ' + (await page.locator('section.output').innerText()).slice(0, 400)));

// 4a3) The × closes the case with its script: the editor, the case's input and its extras go; the file stays.
const caseScript = (await page.locator('section.script h2').getAttribute('title')) ?? '';
await page.locator('section.script').getByRole('button', { name: 'Close file' }).click();
await page.locator('section.script h2', { hasText: 'No script open' }).waitFor({ timeout: 5000 })
  .catch(() => fail('the script pane does not say that no script is open'));
await page.locator('section.script .empty-hint').waitFor({ timeout: 5000 }).catch(() => fail('no hint in the empty script editor'));
if (!(await inputTab(/^Attachments\s*0/).count())) fail('the closed case kept its attachments');
if (!caseScript.startsWith('From test case ')) fail('the script header does not name the test case: ' + caseScript);

// 4b) Import an exported iFlow (zip) and open its Groovy step.
if (process.env.CPIWB_IFLOW_ZIP) {
  await page.getByRole('button', { name: 'Import iFlow' }).click();
  await page.locator('.dlg input.mono').fill(process.env.CPIWB_IFLOW_ZIP);
  await page.locator('.dlg').getByRole('button', { name: 'OK' }).click();
  await page.locator('.steps .step', { hasText: 'Enrich order' }).click();
  await page.locator('section.script h2', { hasText: 'enrich.groovy' }).waitFor({ timeout: 10000 });
  const fn = await fnName();
  if (fn !== 'enrich') fail('function from iFlow step: ' + fn);
  const prof = await page.getByLabel('Runtime profile').inputValue();
  if (prof !== 'groovy-2.x') fail('profile from iFlow step: ' + prof);
  if (shots) await page.screenshot({ path: path.join(shots, 'g5-iflow.png') });
  // A Content Modifier of the iFlow opens in Flow Steps with its rows.
  await page.locator('.steps .step', { hasText: 'Set headers' }).click();
  await page.locator('.src', { hasText: 'Set headers' }).waitFor({ timeout: 10000 })
    .catch(() => fail('Content Modifier from the iFlow did not open in Flow Steps'));
  await page.locator('.steps .step', { hasText: 'Keep items' }).click();
  if ((await page.getByLabel('Filter XPath').inputValue()) !== '/Order/Item') fail('Filter XPath from the iFlow');
  await page.locator('.steps .step', { hasText: 'Gather items' }).click();
  if ((await page.getByLabel('Aggregation Algorithm').inputValue()) !== 'combineAtXPath') fail('Gather algorithm from the iFlow');
  // × closes the iFlow step: Gather has its own settings again; the Filter taken from the iFlow stays.
  await page.getByRole('button', { name: 'Close iFlow step' }).click();
  await page.locator('.src').waitFor({ state: 'detached', timeout: 5000 }).catch(() => fail('Close iFlow step left the "From" line'));
  if ((await page.getByLabel('Aggregation Algorithm').inputValue()) !== 'combine') fail('Close iFlow step did not bring back Gather\'s own settings');
  await page.getByRole('tab', { name: 'Filter' }).click();
  await page.locator('.src', { hasText: 'Keep items' }).waitFor({ timeout: 5000 }).catch(() => fail('closing Gather also closed the Filter from the iFlow'));
  if ((await page.getByLabel('Filter XPath').inputValue()) !== '/Order/Item') fail('closing Gather changed the Filter from the iFlow');
  // A Message Mapping step is listed (not simulated); it opens its script with the first mapping function.
  await page.locator('.steps .step', { hasText: 'Map order' }).click();
  await page.locator('section.script h2', { hasText: 'lookup.gsh' }).waitFor({ timeout: 10000 })
    .catch(() => fail('Message Mapping step did not open its script'));
  await page.locator('.status', { hasText: 'Press Ctrl+Enter to run it' }).waitFor({ timeout: 10000 });
  await runIt();
  await page.locator('section.output .badge.ok').waitFor({ timeout: 20000 })
    .catch(() => fail('mapping script from the iFlow did not run'));
  if ((await fnName()) !== 'getHeader') fail('mapping function not picked');
  // 4b2) The list holds the whole iFlow (routes, adapters, other processes); an element that is not simulated
  // shows its settings in the Diagram tab, and a double-click there opens a step that runs.
  if (!(await page.locator('.steps .route', { hasText: 'ERP' }).count())) fail('the Router route is not in the list');
  if (!(await page.locator('.steps .adapter', { hasText: 'HTTPS' }).count())) fail('the sender adapter is not in the list');
  await page.locator('.steps .node', { hasText: 'Write to data store' }).click();
  await page.locator('.diagram svg').waitFor({ timeout: 10000 }).catch(() => fail('the Diagram tab did not open'));
  const details = page.locator('.diagram .details');
  await details.waitFor({ timeout: 5000 }).catch(() => fail('no details for the selected element'));
  if (!(await details.innerText()).includes('storageName')) fail('details of the data store step: ' + (await details.innerText()));
  if (shots) await page.screenshot({ path: path.join(shots, 'g5b-diagram.png') });
  await page.locator('.diagram [data-node="CallActivity_1"]').dblclick();
  await page.locator('section.script h2', { hasText: 'enrich.groovy' }).waitFor({ timeout: 10000 })
    .catch(() => fail('a double-click in the diagram did not open the Groovy step'));
  // 4b3) Where used: the overview lists what the iFlow uses and its {{…}} parameters; a value finds its adapter,
  // a click shows it in the diagram.
  await page.getByRole('tab', { name: 'Where used', exact: true }).click();
  const params = page.locator('.wu table.params');
  await params.waitFor({ timeout: 10000 }).catch(() => fail('no parameter table in the overview'));
  if (!(await params.innerText()).includes('GroupSize')) fail('parameters: ' + (await params.innerText()));
  const address = page.locator('.wu .kind-group', { hasText: 'Address' }).getByRole('button', { name: /s4\.example/ });
  await address.first().waitFor({ timeout: 5000 }).catch(() => fail('the overview lists no s4.example address'));
  if (shots) await page.screenshot({ path: path.join(shots, 'g5c-whereused.png') });
  await address.first().click();
  if ((await page.getByLabel('Kind').inputValue()) !== 'address') fail('a value of the overview was not searched as its kind');
  await page.getByRole('button', { name: '← Overview' }).click();
  await params.waitFor({ timeout: 5000 }).catch(() => fail('← Overview did not show the overview again'));
  await page.getByLabel('Search the iFlows').fill('s4.example');
  const hit = page.locator('.wu .el', { hasText: 'SOAP receiver' });
  await hit.waitFor({ timeout: 10000 }).catch(() => fail('Where used found no SOAP receiver for s4.example'));
  if (!(await hit.locator('mark').count())) fail('the match is not highlighted');
  await hit.locator('button.name').click();
  await page.locator('.diagram .details').waitFor({ timeout: 10000 }).catch(() => fail('a hit did not show the diagram'));
  if (!(await page.locator('.diagram .details').innerText()).includes('s4.example.com')) fail('the hit selected another element');
  await page.getByRole('tab', { name: 'Groovy Script' }).click();
}

// 4c) Another script starts with processData again, not with the function set for the previous one.
await setOtherFn('customFunction');
await page.locator('.tree .file', { hasText: 'route-by-partner.groovy' }).click();
await page.locator('section.script h2', { hasText: 'route-by-partner.groovy' }).waitFor({ timeout: 10000 });
if ((await fnName()) !== 'processData') fail('function name kept from the previous script');
if (await page.getByLabel('Function name').count()) fail('"Other name…" kept for a script whose function is listed');
if (!(await inputTitle()).startsWith('Input message')) fail('input pane title for a script step');
// 4c2) Live off: a run that answers after another script was opened does not land in that script.
// The first run is held until the other script is open; any later run is held until the check is done.
await page.getByLabel('Live').uncheck();
await page.locator('.tree .file', { hasText: 'mapping-null.groovy' }).click();
await page.locator('section.script h2', { hasText: 'mapping-null.groovy' }).waitFor({ timeout: 10000 });
// A script with mapping functions only opens with its first one, read from the text before any run.
if ((await fnName()) !== 'classify') fail('mapping function not picked from the script text');
if (!(await inputTitle()).startsWith('Function arguments')) fail('arguments not shown before the first run');
await page.getByLabel('Runtime profile').selectOption('groovy-1.x');
await page.locator('section.output .badge.run').waitFor({ state: 'detached', timeout: 20000 }).catch(() => {});
let releaseFirst, releaseLater;
const first = new Promise((r) => (releaseFirst = r));
const later = new Promise((r) => (releaseLater = r));
const groovyRpc = /127\.0\.0\.1:777[89]\/project\/rpc$/;
let heldRuns = 0;
await page.route(groovyRpc, async (route) => {
  if (route.request().postData()?.includes('groovy.run')) await (++heldRuns === 1 ? first : later);
  await route.continue().catch(() => {}); // unroute may have let it through already
});
await page.getByRole('button', { name: /^\W*Run$/ }).click();
// enrich-order.groovy is a 1.x script too, so opening it changes no runtime (which would start a run of its own).
await page.locator('.tree .file', { hasText: 'enrich-order.groovy' }).first().click();
await page.locator('section.script h2', { hasText: 'enrich-order.groovy' }).waitFor({ timeout: 10000 });
releaseFirst();
await page.waitForTimeout(1500);
if (heldRuns === 0) fail('the late-run check held no run');
if (!(await inputTitle()).startsWith('Input message')) fail('a late run of the previous script turned this one into a mapping function');
releaseLater();
await page.unroute(groovyRpc);
await page.getByLabel('Live').check();

// 4d) Message Mapping functions: arguments instead of a body, the output queue as the result.
// One call per context: each call counts its own context, the calls are joined by context changes.
await openAndRun('mapping-context/case.json', 'all values of a context');
// The output keeps the tab chosen before (Headers in 4a2), so open the result.
await page.locator('section.output').getByRole('tab', { name: 'Result' }).click();
await waitQueue('["2"]\n["1"]\n["0"]', 'mapping queue');
if (!(await page.locator('section.output .queue-out tr').first().innerText()).match(/Context 1\s+2/)) fail('output queue rows are not named by context');
// A String[] argument shows its contexts as rows; SUPPRESS is a kind of value.
if ((await page.getByLabel('Argument 1, context 1, value 1').inputValue()) !== 'Multi Print') fail('mapping argument from the case');
if ((await page.getByLabel('Kind of value 2 in context 2').inputValue()) !== 'suppress') fail('SUPPRESS from the case');
if (shots) await page.screenshot({ path: path.join(shots, 'g3c-mapping-context.png') });
// Compare runs the function on 2.x too and compares the function result, not only the body.
await page.getByRole('button', { name: 'Compare', exact: true }).click();
await page.locator('section.output .compare .same', { hasText: 'function result' }).waitFor({ timeout: 20000 })
  .catch(async () => fail('compare of a mapping function: ' + (await page.locator('section.output').innerText()).slice(0, 400)));
await page.getByRole('button', { name: 'Comparing' }).click();
await page.locator('section.input').getByRole('button', { name: 'Text', exact: true }).click();
await page.getByLabel('Argument 1', { exact: true }).fill('["A", "B", "C"]');
await waitQueue('["3"]', 'mapping argument typed in the tab');
// Back to rows: SUPPRESS on a value takes it out of the count; + Context adds a context change.
await page.locator('section.input').getByRole('button', { name: 'Text', exact: true }).click();
await page.getByLabel('Kind of value 3 in context 1').selectOption('suppress');
await waitQueue('["2"]', 'SUPPRESS chosen in the context editor did not reach the run');
await page.locator('section.input').getByRole('button', { name: '+ Context' }).click();
await page.getByLabel('Argument 1, context 2, value 1').fill('D');
await waitQueue('["2"]\n["1"]', 'a context added in the editor');
await openAndRun('mapping-get-header/case.json', 'getHeader through MappingContext');
await page.locator('section.output pre', { hasText: '4711' }).waitFor({ timeout: 20000 })
  .catch(async () => fail('mapping returned value: ' + (await page.locator('section.output').innerText()).slice(0, 400)));
// A test case's null argument stays null in the tab (not an empty string); the null button toggles it.
await openAndRun('mapping-null-arg/case.json', 'a null argument stays null');
await page.locator('section.output .pad', { hasText: 'NULL' }).waitFor({ timeout: 20000 })
  .catch(async () => fail('null argument from the case: ' + (await page.locator('section.output').innerText()).slice(0, 300)));
if ((await page.getByRole('button', { name: 'null', exact: true }).getAttribute('aria-pressed')) !== 'true') fail('null button not pressed');
await page.getByRole('button', { name: 'null', exact: true }).click();
await page.locator('section.output .pad', { hasText: 'EMPTY' }).waitFor({ timeout: 20000 })
  .catch(() => fail('the null button did not switch to an empty string'));
await openAndRun('mapping-get-header/case.json', 'getHeader through MappingContext');
await page.locator('section.output pre', { hasText: '4711' }).waitFor({ timeout: 20000 });
await page.getByLabel('Argument 1', { exact: true }).fill('Missing');
await page.locator('section.output .pad .hint', { hasText: 'null' }).waitFor({ timeout: 20000 })
  .catch(() => fail('getHeader of a missing header is not null'));
// The picker lists the script's functions; choosing one switches the arguments to its parameters.
await fnSelect().selectOption('countPerContext');
await page.locator('section.input .arg-head', { hasText: 'all values of a context' }).waitFor({ timeout: 10000 })
  .catch(() => fail('choosing countPerContext did not show its String[] argument'));
if ((await fnName()) !== 'countPerContext') fail('the picker did not set the function');
// Opening a script starts with processData again, so the Body tab is back.
await page.locator('.tree .file', { hasText: 'route-by-partner.groovy' }).click();
await page.locator('section.input').getByRole('tab', { name: 'Body' }).waitFor({ timeout: 10000 })
  .catch(() => fail('no Body tab after opening a script'));
// A script with mapping functions only, opened from the tree, runs its first one on its first run; it keeps its
// function and arguments when the tree opens it again, from another tab or after another script.
await openAndRun('mapping-functions.groovy', 'mapping-functions.groovy. A script runs with your rights');
await page.locator('section.input').getByRole('tab', { name: /Arguments/ }).waitFor({ timeout: 20000 })
  .catch(() => fail('a script with only mapping functions did not open with its arguments'));
const fnOpened = await fnName();
await page.getByLabel('Argument 1', { exact: true }).fill('["kept"]');
await page.getByRole('tab', { name: 'XSLT Mapping' }).click();
await page.locator('.tree .file', { hasText: 'mapping-functions.groovy' }).click();
await page.locator('section.script').waitFor({ timeout: 10000 });
if ((await page.getByLabel('Argument 1', { exact: true }).inputValue()) !== '["kept"]') fail('arguments lost when the open script was clicked again');
await page.locator('.tree .file', { hasText: 'route-by-partner.groovy' }).click();
await page.locator('section.input').getByRole('tab', { name: 'Body' }).waitFor({ timeout: 10000 });
// Opened after another script, it has not run yet: its function is back at once, its arguments with the run.
await page.locator('.tree .file', { hasText: 'mapping-functions.groovy' }).click();
await page.locator('.status', { hasText: 'mapping-functions.groovy. A script runs with your rights' }).waitFor({ timeout: 10000 });
if ((await fnName()) !== fnOpened) fail('function not back before the run');
await runIt();
await page.getByLabel('Argument 1', { exact: true }).waitFor({ timeout: 20000 })
  .catch(() => fail('arguments tab not back after reopening the script'));
if ((await page.getByLabel('Argument 1', { exact: true }).inputValue()) !== '["kept"]') fail('arguments lost after another script');
if ((await fnName()) !== fnOpened) fail('function lost after another script');
await page.locator('.tree .file', { hasText: 'route-by-partner.groovy' }).click();
await page.locator('section.input').getByRole('tab', { name: 'Body' }).waitFor({ timeout: 10000 });

// 4e) Import…: a link from CPI Helper's GroovyDebugX plugin brings a traced step's message and script.
// The script is someone's code: it does not run until Run.
/** A link's data as CPI Helper's plugins write it: JSON, raw DEFLATE, URL-safe Base64 without padding. */
async function shareData(data) {
  const stream = new Blob([JSON.stringify(data)]).stream().pipeThrough(new CompressionStream('deflate-raw'));
  return Buffer.from(await new Response(stream).arrayBuffer()).toString('base64').replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}
const sharedLink = 'https://groovyide.com/cpi/share/v1/' + await shareData({
  input: { body: '<Order id="traced-1"/>', headers: { TraceHeader: 'from-tenant' }, properties: { TraceProp: 'p1' } },
  script: { code: 'import com.sap.gateway.ip.core.customdev.util.Message\ndef Message enrichTraced(Message message) {\n  message.setHeader("Seen", message.getHeader("TraceHeader", String) + "!")\n  return message\n}\n', function: 'enrichTraced' } });
await page.locator('section.input').getByRole('tab', { name: 'Body' }).click();
await page.locator('section.input').getByRole('button', { name: 'Import…' }).click();
await page.getByRole('dialog').getByRole('textbox').fill(sharedLink);
await page.getByRole('dialog').getByRole('button', { name: 'OK' }).click();
await page.locator('.status', { hasText: 'Imported from CPI Helper: body, 1 header, 1 property, script' }).waitFor({ timeout: 10000 })
  .catch(async () => fail('import of a GroovyDebugX link: ' + (await page.locator('.status').innerText()) + ' ' + (await page.locator('.banner.error').allInnerTexts())));
if (!(await page.locator('.status').innerText()).includes('it has not run')) fail('the imported script is not announced as not run');
if (await page.locator('section.output .badge.ok, section.output .badge.bad').count()) fail('an imported script ran without Run');
if ((await fnName()) !== 'enrichTraced') fail('function of the imported script: ' + await fnName());
await page.locator('section.input .view-lines', { hasText: 'traced-1' }).waitFor({ timeout: 5000 }).catch(() => fail('imported body missing'));
await runIt();
await page.locator('section.output').getByRole('tab', { name: /^Headers/ }).click();
await page.locator('section.output tr', { hasText: 'from-tenant!' }).waitFor({ timeout: 20000 })
  .catch(async () => fail('the imported script on the imported message: ' + (await page.locator('section.output').innerText()).slice(0, 300)));
// What cannot be read says why.
await page.locator('section.input').getByRole('button', { name: 'Import…' }).click();
await page.getByRole('dialog').getByRole('textbox').fill('https://ide.contiva.com/cpi/script/debug?data=H4sIAAAA');
await page.getByRole('dialog').getByRole('button', { name: 'OK' }).click();
await page.locator('.banner.error', { hasText: 'Contiva IDE' }).waitFor({ timeout: 5000 }).catch(() => fail('no reason for a link that cannot be read'));
await page.locator('.banner.error').getByRole('button', { name: 'Dismiss' }).click();
await page.locator('.tree .file', { hasText: 'route-by-partner.groovy' }).click();
await page.getByRole('dialog').filter({ hasText: 'Discard the unsaved changes' }).getByRole('button', { name: 'OK' }).click()
  .catch(() => fail('the imported script does not count as unsaved'));
await page.locator('section.script h2', { hasText: 'route-by-partner.groovy' }).waitFor({ timeout: 10000 });

// 4f) Another mapping function: what is typed for it before its first run stays after the run (review F05).
await page.locator('.tree .file', { hasText: 'mapping-functions.groovy' }).click();
await page.locator('section.script h2', { hasText: 'mapping-functions.groovy' }).waitFor({ timeout: 10000 });
await page.getByLabel('Live').uncheck();
await fnSelect().selectOption('getHeader');
await runIt();
await page.locator('section.output .badge.ok').waitFor({ timeout: 20000 });
await fnSelect().selectOption('setProperty');
await page.getByLabel('Argument 1', { exact: true }).fill('P');
await page.getByLabel('Argument 2', { exact: true }).fill('B-value');
await runIt();
await page.locator('section.output').getByRole('tab', { name: 'Result' }).click();
await page.locator('section.output pre', { hasText: 'B-value' }).waitFor({ timeout: 20000 })
  .catch(async () => fail('setProperty did not return its argument: ' + (await page.locator('section.output').innerText()).slice(0, 300)));
await page.waitForTimeout(500);
if ((await page.getByLabel('Argument 2', { exact: true }).inputValue()) !== 'B-value') fail('the arguments typed for a new function were dropped after its first run');
// The same with the name typed under "Other name…" (re-review of 86beb74, R05).
await fnSelect().selectOption('getHeader');
await runIt();
await page.locator('section.output .badge.ok').waitFor({ timeout: 20000 });
await fnSelect().selectOption(OTHER);
await page.getByLabel('Function name').fill('setProperty');
await page.getByLabel('Argument 1', { exact: true }).fill('P');
await page.getByLabel('Argument 2', { exact: true }).fill('C-value');
await runIt();
await page.locator('section.output pre', { hasText: 'C-value' }).waitFor({ timeout: 20000 })
  .catch(async () => fail('setProperty (typed) did not return its argument: ' + (await page.locator('section.output').innerText()).slice(0, 300)));
await page.waitForTimeout(500);
if ((await page.getByLabel('Argument 2', { exact: true }).inputValue()) !== 'C-value') fail('the arguments typed for a function named under Other name were dropped');
// And with the old function's run still under way: its late answer belongs to it, not to the new one.
let releaseOld;
const oldHeld = new Promise((r) => (releaseOld = r));
let heldOld = 0;
await page.route(groovyRpc, async (route) => {
  if (route.request().postData()?.includes('"getHeader"') && ++heldOld === 1) await oldHeld;
  await route.continue().catch(() => {});
});
await fnSelect().selectOption('getHeader');
await runIt();
await page.waitForTimeout(300);
await fnSelect().selectOption('setProperty');
await page.getByLabel('Argument 1', { exact: true }).fill('P');
await page.getByLabel('Argument 2', { exact: true }).fill('D-value');
releaseOld();
await page.waitForTimeout(1000);
await page.unroute(groovyRpc);
if (heldOld === 0) fail('the late-answer check held no run');
await runIt();
await page.locator('section.output pre', { hasText: 'D-value' }).waitFor({ timeout: 20000 })
  .catch(async () => fail('setProperty after a late getHeader answer: ' + (await page.locator('section.output').innerText()).slice(0, 300)));
await page.waitForTimeout(500);
if ((await page.getByLabel('Argument 2', { exact: true }).inputValue()) !== 'D-value') fail('a late answer of the old function dropped the new one\'s arguments');
await page.getByLabel('Live').check();
await page.locator('.tree .file', { hasText: 'route-by-partner.groovy' }).click();
await page.locator('section.script h2', { hasText: 'route-by-partner.groovy' }).waitFor({ timeout: 10000 });

// 5) A file from inputs/ opens as the Groovy input body.
fs.mkdirSync(path.join(project, 'inputs'), { recursive: true });
fs.writeFileSync(path.join(project, 'inputs/g-input.xml'), '<Order id="g-input-1"/>\n');
await page.getByTitle('Reload file list').click();
await page.locator('.tree .file', { hasText: 'g-input.xml' }).click();
await page.locator('section.input .view-lines', { hasText: 'g-input-1' }).waitFor({ timeout: 10000 })
  .catch(() => fail('inputs/g-input.xml not loaded as Groovy input'));
await page.locator('section.input h2', { hasText: '· g-input.xml' }).waitFor({ timeout: 5000 })
  .catch(() => fail('Groovy input header does not name g-input.xml'));

// 6) A JSON input opens in the Groovy tab, even from the XSLT tab, and is formatted as JSON.
fs.writeFileSync(path.join(project, 'inputs/g-input.json'), '{"order":{"id":"j-1","items":[1,2]}}\n');
await page.getByTitle('Reload file list').click();
await page.getByRole('tab', { name: 'XSLT Mapping' }).click();
await page.locator('.tree .file', { hasText: 'g-input.json' }).click();
await page.locator('section.script').waitFor({ timeout: 10000 })
  .catch(() => fail('JSON input did not open in the Groovy tab'));
await page.locator('section.input').getByRole('button', { name: 'Format' }).click();
await page.locator('section.input .view-lines', { hasText: '"items": [' }).waitFor({ timeout: 5000 })
  .catch(() => fail('Format did not pretty-print the JSON input'));

// 6b) Flow Steps, which start empty: Content Modifier (XPath on the XML engine, Simple on Camel),
// Router (CPI's Non-XML spelling) and General Splitter, with the settings entered here.
await page.getByRole('tab', { name: 'Flow Steps' }).click();
const flowInput = page.locator('section[aria-label="Input message"]');
await flowInput.locator('.view-lines').click({ position: { x: 40, y: 10 } });
await page.keyboard.press('Control+a');
await page.keyboard.insertText('<Order id="4711">\n  <Item sku="M-100" qty="2"/>\n  <Item sku="M-200" qty="1"/>\n</Order>\n');
await flowInput.getByRole('tab', { name: /^Headers/ }).click();
await flowInput.getByRole('button', { name: '+ Add' }).click();
await flowInput.getByLabel('Name').last().fill('SenderId');
await flowInput.getByLabel('Value').last().fill('ERP_100');
await flowInput.getByRole('tab', { name: /^Body/ }).click();
await page.getByRole('tab', { name: 'Content Modifier' }).click();
const config = page.locator('section[aria-label="Step configuration"]');
if (!process.env.CPIWB_IFLOW_ZIP) {
  // Without the iFlow's Content Modifier (4b): the same row by hand.
  await config.getByRole('button', { name: '+ Add' }).click();
  await config.getByLabel('Name').last().fill('OrderId');
  await config.getByLabel('Source Type').last().selectOption('xpath');
  await config.getByLabel('Source Value').last().fill('/Order/@id');
}
await page.getByRole('button', { name: 'Run step' }).click();
// The imported iFlow's Content Modifier (or the row above) sets the header OrderId from /Order/@id.
await page.locator('.res .row', { hasText: 'OrderId' }).filter({ hasText: '4711' }).waitFor({ timeout: 20000 })
  .catch(() => fail('Content Modifier: header OrderId is not 4711'));
// A variable-dependent XPath and a later Simple row must see the same exchange as the first Simple row.
for (const [name, type, value] of [['FirstExchangeId', 'expression', '${exchangeId}'],
  ['CopiedExchangeId', 'xpath', '$FirstExchangeId'], ['LastExchangeId', 'expression', '${exchangeId}']]) {
  await config.getByRole('button', { name: '+ Add' }).click();
  await config.getByLabel('Name', { exact: true }).last().fill(name);
  await config.getByLabel('Source Type').last().selectOption(type);
  await config.getByLabel('Source Value').last().fill(value);
}
await page.getByRole('button', { name: 'Run step' }).click();
const rowValue = (name) => page.locator('.res .row', { has: page.locator('b', { hasText: name }) }).locator('code').innerText({ timeout: 10000 });
const firstId = await rowValue('FirstExchangeId');
if (!firstId || firstId !== await rowValue('CopiedExchangeId') || firstId !== await rowValue('LastExchangeId')) {
  fail('Content Modifier did not keep one exchange across the XPath row');
}
await page.getByRole('tab', { name: 'Router' }).click();
if (await config.getByLabel('Route name').count()) fail('the Router does not start empty');
for (const [name, condition] of [['ERP', "${header.SenderId} = 'ERP_100'"], ['Default', '']]) {
  await config.getByRole('button', { name: '+ Route' }).click();
  await config.getByLabel('Route name').last().fill(name);
  if (condition) await config.getByLabel('Condition').last().fill(condition);
  else await config.getByLabel('Default route').last().check();
}
await page.getByRole('tab', { name: 'Splitter' }).click();
await config.getByLabel('Split expression').fill('/Order/Item');
await page.getByRole('tab', { name: 'Router' }).click();
await page.getByRole('button', { name: 'Run step' }).click();
await page.locator('.taken-line', { hasText: 'ERP' }).waitFor({ timeout: 10000 })
  .catch(() => fail('Router did not take the ERP route'));
await page.getByRole('tab', { name: 'Splitter' }).click();
await page.getByRole('button', { name: 'Run step' }).click();
await page.locator('.res h3', { hasText: '2 message(s)' }).waitFor({ timeout: 10000 })
  .catch(() => fail('General Splitter did not create 2 messages'));
// The split messages go to Gather; Combine writes SAP's multi-mapping format.
await page.locator('.res h3').getByRole('button', { name: 'Gather' }).click();
// The split messages are the input on the left, one body tab each.
await page.getByRole('tab', { name: 'Body 2' }).waitFor({ timeout: 5000 })
  .catch(() => fail('Gather: the split messages are not on the left as Body 1 and Body 2'));
// Set explicitly: opening the iFlow's Gather (4b) loaded Combine at XPath.
await page.getByLabel('Incoming Format').selectOption('sameXml');
await page.getByLabel('Aggregation Algorithm').selectOption('combine');
await page.getByRole('button', { name: 'Run step' }).click();
await page.locator('.res pre', { hasText: 'multimap:Message1' }).waitFor({ timeout: 10000 })
  .catch(() => fail('Gather did not combine the split messages'));
await page.getByRole('tab', { name: 'Filter' }).click();
await page.getByLabel('Filter XPath').fill('/Order/Item[@qty > 1]');
await page.getByLabel('Value Type').selectOption('nodelist');
await page.getByRole('button', { name: 'Run step' }).click();
await page.locator('.res h3', { hasText: '1 item(s) selected' }).waitFor({ timeout: 10000 })
  .catch(() => fail('Filter did not select the one item with qty > 1'));
// A substituted literal may contain the next Simple call's source; it must stay literal.
await flowInput.getByRole('tab', { name: /^Headers/ }).click();
for (const [name, value] of [['First', `marker function:simple('\${header.Qty}') and "quotes"`], ['Qty', '1']]) {
  await flowInput.getByRole('button', { name: '+ Add' }).click();
  await flowInput.getByLabel('Name').last().fill(name);
  await flowInput.getByLabel('Value').last().fill(value);
}
await flowInput.getByRole('tab', { name: /^Body/ }).click();
await page.getByLabel('Filter XPath').fill(`//Item[contains(function:simple("\${header.First}"), 'marker')][@qty = function:simple('\${header.Qty}')][@qty = function:simple('\${header.Qty}')]`);
await page.getByRole('button', { name: 'Run step' }).click();
await page.locator('.res pre', { hasText: 'M-200' }).waitFor({ timeout: 10000 });
// Message Digest: the header with the digest and the canonical form it is of; the body stays.
await page.getByRole('tab', { name: 'Message Digest' }).click();
await page.getByLabel('Digest Algorithm').selectOption('SHA256');
await page.getByRole('button', { name: 'Run step' }).click();
await page.locator('.res .row', { hasText: 'SAPMessageDigest' }).waitFor({ timeout: 10000 })
  .catch(async () => fail('Message Digest: ' + (await page.locator('.res').innerText()).slice(0, 300)));
if (!/^[A-Za-z0-9+/]{43}=$/.test(await page.locator('.res .row code').innerText())) fail('Message Digest: not a Base64 SHA-256 value');
await page.locator('.res pre', { hasText: '<Order id="4711"' }).waitFor({ timeout: 5000 })
  .catch(() => fail('Message Digest did not show the canonical form'));
await page.getByLabel('Digest filter XPath').fill('/Order/Item');
await page.getByRole('button', { name: 'Run step' }).click();
await page.locator('.res .d.error', { hasText: 'UnsupportedInSimulator' }).waitFor({ timeout: 10000 })
  .catch(() => fail('Message Digest: a filter that selects several nodes must be refused'));
await page.getByLabel('Digest filter XPath').fill('');
// Encoder: Base64 in lines; GZIP gives bytes, which travel through a Content Modifier to a Decoder.
// MIME Multipart is refused.
await page.getByRole('tab', { name: 'Encoder' }).click();
await page.getByRole('button', { name: 'Run step' }).click();
await page.locator('.res pre', { hasText: /^(PD94|PE9y)[A-Za-z0-9+/=\s]+$/ }).waitFor({ timeout: 10000 })
  .catch(async () => fail('Base64 Encode: ' + (await page.locator('.res').innerText()).slice(0, 300)));
await page.getByLabel('Encoding Scheme').selectOption('mimeMultipartEncode');
await page.getByRole('button', { name: 'Run step' }).click();
await page.locator('.res .d.error', { hasText: 'UnsupportedInSimulator' }).waitFor({ timeout: 10000 })
  .catch(() => fail('MIME Multipart Encode must be refused'));
await page.getByLabel('Encoding Scheme').selectOption('gzipCompress');
await page.getByRole('button', { name: 'Run step' }).click();
await page.locator('.res h3', { hasText: 'binary' }).waitFor({ timeout: 10000 })
  .catch(async () => fail('GZIP Compress: ' + (await page.locator('.res').innerText()).slice(0, 300)));
if (!/^00000000 +1f 8b 08/.test(await page.getByLabel('Binary output').innerText())) fail('GZIP Compress: no GZIP header in the hex view');
// Binary output must use the same create-new confirmation. The replacement then opens as binary input.
const gzipFile = path.join(project, 'archives/order.gz');
fs.mkdirSync(path.dirname(gzipFile), { recursive: true });
fs.writeFileSync(gzipFile, Buffer.from([0, 255, 7]));
await page.locator('.res h3', { hasText: 'binary' }).getByRole('button', { name: 'Save…' }).click();
await page.locator('.dlg input.mono').fill('archives/order.gz');
await page.locator('.dlg').getByRole('button', { name: 'OK' }).click();
const replaceGzip = page.getByRole('dialog').filter({ hasText: 'archives/order.gz exists. Overwrite it?' });
await replaceGzip.waitFor({ timeout: 5000 });
if (!fs.readFileSync(gzipFile).equals(Buffer.from([0, 255, 7]))) fail('binary output changed an existing archive before confirmation');
await replaceGzip.getByRole('button', { name: 'Overwrite', exact: true }).click();
const savedGz = page.locator('nav.tree button.file[title="archives/order.gz"]');
await savedGz.waitFor({ timeout: 5000 }).catch(() => fail('the saved archives/order.gz is not listed in the file tree'));
if (await savedGz.count()) {
  await savedGz.click();
  await page.getByLabel('Binary input body').filter({ hasText: 'archives/order.gz' }).waitFor({ timeout: 5000 })
    .catch(async () => fail('archives/order.gz did not open as the binary input: ' + await page.locator('.status, .error').allInnerTexts()));
}
await page.locator('.res h3', { hasText: 'binary' }).getByRole('button', { name: 'Use as input' }).click();
await page.getByLabel('Binary input body').waitFor({ timeout: 5000 }).catch(() => fail('The GZIP result did not become a binary input'));
await page.getByRole('tab', { name: 'Content Modifier' }).click();
// The previous result survived switching tabs. Replace XML-dependent rows with one header-only change.
await page.locator('.res .row', { hasText: 'OrderId' }).filter({ hasText: '4711' }).waitFor({ timeout: 5000 })
  .catch(() => fail('the Content Modifier result was lost after running the other steps'));
await config.getByRole('tab', { name: 'Message Header' }).click();
const removeHeader = config.getByRole('button', { name: 'Remove', exact: true });
while (await removeHeader.count()) await removeHeader.first().click();
await config.getByRole('button', { name: '+ Add' }).click();
await config.getByLabel('Name', { exact: true }).fill('ArchiveEncoding');
await config.getByLabel('Source Value').fill('gzip');
await config.getByRole('tab', { name: 'Exchange Property' }).click();
const removeProperty = config.getByRole('button', { name: 'Remove', exact: true });
while (await removeProperty.count()) await removeProperty.first().click();
await config.getByRole('tab', { name: 'Message Body' }).click();
await config.locator('textarea').fill('');
await page.getByRole('button', { name: 'Run step' }).click();
const binaryCm = page.locator('.res h3', { hasText: 'bytes, binary' });
await binaryCm.waitFor({ timeout: 10000 });
await binaryCm.getByRole('button', { name: 'Save…' }).click();
await page.locator('.dlg input.mono').fill('archives/modified.gz');
await page.locator('.dlg').getByRole('button', { name: 'OK' }).click();
await page.locator('nav.tree button.file[title="archives/modified.gz"]').waitFor({ timeout: 5000 });
if (!fs.readFileSync(path.join(project, 'archives/modified.gz')).equals(fs.readFileSync(gzipFile))) {
  fail('Header-only Content Modifier did not preserve the exact GZIP bytes');
}
await binaryCm.getByRole('button', { name: 'Use as input' }).click();
await page.getByLabel('Binary input body').waitFor({ timeout: 5000 });
await page.getByRole('tab', { name: 'Decoder' }).click();
await page.getByLabel('Decoding Scheme').selectOption('gzipDecompress');
await page.getByRole('button', { name: 'Run step' }).click();
const gunzipped = page.locator('.res pre', { hasText: '<Order id="4711"' });
const original = gunzipSync(fs.readFileSync(gzipFile)).toString('utf8');
if (await gunzipped.textContent({ timeout: 10000 }) !== original) fail('GZIP Decoder did not restore the exact text');
for (const [name, value] of [['ArchiveEncoding', 'gzip'], ['SenderId', 'ERP_100']]) {
  const header = page.locator('.res tr', { has: page.getByRole('cell', { name, exact: true }) });
  if (await header.locator('td.value').innerText({ timeout: 5000 }) !== value) fail(`Decoder did not receive header ${name}`);
}
// The decoded body back as the text input, for the steps below.
await page.locator('.res h3', { hasText: 'bytes' }).getByRole('button', { name: 'Use as input' }).click();
await page.getByLabel('Binary input body').waitFor({ state: 'detached', timeout: 5000 }).catch(() => fail('The decoded text did not replace the binary input'));
await page.getByLabel('Decoding Scheme').selectOption('base64Decode');
await page.getByRole('tab', { name: 'Encoder' }).click();
await page.getByLabel('Encoding Scheme').selectOption('base64Encode');
// Converter: XML to JSON on the sample body, the result back as input, then JSON to XML.
await page.getByRole('tab', { name: 'Converter' }).click();
await page.getByLabel('Converter', { exact: true }).selectOption('xmlToJson');
await page.getByRole('button', { name: 'Run step' }).click();
await page.locator('.res pre', { hasText: '{"Order":{"@id":"4711"' }).waitFor({ timeout: 10000 })
  .catch(async () => fail('XML to JSON: ' + (await page.locator('.res').innerText()).slice(0, 300)));
await page.locator('.res h3').getByRole('button', { name: 'Use as input' }).click();
await page.getByLabel('Converter', { exact: true }).selectOption('jsonToXml');
await page.getByRole('button', { name: 'Run step' }).click();
await page.locator('.res pre', { hasText: "<?xml version='1.0' encoding='UTF-8'?><Order id=\"4711\">" }).waitFor({ timeout: 10000 })
  .catch(async () => fail('JSON to XML: ' + (await page.locator('.res').innerText()).slice(0, 300)));
await page.locator('.res h3').getByRole('button', { name: 'Use as input' }).click();
// Jumping between the steps keeps each step's settings and result.
await page.getByRole('tab', { name: 'Gather' }).click();
await page.locator('.res pre', { hasText: 'multimap:Message1' }).waitFor({ timeout: 5000 })
  .catch(() => fail('the Gather result was lost after switching steps'));
if (shots) await page.screenshot({ path: path.join(shots, 'g4-flow-steps.png') });

// 6b) Diagram regressions: pan a selected element out of view, and deselect without changing iFlow.
if (process.env.CPIWB_IFLOW_ZIP) {
  // A second real import, not a mocked model. Keep the archive outside the disposable project.
  const secondZip = project + '-diagram-second.zip';
  fs.copyFileSync(process.env.CPIWB_IFLOW_ZIP, secondZip);
  try {
    await page.getByRole('button', { name: 'Import iFlow' }).click();
    await page.locator('.dlg input.mono').fill(secondZip);
    await page.locator('.dlg').getByRole('button', { name: 'OK' }).click();
    await page.getByRole('tab', { name: 'Diagram', exact: true }).click();
    const picker = page.getByLabel('iFlow', { exact: true });
    await picker.waitFor();
    const options = await picker.locator('option').evaluateAll((els) => els.map((e) => e.value));
    if (options.length !== 2) throw new Error('diagram regression needs two imported iFlows: ' + options);
    const shown = options[1]; // Not the first model: falling back to that model must fail this check.
    await picker.selectOption(shown);
    const modelPath = await page.locator('.diagram .bar .file').innerText();
    if (!modelPath.startsWith(`iflows/${shown}/`)) throw new Error('picker and rendered iFlow disagree: ' + modelPath);
    const diagram = page.locator('.diagram svg');
    const node = diagram.locator('[data-node="CallActivity_1"]');
    await node.click();
    await page.locator('.diagram .details').waitFor();
    const before = await diagram.getAttribute('viewBox');
    const rect = await diagram.boundingBox();
    for (let i = 0; i < 3; i++) {
      await page.mouse.move(rect.x + rect.width * 0.15, rect.y + rect.height * 0.5);
      await page.mouse.down();
      await page.mouse.move(rect.x + rect.width * 0.85, rect.y + rect.height * 0.5, { steps: 12 });
      await page.mouse.up();
    }
    const panned = await diagram.getAttribute('viewBox');
    const [bx, , bw] = before.split(' ').map(Number);
    const [px] = panned.split(' ').map(Number);
    if (!(bx - px > bw)) throw new Error(`selected element snapped back while panning: ${before} -> ${panned}`);
    // Let Svelte effects and browser rendering settle before checking that it stayed there.
    await page.evaluate(() => new Promise((resolve) => requestAnimationFrame(() => requestAnimationFrame(resolve))));
    if ((await diagram.getAttribute('viewBox')) !== panned) throw new Error('diagram snapped back after panning');
    if (!(await node.getAttribute('class')).includes('selected')) throw new Error('panning lost the selected node');
    if (shots) await page.screenshot({ path: path.join(shots, 'g6b-diagram-panned.png') });
    await page.getByRole('button', { name: 'Close details' }).click();
    await page.locator('.diagram .details').waitFor({ state: 'detached' });
    if ((await picker.inputValue()) !== shown) throw new Error('Close details switched to another iFlow');
    if ((await page.locator('.diagram .bar .file').innerText()) !== modelPath) throw new Error('Close details changed the rendered iFlow');
    if ((await diagram.getAttribute('viewBox')) !== panned) throw new Error('Close details reset the diagram view');
    await page.getByTitle('Show the whole iFlow').click();
    await node.click();
    await page.locator('.diagram .details').waitFor();
    const blank = await diagram.evaluate((svg) => {
      const r = svg.getBoundingClientRect();
      for (const dy of [0.05, 0.95, 0.5]) for (const dx of [0.05, 0.95, 0.5]) {
        const x = r.left + r.width * dx, y = r.top + r.height * dy;
        const el = document.elementFromPoint(x, y);
        if (el && svg.contains(el) && !el.closest('[data-node]')) return { x, y };
      }
      throw new Error('no empty point in the diagram');
    });
    await page.mouse.click(blank.x, blank.y);
    await page.locator('.diagram .details').waitFor({ state: 'detached' });
    if ((await picker.inputValue()) !== shown) throw new Error('background deselection switched to another iFlow');
    if ((await page.locator('.diagram .bar .file').innerText()) !== modelPath) throw new Error('background deselection changed the rendered iFlow');
    if (shots) await page.screenshot({ path: path.join(shots, 'g6b-diagram-deselected.png') });
    console.log('diagram regressions passed: selected pan, Close details, background deselection on second iFlow');
    // Where used: choosing an iFlow narrows the search hits already shown.
    await page.getByRole('tab', { name: 'Where used', exact: true }).click();
    const wuPicker = page.locator('.wu select[aria-label="iFlow"]');
    await wuPicker.selectOption('');
    await page.getByLabel('Search the iFlows').fill('s4.example');
    const summary = page.locator('.wu .body p.small').first();
    await page.locator('.wu section', { hasText: 'SOAP receiver' }).nth(1).waitFor({ timeout: 10000 })
      .catch(() => fail('Where used did not find s4.example in both iFlows'));
    await page.waitForTimeout(600); // the typed search's debounce (250 ms) must not run after the iFlow is chosen
    await wuPicker.selectOption(shown);
    await page.waitForFunction(() => document.querySelectorAll('.wu .body section').length === 1, null, { timeout: 10000 })
      .catch(async () => fail('Search kept the hits for all iFlows after one was chosen: ' + (await summary.innerText())));
  } finally {
    fs.rmSync(secondZip, { force: true });
  }
}

// 7) Closing the project closes it in the engines too: runs no longer see its platform data.
await page.getByTitle('Close project').click();
await page.getByLabel('Project folder').waitFor({ timeout: 5000 });
// The flow steps taken from the project's iFlows close with it.
if (process.env.CPIWB_IFLOW_ZIP) {
  await page.getByRole('tab', { name: 'Flow Steps' }).click();
  for (const step of ['Content Modifier', 'Filter']) {
    await page.getByRole('tab', { name: step, exact: true }).click();
    if (await page.locator('.src').count()) fail(`${step} still shows "${await page.locator('.src').innerText()}" after Close project`);
  }
}
// A scratch Groovy run after Close project must not carry the project's root to either engine.
await page.getByRole('tab', { name: 'Groovy Script', exact: true }).click();
const afterClose = page.waitForRequest((req) => groovyRpc.test(req.url()) && req.postDataJSON()?.method === 'groovy.run');
await runIt();
if ((await afterClose).postDataJSON().projectRoot !== null) fail('Groovy run after Close project still names a project');

// 8) Without a project, Import iFlow… creates a new project folder and imports into it; an existing folder is refused.
if (process.env.CPIWB_IFLOW_ZIP) {
  const fresh = project + '-new-from-export';
  fs.rmSync(fresh, { recursive: true, force: true });
  const importIntoNew = async () => {
    await page.getByRole('button', { name: 'Import iFlow…' }).click();
    await page.locator('.dlg input.mono').fill(process.env.CPIWB_IFLOW_ZIP);
    await page.locator('.dlg').getByRole('button', { name: 'OK' }).click();
    await page.locator('.dlg', { hasText: 'New project folder' }).locator('input.mono').fill(fresh);
    await page.locator('.dlg').getByRole('button', { name: 'OK' }).click();
  };
  try {
    await importIntoNew();
    await page.locator('.steps .step', { hasText: 'Enrich order' }).waitFor({ timeout: 10000 })
      .catch(async () => fail('import into a new project: ' + (await page.locator('.status').innerText()) + ' ' + (await page.locator('.banner.error').allInnerTexts())));
    if ((await page.locator('.proj .root').innerText()) !== path.basename(fresh)) fail('the new project is not the one open');
    if (!fs.existsSync(path.join(fresh, 'cpi-workbench.json'))) fail('the new project was not initialized');
    await page.getByTitle('Close project').click();
    await importIntoNew();
    await page.locator('.banner.error', { hasText: 'exists already' }).waitFor({ timeout: 5000 })
      .catch(() => fail('importing into an existing folder was not refused'));
    await page.locator('.banner.error').getByRole('button', { name: 'Dismiss' }).click();
    if (await page.locator('.proj .root').count()) fail('a refused new project was opened anyway');
  } finally {
    fs.rmSync(fresh, { recursive: true, force: true });
  }
}

if (errors.length) fail('browser errors: ' + errors.join(' | '));
await browser.close();
console.log(process.exitCode ? 'groovy e2e FAILED' : 'groovy e2e passed');
