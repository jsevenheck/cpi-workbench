/**
 * The mocked platform data of a project (`platform/*.json`, docs/test-cases.md) as table rows for the Groovy
 * tab, and back. The JSON files stay the source of truth: rows are read from a file's document and written
 * into it again, and whatever the tables do not show (OAuth fields of a credential, binary Partner Directory
 * parameters, alternative partners, unknown members) is kept as it is in the file.
 *
 * Also the rows of a script's further inputs, attachments and SOAP headers, in the form test cases use.
 */

export type PlatformKey = 'valueMappings' | 'credentials' | 'partnerDirectory' | 'numberRanges';

/** The keys of a run's `platform` object and of a test case's override, with their files. */
export const PLATFORM_FILES: Record<PlatformKey | 'keystore', string> = {
  valueMappings: 'platform/value-mappings.json',
  credentials: 'platform/credentials.json',
  partnerDirectory: 'platform/partner-directory.json',
  numberRanges: 'platform/number-ranges.json',
  keystore: 'platform/keystore.json',
};

export const PLATFORM_KEYS: PlatformKey[] = ['valueMappings', 'credentials', 'partnerDirectory', 'numberRanges'];

type Obj = Record<string, unknown>;
const isObj = (v: unknown): v is Obj => typeof v === 'object' && v !== null && !Array.isArray(v);
const text = (v: unknown): string => (v === undefined || v === null ? '' : String(v));

// ------------------------------------------------------------------ value mappings

export interface ValueMappingRow {
  sourceAgency: string; sourceIdentifier: string; sourceValue: string;
  targetAgency: string; targetIdentifier: string; targetValue: string;
  /** Members of the entry the table does not show. */
  rest: Obj;
}
const VM_FIELDS = ['sourceAgency', 'sourceIdentifier', 'sourceValue', 'targetAgency', 'targetIdentifier', 'targetValue'] as const;

function valueMappingRows(doc: unknown): ValueMappingRow[] {
  if (doc === undefined || doc === null) return [];
  if (!Array.isArray(doc)) throw new Error('expected a list of value mappings: [ { "sourceAgency": …, "targetValue": … } ]');
  return doc.map((e, i) => {
    if (!isObj(e)) throw new Error(`entry ${i + 1} is not an object`);
    const rest: Obj = { ...e };
    const row = { rest } as ValueMappingRow;
    for (const f of VM_FIELDS) {
      row[f] = text(e[f]);
      delete rest[f];
    }
    return row;
  });
}

function valueMappingDoc(rows: ValueMappingRow[]): unknown {
  return rows.filter((r) => VM_FIELDS.some((f) => r[f] !== '')).map((r) => {
    const e: Obj = {};
    for (const f of VM_FIELDS) e[f] = r[f];
    return { ...e, ...r.rest };
  });
}

// ------------------------------------------------------------------ credentials (Secure Store)

export interface CredentialRow {
  alias: string; username: string; password: string;
  /** properties, accessToken, user, tokenResponse and anything else: kept, edited in the file. */
  rest: Obj;
  /** Of username and password, what the file's entry does not have (an OAuth2 credential): written only when filled. */
  absent: ('username' | 'password')[];
}

function credentialRows(doc: unknown): CredentialRow[] {
  if (doc === undefined || doc === null) return [];
  if (!isObj(doc)) throw new Error('expected an object of aliases: { "ALIAS": { "username": …, "password": … } }');
  return Object.entries(doc).map(([alias, c]) => {
    if (!isObj(c)) throw new Error(`credential ${alias} is not an object`);
    const { username, password, ...rest } = c;
    const absent = (['username', 'password'] as const).filter((k) => c[k] === undefined);
    return { alias, username: text(username), password: text(password), rest, absent };
  });
}

function credentialDoc(rows: CredentialRow[]): unknown {
  const doc: Obj = {};
  for (const r of rows) {
    if (r.alias.trim() === '') continue;
    const c: Obj = {};
    for (const k of ['username', 'password'] as const) {
      if (r[k] !== '' || !r.absent.includes(k)) c[k] = r[k];
    }
    doc[r.alias.trim()] = { ...c, ...r.rest };
  }
  return doc;
}

// ------------------------------------------------------------------ Partner Directory

export interface PartnerParameterRow {
  partner: string; name: string; value: string;
  /** A binary parameter ({contentType, file} or {base64}): shown, not edited here. */
  binary: Obj | null;
}

