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
