// Bridge to the engine processes. In the desktop app (Tauri) calls go through the Rust shell,
// which supervises one engine process per profile. In web mode the engine itself serves the UI
// and answers on /rpc (single profile).

export type Profile = 'saxon-he-9.9' | 'saxon-he-12' | 'groovy-1.x' | 'groovy-2.x';
export type Kind = 'xslt' | 'groovy';

export interface ProfileInfo {
  id: Profile;
  kind: Kind;
  label: string;
  available: boolean;
  note?: string;
}

export const PROFILES: ProfileInfo[] = [
  { id: 'saxon-he-9.9', kind: 'xslt', label: 'CPI 9.9 (Saxon-HE 9.9)', available: true,
    note: 'Same Saxon release line as Cloud Integration (Saxon-EE 9.9). No higher-order functions in HE.' },
  { id: 'saxon-he-12', kind: 'xslt', label: 'Modern HE (Saxon-HE 12)', available: true,
    note: 'Supports higher-order functions and xsl:evaluate, which CPI\'s Saxon-EE 9.9 has but HE 9.9 lacks.' },
  { id: 'groovy-1.x', kind: 'groovy', label: 'Groovy Script 1.x (Groovy 2.4.21)', available: true,
    note: 'Script API v1 (com.sap.gateway.ip.core.customdev.util.Message), Camel 2 type conversion.' },
  { id: 'groovy-2.x', kind: 'groovy', label: 'Groovy Script 2.x (Groovy 4.0.29)', available: true,
    note: 'Script API v2 (com.sap.it.script.v2.api.Message, provisional method list), Camel 3.14 type conversion.' },
];

export const DEFAULT_PROFILE: Profile = 'saxon-he-9.9';

declare global {
  interface Window {
    __TAURI_INTERNALS__?: unknown;
  }
}

export const isDesktop = typeof window !== 'undefined' && window.__TAURI_INTERNALS__ !== undefined;

let nextId = 1;
let projectDir: string | null = null;
const projectOpenedIn = new Set<Profile>();
let webProfile: Profile | null = null;
/** Engine used for project/file operations (desktop: the XSLT engine if installed). */
let fileProfile: Profile = DEFAULT_PROFILE;

export class EngineError extends Error {
  constructor(message: string, public timeout = false) {
    super(message);
  }
}

/**
 * Web mode can use more than the engine that serves the page:
 *   http://127.0.0.1:7777/?engines=groovy-1.x@http://127.0.0.1:7778,groovy-2.x@http://127.0.0.1:7779
 */
const webEngines = new Map<Profile, string>();
if (!isDesktop && typeof location !== 'undefined') {
  const spec = new URLSearchParams(location.search).get('engines') ?? '';
  for (const part of spec.split(',').map((x) => x.trim()).filter(Boolean)) {
    const at = part.indexOf('@');
    if (at > 0) webEngines.set(part.slice(0, at) as Profile, part.slice(at + 1).replace(/\/$/, ''));
  }
}

function rpcUrl(profile: Profile): string {
  const base = webEngines.get(profile);
  return base ? `${base}/rpc` : './rpc';
}

async function rawCall(profile: Profile, method: string, params: unknown): Promise<any> {
  if (isDesktop) {
    const { invoke } = await import('@tauri-apps/api/core');
    try {
      return await invoke('engine_call', { profile, method, params });
    } catch (e) {
      const msg = typeof e === 'string' ? e : (e as Error).message ?? String(e);
      throw new EngineError(msg, msg.startsWith('Timeout'));
    }
  }
  const res = await fetch(rpcUrl(profile), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Cpiwb': '1' },
    body: JSON.stringify({ id: nextId++, method, params }),
  });
  if (!res.ok) throw new EngineError(`Engine HTTP ${res.status}`);
  const json = await res.json();
  if (json.error) throw new EngineError(json.error.message, !!json.error.timeout);
  return json.result;
}

/** Calls an engine method; makes sure the open project is known to that engine first. */
export async function call<T = any>(method: string, params: unknown = {}, profile?: Profile): Promise<T> {
  profile = profile ?? fileProfile;
  const target = isDesktop || webEngines.has(profile) ? profile : (webProfile ?? profile);
  if (projectDir && !projectOpenedIn.has(target) && method !== 'project.open') {
    await rawCall(target, 'project.open', { dir: projectDir });
    projectOpenedIn.add(target);
  }
  try {
    return await rawCall(target, method, params);
  } catch (e) {
    // An engine restarted after a timeout has forgotten the project.
    if (e instanceof EngineError && e.timeout) projectOpenedIn.delete(target);
    if (e instanceof EngineError && /No project folder is open/.test(e.message) && projectDir) {
      projectOpenedIn.delete(target);
      await rawCall(target, 'project.open', { dir: projectDir });
      projectOpenedIn.add(target);
      return rawCall(target, method, params);
    }
    throw e;
  }
}

export async function openProject(dir: string, init = false): Promise<any> {
  const target = isDesktop ? fileProfile : (webProfile ?? DEFAULT_PROFILE);
  const r = await rawCall(target, 'project.open', { dir, init });
  projectDir = r.root;
  projectOpenedIn.clear();
  projectOpenedIn.add(target);
  return r;
}

/** Which profiles can actually run here. Web mode serves exactly one engine. */
export async function availableProfiles(): Promise<{ profiles: ProfileInfo[]; info: any }> {
  if (isDesktop) {
    const { invoke } = await import('@tauri-apps/api/core');
    const ids = (await invoke('engine_profiles')) as Profile[];
    const first = (ids.includes(DEFAULT_PROFILE) ? DEFAULT_PROFILE : ids[0]) as Profile | undefined;
    if (first) fileProfile = first;
    const info = first ? await rawCall(first, 'engine.info', {}).catch(() => null) : null;
    return { profiles: PROFILES.map((p) => ({ ...p, available: ids.includes(p.id) })), info };
  }
  // Web mode: the engine serving this page handles file operations; further engines may be
  // configured with ?engines=profile@url,...
  const info = await rawCall(DEFAULT_PROFILE, 'engine.info', {});
  webProfile = info.profile;
  fileProfile = info.profile;
  const available = new Set<string>([info.profile]);
  for (const [id] of webEngines) {
    try {
      const other = await rawCall(id, 'engine.info', {});
      if (other.profile === id) available.add(id);
    } catch {
      /* engine not running */
    }
  }
  return {
    profiles: PROFILES.map((p) => ({ ...p, available: available.has(p.id),
      note: available.has(p.id) ? p.note : 'Not running. In web mode add it with ?engines=' + p.id + '@http://127.0.0.1:<port>' })),
    info,
  };
}

export async function pickFolder(): Promise<string | null> {
  if (!isDesktop) return null;
  const { open } = await import('@tauri-apps/plugin-dialog');
  const dir = await open({ directory: true, multiple: false, title: 'Open project folder' });
  return typeof dir === 'string' ? dir : null;
}

export async function pickZip(): Promise<string | null> {
  if (!isDesktop) return null;
  const { open } = await import('@tauri-apps/plugin-dialog');
  const f = await open({ multiple: false, directory: false, title: 'Import exported iFlow',
    filters: [{ name: 'iFlow export', extensions: ['zip'] }] });
  return typeof f === 'string' ? f : null;
}