function partnerRows(doc: unknown): PartnerParameterRow[] {
  if (doc === undefined || doc === null) return [];
  if (!isObj(doc) || (doc.partners !== undefined && !isObj(doc.partners))) {
    throw new Error('expected { "partners": { "PID": { "parameters": { "NAME": "value" } } } }');
  }
  const rows: PartnerParameterRow[] = [];
  for (const [partner, p] of Object.entries((doc.partners ?? {}) as Obj)) {
    if (!isObj(p)) throw new Error(`partner ${partner} is not an object`);
    const params = p.parameters;
    if (params !== undefined && !isObj(params)) throw new Error(`the parameters of partner ${partner} are not an object`);
    for (const [name, v] of Object.entries((params ?? {}) as Obj)) {
      rows.push(isObj(v) ? { partner, name, value: '', binary: v } : { partner, name, value: text(v), binary: null });
    }
  }
  return rows;
}

/** What a partner has besides parameters, for the note under the table. */
export function partnerExtras(doc: unknown): { partner: string; alternativePartners: number; authorizedUsers: number }[] {
  if (!isObj(doc) || !isObj(doc.partners)) return [];
  return Object.entries(doc.partners).filter(([, p]) => isObj(p)).map(([partner, p]) => ({
    partner,
    alternativePartners: Array.isArray((p as Obj).alternativePartners) ? ((p as Obj).alternativePartners as unknown[]).length : 0,
    authorizedUsers: Array.isArray((p as Obj).authorizedUsers) ? ((p as Obj).authorizedUsers as unknown[]).length : 0,
  })).filter((e) => e.alternativePartners + e.authorizedUsers > 0);
}

function partnerDoc(rows: PartnerParameterRow[], original: unknown): unknown {
  const base: Obj = isObj(original) ? { ...original } : {};
  const before: Obj = isObj(base.partners) ? (base.partners as Obj) : {};
  const params = new Map<string, Obj>();
  for (const r of rows) {
    const id = r.partner.trim();
    const name = r.name.trim();
    if (id === '' || name === '') continue;
    if (!params.has(id)) params.set(id, {});
    params.get(id)![name] = r.binary ?? r.value;
  }
  const partners: Obj = {};
  // In the file's order. A partner keeps what the table does not show; one that had only parameters goes
  // when its rows are gone.
  for (const [id, p] of Object.entries(before)) {
    const { parameters: _parameters, ...others } = isObj(p) ? p : {};
    if (params.has(id) || Object.keys(others).length > 0) partners[id] = { parameters: params.get(id) ?? {}, ...others };
  }
  for (const [id, parameters] of params) {
    if (!(id in partners)) partners[id] = { parameters };
  }
  return { ...base, partners };
}

// ------------------------------------------------------------------ number ranges

export interface NumberRangeRow {
  name: string;
  /** Number fields as typed; an empty one is left out of the file. */
  next: string; min: string; max: string; fieldLength: string;
  rotate: boolean;
  rest: Obj;
}
const NR_NUMBERS = ['next', 'min', 'max', 'fieldLength'] as const;

function numberRangeRows(doc: unknown): NumberRangeRow[] {
  if (doc === undefined || doc === null) return [];
  if (!isObj(doc)) throw new Error('expected { "NAME": { "next": 1, "min": 1, "max": 999999, "fieldLength": 6, "rotate": false } }');
  return Object.entries(doc).map(([name, n]) => {
    if (!isObj(n)) throw new Error(`number range ${name} is not an object`);
    const { next, min, max, fieldLength, rotate, ...rest } = n;
    return { name, next: text(next), min: text(min), max: text(max), fieldLength: text(fieldLength), rotate: rotate === true, rest };
  });
}

function numberRangeDoc(rows: NumberRangeRow[]): unknown {
  const doc: Obj = {};
  for (const r of rows) {
    const name = r.name.trim();
    if (name === '') continue;
    const n: Obj = {};
    for (const f of NR_NUMBERS) {
      const t = r[f].trim();
      if (t === '') continue;
      if (!/^-?\d+$/.test(t)) throw new Error(`Number range ${name}: ${f} must be a whole number, not "${t}"`);
      n[f] = Number(t);
    }
    doc[name] = { ...n, rotate: r.rotate, ...r.rest };
  }
  return doc;
}

// ------------------------------------------------------------------ rows ⇄ document

