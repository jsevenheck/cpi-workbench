// Engine errors the UI handles by kind. The desktop shell passes only an error's message; browser mode also gets the
// engine's `type` (the exception class) and, should an engine send one, a `code`. So each check takes the code or
// type when it is there and falls back to the start of the message, which ProjectFiles words the same on both.

type ErrorLike = { message?: unknown; type?: unknown; code?: unknown };

function parts(e: unknown): { message: string; type: string; code: string } {
  const x = (typeof e === 'object' && e !== null ? e : { message: e }) as ErrorLike;
  const str = (v: unknown) => (typeof v === 'string' ? v : '');
  return { message: str(x.message).trimStart(), type: str(x.type), code: str(x.code) };
}

/** `file.write` with `createNew` found the file there already ("exists already: <path>"). */
export function isExistsConflict(e: unknown): boolean {
  const { message, type, code } = parts(e);
  return code === 'exists' || type === 'FileAlreadyExistsException' || message.startsWith('exists already:');
}

/** The selected project folder is gone (deleted, moved or renamed) since it was opened. */
export function isProjectUnavailable(e: unknown): boolean {
  const { message, code } = parts(e);
  return code === 'projectUnavailable' || message.startsWith('Project folder is unavailable');
}

export const PROJECT_UNAVAILABLE_NOTE =
  'The project folder is unavailable: it was moved, renamed or deleted. Close the project and open the folder again.';
