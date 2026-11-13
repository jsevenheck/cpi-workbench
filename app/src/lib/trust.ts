// What the app runs or contacts without being asked: nothing that came from someone else.

/** A stylesheet with reflexive Java calls (xmlns:x="java:java.lang.Runtime"): running it runs JDK code. */
export function callsJava(stylesheet: string): boolean {
  return /xmlns:[\w.-]+\s*=\s*["']java:/.test(stylesheet);
}

/** An engine URL of browser mode's ?engines= that is on this machine (plain http, no credentials). */
export function isLocalEngine(url: string): boolean {
  try {
    const u = new URL(url);
    return u.protocol === 'http:' && ['localhost', '127.0.0.1', '[::1]'].includes(u.hostname) && !u.username && !u.password;
  } catch {
    return false;
  }
}