export interface PlatformRows {
  valueMappings: ValueMappingRow[];
  credentials: CredentialRow[];
  partnerDirectory: PartnerParameterRow[];
  numberRanges: NumberRangeRow[];
}

/** The rows of a file's document (`undefined`: no file). Throws with what is wrong in the document. */
export function platformRows<K extends PlatformKey>(key: K, doc: unknown): PlatformRows[K] {
  const rows = key === 'valueMappings' ? valueMappingRows(doc) : key === 'credentials' ? credentialRows(doc)
    : key === 'partnerDirectory' ? partnerRows(doc) : numberRangeRows(doc);
  return rows as PlatformRows[K];
}

/** The document for the file and for a run: the rows, with what `original` holds besides them. Empty rows are left out. */
export function platformDoc<K extends PlatformKey>(key: K, rows: PlatformRows[K], original: unknown): unknown {
  return key === 'valueMappings' ? valueMappingDoc(rows as ValueMappingRow[])
    : key === 'credentials' ? credentialDoc(rows as CredentialRow[])
    : key === 'partnerDirectory' ? partnerDoc(rows as PartnerParameterRow[], original)
    : numberRangeDoc(rows as NumberRangeRow[]);
}

export function newPlatformRow<K extends PlatformKey>(key: K): PlatformRows[K][number] {
  const row = key === 'valueMappings' ? { sourceAgency: '', sourceIdentifier: '', sourceValue: '', targetAgency: '', targetIdentifier: '', targetValue: '', rest: {} }
    : key === 'credentials' ? { alias: '', username: '', password: '', rest: {}, absent: [] }
    : key === 'partnerDirectory' ? { partner: '', name: '', value: '', binary: null }
    : { name: '', next: '1', min: '', max: '', fieldLength: '', rotate: false, rest: {} };
  return row as PlatformRows[K][number];
}

/** The file's text for a document: indented JSON with a final line break. */
export function platformFileText(doc: unknown): string {
  return JSON.stringify(doc, null, 2) + '\n';
}

// ------------------------------------------------------------------ attachments and SOAP headers

export interface AttachmentRow {
  name: string;
  contentType: string;
  /** Where the content comes from: typed text, a project file, or Base64 from a test case. */
  kind: 'text' | 'file' | 'base64';
  text: string;
  /** Project-relative path. */
  file: string;
  base64: string;
}

export interface SoapHeaderRow {
  /** {namespace}local, as javax.xml.namespace.QName writes it. */
  name: string;
  /** The header element as XML. */
  value: string;
  mustUnderstand: boolean;
  actor: string;
}

/** A test case's `input.attachments` as rows; `join` makes a case-relative file project-relative. */
export function attachmentRows(list: unknown, join: (file: string) => string): AttachmentRow[] {
  if (!Array.isArray(list)) return [];
  return list.filter(isObj).map((a) => ({
    name: text(a.name), contentType: text(a.contentType),
    kind: a.file !== undefined ? 'file' : a.base64 !== undefined ? 'base64' : 'text',
    text: text(a.text), file: a.file !== undefined ? join(text(a.file)) : '', base64: text(a.base64),
  }));
}

/** The run's `input.attachments`: rows without a name and without content are left out. */
export function attachmentInput(rows: AttachmentRow[]): Obj[] {
  const out: Obj[] = [];
  for (const r of rows) {
    const content = r.kind === 'file' ? r.file.trim() : r.kind === 'base64' ? r.base64 : r.text;
    if (r.name.trim() === '' && content === '') continue;
    const a: Obj = {};
    if (r.name.trim() !== '') a.name = r.name.trim();
    if (r.contentType.trim() !== '') a.contentType = r.contentType.trim();
    a[r.kind] = content;
    out.push(a);
  }
  return out;
}

export function soapHeaderRows(list: unknown): SoapHeaderRow[] {
  if (!Array.isArray(list)) return [];
  return list.filter(isObj).map((h) => ({
    name: text(h.name), value: text(h.value), mustUnderstand: h.mustUnderstand === true, actor: text(h.actor),
  }));
}

export function soapHeaderInput(rows: SoapHeaderRow[]): Obj[] {
  return rows.filter((r) => r.name.trim() !== '').map((r) => {
    const h: Obj = { name: r.name.trim(), mustUnderstand: r.mustUnderstand };
    if (r.value.trim() !== '') h.value = r.value;
    if (r.actor.trim() !== '') h.actor = r.actor.trim();
    return h;
  });
}
