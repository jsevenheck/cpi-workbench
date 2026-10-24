// Bridge to the engine processes. In the desktop app (Tauri) calls go through the Rust shell,
// which supervises one engine process per profile. In web mode the engine itself serves the UI
// and answers on /rpc (single profile).

export type Profile = 'saxon-he-9.9' | 'saxon-he-12';

export interface ProfileInfo {
  id: Profile;
  label: string;
  available: boolean;
  note?: string;
}

export const PROFILES: ProfileInfo[] = [
  { id: 'saxon-he-9.9', label: 'CPI 9.9 (Saxon-HE 9.9)', available: true,
    note: 'Same Saxon release line as Cloud Integration (Saxon-EE 9.9). No higher-order functions in HE.' },
  { id: 'saxon-he-12', label: 'Modern HE (Saxon-HE 12)', available: true,
    note: 'Supports higher-order functions and xsl:evaluate, which CPI\'s Saxon-EE 9.9 has but HE 9.9 lacks.' },
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

export class EngineError extends Error {
  constructor(message: string, public timeout = false) {
    super(message);
  }
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
  const res = await fetch('./rpc', {
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
export async function call<T = any>(method: string, params: unknown = {}, profile: Profile = DEFAULT_PROFILE): Promise<T> {
  const target = isDesktop ? profile : (webProfile ?? profile);
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
  const r = await rawCall(isDesktop ? DEFAULT_PROFILE : (webProfile ?? DEFAULT_PROFILE), 'project.open', { dir, init });
  projectDir = r.root;
  projectOpenedIn.clear();
  projectOpenedIn.add(isDesktop ? DEFAULT_PROFILE : (webProfile ?? DEFAULT_PROFILE));
  return r;
}

/** Which profiles can actually run here. Web mode serves exactly one engine. */
export async function availableProfiles(): Promise<{ profiles: ProfileInfo[]; info: any }> {
  if (isDesktop) {
    const { invoke } = await import('@tauri-apps/api/core');
    const ids = (await invoke('engine_profiles')) as Profile[];
    const info = await rawCall(DEFAULT_PROFILE, 'engine.info', {}).catch(() => null);
    return { profiles: PROFILES.map((p) => ({ ...p, available: ids.includes(p.id) })), info };
  }
  const info = await rawCall(DEFAULT_PROFILE, 'engine.info', {});
  webProfile = info.profile;
  return {
    profiles: PROFILES.map((p) => ({ ...p, available: p.id === info.profile,
      note: p.id === info.profile ? p.note : 'Web mode runs one engine. Start cpiwb-xml for this profile to use it.' })),
    info,
  };
}

export async function pickFolder(): Promise<string | null> {
  if (!isDesktop) return null;
  const { open } = await import('@tauri-apps/plugin-dialog');
  const dir = await open({ directory: true, multiple: false, title: 'Open project folder' });
  return typeof dir === 'string' ? dir : null;
}
