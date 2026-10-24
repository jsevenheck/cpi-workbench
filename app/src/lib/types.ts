export interface Row {
  name: string;
  value: string;
  type: string;
  disabled?: boolean;
}

export interface Diagnostic {
  severity: 'error' | 'warning' | 'info';
  message: string;
  code?: string;
  systemId?: string;
  line?: number;
  column?: number;
}

export interface MessageOut {
  body: string;
  encoding: string;
  bodySize: number;
  headers: Row[];
  properties: Row[];
}

export interface Changes {
  added: string[];
  changed: string[];
  removed: string[];
}

export interface XsltResult {
  ok: boolean;
  failedPhase?: 'compile' | 'parameters' | 'runtime';
  diagnostics: Diagnostic[];
  messages: { text: string; terminate: boolean; line?: number }[];
  parameters: { name: string; source: string; required: boolean; value?: string; type?: string }[];
  cpiCalls: { function: string; name: string; value: string }[];
  output?: MessageOut;
  changes?: { headers: Changes; properties: Changes };
  timing: { compileMs: number; cached: boolean; totalMs: number };
  transformMs?: number;
  engine: { profile: string; product: string };
  outputMethod?: string;
}

export interface FileEntry {
  path: string;
  dir: boolean;
}

export const TYPES = ['String', 'Integer', 'Long', 'Boolean', 'Double', 'Float', 'BigDecimal', 'Date', 'byte[]', 'null'];

export interface GroovyResult {
  ok: boolean;
  failedPhase?: 'compile' | 'runtime';
  diagnostics: Diagnostic[];
  output?: MessageOut & { bodyType: string };
  changes?: { headers: Changes; properties: Changes };
  attachments?: { name: string; contentType: string | null; size: number }[];
  mpl: {
    enabled: boolean;
    properties: { name: string; type: string; value: string | null }[];
    customHeaders: { name: string; value: string }[];
    attachments: { name: string; mediaType: string; content: string }[];
  };
  apiCalls: { api: string; method: string; args: (string | null)[] }[];
  console: string;
  stackTrace?: string;
  runMs?: number;
  timing: { compileMs: number; cached: boolean; totalMs: number };
  engine: { profile: string; groovy: string; camel: string; java: string; scriptApi: string };
}
