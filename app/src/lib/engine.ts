// Bridge to the engine processes. In the desktop app (Tauri) calls go through the Rust shell,
// which supervises one engine process per profile. In web mode the engine itself serves the UI
// and answers on /project/rpc. Both transports carry the selected project on every request.

import { isLocalEngine } from './trust';
import { isProjectUnavailable, PROJECT_UNAVAILABLE_NOTE } from './errors';

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
    note: 'Same Saxon release line as Cloud Integration (Saxon-EE 9.9.1.6). No higher-order functions in HE.' },
  { id: 'saxon-he-12', kind: 'xslt', label: 'Modern HE (Saxon-HE 12)', available: true,
    note: 'Supports higher-order functions and xsl:evaluate, which CPI\'s Saxon-EE 9.9 has but HE 9.9 lacks.' },
  { id: 'groovy-1.x', kind: 'groovy', label: 'Groovy Script 1.x (Groovy 2.4.21)', available: true,
    note: 'Script API v1 (com.sap.gateway.ip.core.customdev.util.Message), Camel 3.14 type conversion, as on a tenant.' },
  { id: 'groovy-2.x', kind: 'groovy', label: 'Groovy Script 2.x (Groovy 4.0.29)', available: true,
    note: 'Script API v2 (com.sap.it.script.v2.api.Message), Camel 3.14 type conversion.' },
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
let webProfile: Profile | null = null;
/** Engine used for project/file operations (desktop: the XSLT engine if installed). */
let fileProfile: Profile = DEFAULT_PROFILE;

export class EngineError extends Error {
  /** Browser mode only: the engine's error `type` (exception class) and `code`, when it sends them. */
  constructor(message: string, readonly type?: string, readonly code?: string) {
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
    // Engines on this machine only: a link with a foreign engine would send mappings and messages there.
    if (at > 0 && isLocalEngine(part.slice(at + 1))) webEngines.set(part.slice(0, at) as Profile, part.slice(at + 1).replace(/\/$/, ''));
  }
}

function rpcUrl(profile: Profile): string {
  const base = webEngines.get(profile);
  return base ? `${base}/project/rpc` : './project/rpc';
}

async function rawCall(profile: Profile, method: string, params: unknown, projectRoot = projectDir): Promise<any> {
  if (isDesktop) {
    const { invoke } = await import('@tauri-apps/api/core');
    try {
      return await invoke('engine_call', { profile, method, params, projectRoot });
    } catch (e) {
      const msg = typeof e === 'string' ? e : (e as Error).message ?? String(e);
      throw new EngineError(msg);
    }
  }
  const body = JSON.stringify({ id: nextId++, method, params, projectRoot });
  const res = await fetch(rpcUrl(profile), {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'X-Cpiwb': '1' },
    body,
  }).catch(() => { throw new EngineError('No response from the local engine. Check that it is running and ready.'); });
  if (!res.headers.get('content-type')?.startsWith('application/json')) {
    if (!res.ok) throw new EngineError(`Engine HTTP ${res.status}`);
    throw new EngineError('The engine does not support browser project requests. Use an engine built with this app.');
  }
  const json = await res.json();
  const err = json?.error;
  if (typeof err?.message === 'string') {
    throw new EngineError(err.message, typeof err.type === 'string' ? err.type : undefined, typeof err.code === 'string' ? err.code : undefined);
  }
  if (!res.ok) throw new EngineError(`Engine HTTP ${res.status}`);
  return json.result;
}

/** A run or write is sent once, with the root selected when the call was made. */
export function call<T = any>(method: string, params: unknown = {}, profile = fileProfile): Promise<T> {
  return callInProject(method, params, profile, projectDir);
}

function callInProject(method: string, params: unknown, profile: Profile, root: string | null) {
  const target = isDesktop || webEngines.has(profile) ? profile : (webProfile ?? profile);
  return rawCall(target, method, params, root);
}

/** Keeps all RPCs of a multi-engine run in the project it started in. */
export function flowEngineCall() {
  const root = projectDir;
  return (method: string, params: unknown, profile: Profile) => callInProject(method, params, profile, root);
}

export async function openProject(dir: string, init = false, create = false): Promise<any> {
  const target = isDesktop ? fileProfile : (webProfile ?? DEFAULT_PROFILE);
  const r = await rawCall(target, 'project.open', { dir, init, create }, null);
  projectDir = r.root;
  return r;
}

/** Only the UI keeps a selected project. In-flight calls still have their own snapshot. */
export function closeProject(): void {
  projectDir = null;
}

/** Which profiles can actually run here. Web mode serves exactly one engine. */
export async function availableProfiles(): Promise<{ profiles: ProfileInfo[]; info: any }> {
  if (isDesktop) {
    const { invoke } = await import('@tauri-apps/api/core');
    const ids = (await invoke('engine_profiles')) as Profile[];
    const first = (ids.includes(DEFAULT_PROFILE) ? DEFAULT_PROFILE : ids[0]) as Profile | undefined;
    if (first) fileProfile = first;
    const info = first ? await rawCall(first, 'engine.info', {}, null).catch(() => null) : null;
    return { profiles: PROFILES.map((p) => ({ ...p, available: ids.includes(p.id) })), info };
  }
  // Web mode: the engine serving this page handles file operations; further engines may be
  // configured with ?engines=profile@url,...
  // engine.info needs no project: asking without one keeps a deleted or renamed project folder from making a
  // running engine look absent. An engine that still reports the folder gets that note instead.
  const unavailable = (e: unknown) => {
    if (isProjectUnavailable(e)) throw new EngineError(PROJECT_UNAVAILABLE_NOTE);
    throw e;
  };
  const info = await rawCall(DEFAULT_PROFILE, 'engine.info', {}, null).catch(unavailable);
  webProfile = info.profile;
  fileProfile = info.profile;
  const available = new Set<string>([info.profile]);
  const notes = new Map<string, string>();
  for (const [id] of webEngines) {
    try {
      const other = await rawCall(id, 'engine.info', {}, null);
      if (other.profile === id) available.add(id);
    } catch (e) {
      // Otherwise the engine is not running or incompatible.
      if (isProjectUnavailable(e)) notes.set(id, PROJECT_UNAVAILABLE_NOTE);
    }
  }
  return {
    profiles: PROFILES.map((p) => ({ ...p, available: available.has(p.id),
      note: available.has(p.id) ? p.note
        : notes.get(p.id) ?? 'No compatible engine connected. In web mode add it with ?engines=' + p.id + '@http://127.0.0.1:<port>' })),
    info,
  };
}

export async function pickFolder(): Promise<string | null> {
  if (!isDesktop) return null;
  const { open } = await import('@tauri-apps/plugin-dialog');
  const dir = await open({ directory: true, multiple: false, title: 'Open project folder' });
  return typeof dir === 'string' ? dir : null;
}

/** Asks where a new project folder goes; the engine creates it (and refuses one that exists). */
export async function pickNewFolder(name: string): Promise<string | null> {
  if (!isDesktop) return null;
  const { save } = await import('@tauri-apps/plugin-dialog');
  const dir = await save({ title: 'Create a project folder for the import', defaultPath: name });
  return typeof dir === 'string' ? dir : null;
}

export async function pickZip(): Promise<string | null> {
  if (!isDesktop) return null;
  const { open } = await import('@tauri-apps/plugin-dialog');
  const f = await open({ multiple: false, directory: false, title: 'Import an exported iFlow, Value Mapping or package',
    filters: [{ name: 'Integration Suite export', extensions: ['zip'] }] });
  return typeof f === 'string' ? f : null;
}
