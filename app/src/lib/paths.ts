/**
 * A project-relative path in one spelling, for comparing paths: `/` separators, no `.` or empty
 * segments, `..` applied, lower case. Windows and macOS file systems ignore case by default, so
 * `Scripts/./A.groovy` and `scripts/a.groovy` count as the same file (on Linux this only means one
 * question too many).
 */
export function pathKey(path: string): string {
  const parts: string[] = [];
  for (const p of path.replace(/\\/g, '/').split('/')) {
    if (p === '..') parts.pop();
    else if (p !== '.' && p !== '') parts.push(p);
  }
  return parts.join('/').toLowerCase();
}
