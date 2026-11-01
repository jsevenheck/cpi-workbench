// Completions for Groovy scripts: the members of SAP's Script API as the clean-room classes in
// engine/groovy implement them (SAP's published interfaces). Groovy has no type information in the
// editor, so the receiver is recognised by name: `message` or a variable declared as Message,
// `messageLogFactory`, a variable declared as MessageLog or assigned from getMessageLog(...), and
// ITApiFactory. tests/completions.test.mts checks every member against the Java sources.

export interface Member {
  name: string;
  /** Parameters as SAP's interface declares them, e.g. "String name, Object value". */
  params: string;
  returns: string;
}

type Kind = 'message' | 'messageLog' | 'messageLogFactory' | 'itApiFactory';

const m = (returns: string, name: string, params = ''): Member => ({ name, params, returns });

/** Both Script API versions (the shared base class). */
const MESSAGE: Member[] = [
  m('Object', 'getBody'), m('<T> T', 'getBody', 'Class<T> type'), m('void', 'setBody', 'Object body'),
  m('long', 'getBodySize'),
  m('Map<String, Object>', 'getHeaders'), m('<T> T', 'getHeader', 'String name, Class<T> type'),
  m('void', 'setHeader', 'String name, Object value'), m('void', 'setHeaders', 'Map<String, Object> headers'),
  m('Map<String, Object>', 'getProperties'), m('Object', 'getProperty', 'String name'),
  m('void', 'setProperty', 'String name, Object value'), m('void', 'setProperties', 'Map<String, Object> properties'),
  m('Map<String, DataHandler>', 'getAttachments'), m('void', 'setAttachments', 'Map<String, DataHandler> attachments'),
  m('long', 'getAttachmentsSize'), m('void', 'clearSoapHeaders'),
];

/** Script API v1 (com.sap.gateway.ip.core.customdev.util.Message). */
const MESSAGE_V1: Member[] = [
  m('void', 'addAttachmentHeader', 'String name, String value, AttachmentWrapper attachment'),
  m('void', 'setAttachmentHeader', 'String name, String value, AttachmentWrapper attachment'),
  m('String', 'getAttachmentHeader', 'String name, AttachmentWrapper attachment'),
  m('void', 'removeAttachmentHeader', 'String name, AttachmentWrapper attachment'),
  m('Map<String, AttachmentWrapper>', 'getAttachmentWrapperObjects'),
  m('void', 'setAttachmentWrapperObjects', 'Map<String, AttachmentWrapper> attachments'),
  m('void', 'addAttachmentObject', 'String id, AttachmentWrapper attachment'),
  m('List<SoapHeader>', 'getSoapHeaders'), m('void', 'setSoapHeaders', 'List<SoapHeader> headers'),
];

/** Script API v2 (com.sap.it.script.v2.api.Message). */
const MESSAGE_V2: Member[] = [
  m('Object', 'getHeader', 'String name'), m('Object', 'getHeader', 'String name, Object defaultValue'),
  m('void', 'addAttachmentHeader', 'String name, String value, Attachment attachment'),
  m('void', 'setAttachmentHeader', 'String name, String value, Attachment attachment'),
  m('String', 'getAttachmentHeader', 'String name, Attachment attachment'),
  m('void', 'removeAttachmentHeader', 'String name, Attachment attachment'),
  m('Map<String, Attachment>', 'getAttachmentObjects'), m('void', 'setAttachmentObjects', 'Map<String, Attachment> attachments'),
  m('void', 'addAttachmentObject', 'String id, Attachment attachment'), m('void', 'clearAttachments'),
  m('void', 'resetBodyStream'),
  m('ExtensionFunctions', 'getExtensionFunctions', 'Class<? extends ExtensionFunctions> type'),
];

const MESSAGE_LOG: Member[] = [
  m('void', 'setStringProperty', 'String name, String value'), m('void', 'setIntegerProperty', 'String name, Integer value'),
  m('void', 'setLongProperty', 'String name, Long value'), m('void', 'setBooleanProperty', 'String name, Boolean value'),
  m('void', 'setFloatProperty', 'String name, Float value'), m('void', 'setDoubleProperty', 'String name, Double value'),
  m('void', 'setDateProperty', 'String name, Date value'), m('void', 'addCustomHeaderProperty', 'String name, String value'),
  m('void', 'addAttachmentAsString', 'String name, String text, String mediaType'),
];

const MESSAGE_LOG_FACTORY: Member[] = [m('MessageLog', 'getMessageLog', 'Object message')];

const IT_API_FACTORY: Member[] = [
  m('<T> T', 'getService', 'Class<T> type, Object context'), m('<T> T', 'getApi', 'Class<T> type, Object context'),
];

/** The member lists, for the test that checks them against the Java sources. */
export const API = { MESSAGE, MESSAGE_V1, MESSAGE_V2, MESSAGE_LOG, MESSAGE_LOG_FACTORY, IT_API_FACTORY };

const esc = (s: string) => s.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');

/** What the receiver before the dot is, from its name and the declarations in the script. */
export function receiverKind(script: string, receiver: string): Kind | null {
  if (receiver === 'messageLogFactory') return 'messageLogFactory';
  if (receiver === 'ITApiFactory') return 'itApiFactory';
  const r = esc(receiver);
  if (new RegExp(`\\bMessageLog\\s+${r}\\b`).test(script)
    || new RegExp(`\\b${r}\\s*=\\s*messageLogFactory\\s*\\.\\s*getMessageLog\\s*\\(`).test(script)) return 'messageLog';
  if (receiver === 'message' || new RegExp(`\\bMessage\\s+${r}\\b`).test(script)) return 'message';
  return null;
}

/** The members after `receiver.` in this script, or null when the receiver is not an API object. */
export function membersFor(script: string, receiver: string): Member[] | null {
  const kind = receiverKind(script, receiver);
  if (kind === 'messageLog') return MESSAGE_LOG;
  if (kind === 'messageLogFactory') return MESSAGE_LOG_FACTORY;
  if (kind === 'itApiFactory') return IT_API_FACTORY;
  if (kind === 'message') return [...MESSAGE, ...(script.includes('com.sap.it.script.v2.api') ? MESSAGE_V2 : MESSAGE_V1)];
  return null;
}

/** The receiver and the typed part of the member, from the line up to the cursor: `message.setHea`, `log?.set`. */
export function memberAccess(linePrefix: string): { receiver: string; partial: string } | null {
  const hit = /([A-Za-z_]\w*)\s*\??\.\s*(\w*)$/.exec(linePrefix);
  return hit ? { receiver: hit[1], partial: hit[2] } : null;
}
