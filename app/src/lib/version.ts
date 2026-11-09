/** SemVer precedence (semver.org §11) of two versions such as "0.3.0", "v0.3.0" or "1.0.0-rc.1"; build metadata is ignored. */
export function compareVersions(a: string, b: string): number {
  const pa = parse(a);
  const pb = parse(b);
  for (let i = 0; i < 3; i++) if (pa.core[i] !== pb.core[i]) return pa.core[i] < pb.core[i] ? -1 : 1;
  // A pre-release ranks below its release.
  if (!pa.pre.length || !pb.pre.length) return (pb.pre.length ? 1 : 0) - (pa.pre.length ? 1 : 0);
  for (let i = 0; i < Math.max(pa.pre.length, pb.pre.length); i++) {
    const x = pa.pre[i];
    const y = pb.pre[i];
    if (x === undefined) return -1;
    if (y === undefined) return 1;
    if (x === y) continue;
    const nx = /^\d+$/.test(x);
    const ny = /^\d+$/.test(y);
    if (nx && ny) return Number(x) < Number(y) ? -1 : 1;
    if (nx !== ny) return nx ? -1 : 1;
    return x < y ? -1 : 1;
  }
  return 0;
}

function parse(v: string) {
  const m = /^v?(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$/.exec(v.trim());
  if (!m) throw new Error(`Not a version: ${v}`);
  return { core: [Number(m[1]), Number(m[2]), Number(m[3])], pre: m[4] ? m[4].split('.') : [] };
}

export const RELEASES_API = 'https://api.github.com/repos/jsevenheck/cpi-workbench/releases/latest';
export const RELEASES_PAGE = 'https://github.com/jsevenheck/cpi-workbench/releases/';

export type UpdateCheck = { newer: false; latest: string } | { newer: true; latest: string; url: string };

/** Asks GitHub for the latest release (drafts and pre-releases are not "latest") and compares it with `current`. */
export async function checkForUpdate(current: string, fetchFn: typeof fetch = fetch): Promise<UpdateCheck> {
  const res = await fetchFn(RELEASES_API, { headers: { Accept: 'application/vnd.github+json' } });
  if (!res.ok) throw new Error(await failure(res));
  const release = (await res.json()) as { tag_name?: string; html_url?: string };
  const latest = String(release.tag_name ?? '').replace(/^v/, '');
  if (compareVersions(latest, current) <= 0) return { newer: false, latest };
  const url = typeof release.html_url === 'string' && release.html_url.startsWith(RELEASES_PAGE) ? release.html_url : RELEASES_PAGE;
  return { newer: true, latest, url };
}

/**
 * Why GitHub refused, in words: its own `message` (JSON) if there is one, and for a used-up rate limit
 * (403 or 429 with `x-ratelimit-remaining: 0`) what that means. Without sign-in GitHub allows 60 requests
 * per hour per network address, which a shared company network can use up.
 */
async function failure(res: Response): Promise<string> {
  let message = '';
  try {
    const body = (await res.json()) as { message?: unknown };
    if (typeof body.message === 'string') message = body.message;
  } catch {
    // not JSON, e.g. a proxy's HTML page
  }
  const head = `GitHub answered ${res.status} ${res.statusText}`.trim() + (message ? `: ${message}` : '');
  if ((res.status === 403 || res.status === 429) && res.headers.get('x-ratelimit-remaining') === '0') {
    return `${head}\nGitHub allows 60 requests per hour per network address without sign-in; a shared network may have used them up. Try again later or open the releases page.`;
  }
  return head;
}
