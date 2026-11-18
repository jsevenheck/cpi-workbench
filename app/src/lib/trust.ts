/** An engine URL of browser mode's ?engines= that is on this machine (plain http, no credentials). */
export function isLocalEngine(url: string): boolean {
  try {
    const u = new URL(url);
    return u.protocol === 'http:' && ['localhost', '127.0.0.1', '[::1]'].includes(u.hostname) && !u.username && !u.password;
  } catch {
    return false;
  }
}
