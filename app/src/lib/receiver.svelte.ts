/**
 * The desktop app's receiver for CPI Helper's links (docs/plan.md W2b; the server is
 * `src-tauri/src/receiver.rs`). Off until the user turns it on under ⚙. A link that arrives is shown in a
 * dialog first and imported only on **Import**, as **Import…** would; nothing runs by itself.
 */
import { isDesktop } from './engine';
import { app, closeDialog, confirm, dialog, importMessage } from './store.svelte';
import { bodyWarning, readShared, RECEIVER_DEFAULT_PORT, sharedSummary, type SharedMessage } from './share';

const KEY = 'cpiwb.receiver';

export const receiver = $state({
  /** The user's choice, kept across starts. */
  on: false,
  port: RECEIVER_DEFAULT_PORT,
  /** Listening now. */
  running: false,
  error: '',
});

/** The plugins' Custom URL: GroovyDebugX appends the data, xsltDebugX adds `#share/<data>` itself. */
export function customUrls(port: number) {
  return { groovy: `http://127.0.0.1:${port}/#share/`, xslt: `http://127.0.0.1:${port}/` };
}

/** At start-up (desktop only): listens for links, and starts the receiver if it was on. */
export async function initReceiver() {
  if (!isDesktop) return;
  try {
    const saved = JSON.parse(localStorage.getItem(KEY) ?? 'null');
    if (saved && Number.isInteger(saved.port)) Object.assign(receiver, { on: saved.on === true, port: saved.port });
  } catch { /* defaults */ }
  const { listen } = await import('@tauri-apps/api/event');
  await listen<string>('cpi-helper-link', (e) => void received(e.payload));
  if (receiver.on) await applyReceiver();
}

/** Turns the receiver on or off at the chosen port; a port that cannot be used leaves it off, with the reason. */
export function setReceiver(on: boolean, port: number): Promise<void> {
  Object.assign(receiver, { on, port });
  try { localStorage.setItem(KEY, JSON.stringify({ on, port })); } catch { /* not kept */ }
  return applyReceiver();
}

// One switch at a time, each for the setting as it is when its turn comes: an older switch can neither
// finish after a newer one nor stop the receiver a newer one started.
let switching: Promise<void> = Promise.resolve();

function applyReceiver(): Promise<void> {
  switching = switching.then(switchReceiver);
  return switching;
}

async function switchReceiver() {
  const { invoke } = await import('@tauri-apps/api/core');
  receiver.error = '';
  try {
    if (receiver.on && !(Number.isInteger(receiver.port) && receiver.port >= 1024 && receiver.port <= 65535)) {
      throw new Error('Choose a port from 1024 to 65535.');
    }
    await invoke('receiver_set', { port: receiver.on ? receiver.port : null });
    receiver.running = receiver.on;
  } catch (e) {
    await invoke('receiver_set', { port: null }).catch(() => undefined);
    receiver.running = false;
    receiver.error = e instanceof Error ? e.message : String(e);
  }
}

// One confirmation at a time, for the newest link. Each link that arrives counts up `generation`; after each
// await the loop checks it, so a link that arrives while an older one is unpacked or shown replaces it. A
// question the app is asking already (e.g. about unsaved changes) is answered first.
let pending: string | null = null;
let generation = 0;
/** A link was taken from `pending` and not yet answered: a newer one replaces it. */
let undecided = false;
let replaced = false;
let showing = false;
let working = false;

async function received(data: string) {
  if (pending !== null || undecided) replaced = true;
  pending = data;
  generation++;
  if (showing) closeDialog(false);
  if (working) return;
  working = true;
  try {
    while (pending !== null) {
      while (dialog.open) await new Promise((r) => setTimeout(r, 250));
      const mine = generation;
      const link = '#share/' + pending;
      pending = null;
      undecided = true;
      try {
        await confirmAndImport(link, () => mine === generation);
      } finally {
        undecided = false;
      }
    }
  } finally {
    working = false;
  }
}

async function confirmAndImport(link: string, current: () => boolean) {
  let m: SharedMessage;
  try {
    m = await readShared(link);
  } catch (e) {
    if (current()) app.error = 'A browser tab sent a message that could not be read: ' + (e as Error).message;
    return;
  }
  if (!current()) return;
  const warning = bodyWarning(m.body);
  const note = replaced ? 'It replaces a message that arrived before.\n\n' : '';
  replaced = false;
  showing = true;
  const ok = await confirm(note + describe(m, warning), 'Import');
  showing = false;
  undecided = false; // answered: a link that arrives during the import follows it, it does not replace it
  // The import runs nothing: not the script or mapping that came along, nor the open one with Live on.
  if (ok && current()) await importMessage(m, { rerun: false, warning });
}

/** The dialog's text: from where (a browser tab: any page can open the link), what, into which tab. */
export function describe(m: SharedMessage, warning: string): string {
  const tab = m.kind === 'groovy' ? 'the Groovy tab' : m.kind === 'xslt' ? 'the XSLT tab' : 'the open tab';
  const plugin = m.kind === 'groovy' ? 'a GroovyDebugX link' : m.kind === 'xslt' ? 'an xsltDebugX link' : 'a link';
  const what = sharedSummary(m).replace(/^body/, `body (${size(m.body.length)})`) || 'nothing';
  const replaces = [m.body && 'input body', m.headers.length && 'headers', m.properties.length && 'properties',
    m.source && (m.kind === 'xslt' ? 'mapping' : 'script')].filter(Boolean).join(', ').replace(/, ([^,]*)$/, ' and $1');
  return `A browser tab sent a message for ${tab} (${plugin}, as CPI Helper opens one): ${what}.\n\n`
    + `Import it? It replaces the tab's ${replaces || 'nothing'}. Nothing runs until you start it.`
    + (warning ? `\n\nWarning: ${warning}` : '');
}

function size(chars: number): string {
  return chars < 1024 ? `${chars} characters` : chars < 1024 * 1024 ? `${Math.round(chars / 1024)} KB` : `${(chars / 1024 / 1024).toFixed(1)} MB`;
}
