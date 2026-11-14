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

/** `rel` relative to `dir`, as a project path; above the project root it keeps its leading `..` parts. */
export function projectPath(dir: string, rel: string): string {
  // An absolute path (/x, C:\x, C:/x, \\server\share) stays as written: the engine refuses it as outside the
  // project, as for the test runner, instead of reading a file of that name inside the project.
  if (/^([\\/]|[A-Za-z]:)/.test(rel)) return rel;
  rel = rel.replace(/\\/g, '/');
  const parts: string[] = [];
  let up = 0;
  for (const p of [...dir.split('/'), ...rel.split('/')]) {
    if (p === '..') {
      if (parts.length) parts.pop();
      else up++;
    } else if (p !== '.' && p !== '') parts.push(p);
  }
  return [...Array(up).fill('..'), ...parts].join('/');
}
