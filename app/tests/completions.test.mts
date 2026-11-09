// Script API completions: node --test tests/ (Node 22.18+ runs the TypeScript sources).
import { test } from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import { API, membersFor, memberAccess, receiverKind } from '../src/lib/completions.ts';

const src = (p: string) => fs.readFileSync(new URL('../../engine/groovy/src/' + p, import.meta.url), 'utf8');
const publicNames = (java: string) => new Set([...java.matchAll(/public\s+(?:static\s+)?(?:<[^>]+>\s+)?[\w<>[\], ?]+\s+(\w+)\s*\(/g)].map((x) => x[1]));
const base = publicNames(src('main/java/io/cpiwb/groovy/MessageSupport.java'));
const v1 = new Set([...base, ...publicNames(src('api-common/java/com/sap/gateway/ip/core/customdev/util/Message.java'))]);
const v2 = new Set([...base, ...publicNames(src('api-v2/java/com/sap/it/script/v2/api/Message.java'))]);
const log = publicNames(src('api-common/java/com/sap/it/api/msglog/MessageLog.java'));
const factory = publicNames(src('api-common/java/com/sap/it/api/msglog/MessageLogFactory.java'));
const itApi = publicNames(src('api-common/java/com/sap/it/api/ITApiFactory.java'));

test('every completion is a method of the clean-room Script API', () => {
  const missing = (list: { name: string }[], names: Set<string>) => list.map((m) => m.name).filter((n) => !names.has(n));
  assert.deepEqual(missing(API.MESSAGE, v1), []);
  assert.deepEqual(missing(API.MESSAGE, v2), []);
  assert.deepEqual(missing(API.MESSAGE_V1, v1), []);
  assert.deepEqual(missing(API.MESSAGE_V2, v2), []);
  assert.deepEqual(missing(API.MESSAGE_LOG, log), []);
  assert.deepEqual(missing(API.MESSAGE_LOG_FACTORY, factory), []);
  assert.deepEqual(missing(API.IT_API_FACTORY, itApi), []);
});

test('receivers are recognised by name and declaration', () => {
  const v2Script = 'import com.sap.it.script.v2.api.Message\ndef Message processData(Message msg) {\n'
    + '  def log = messageLogFactory.getMessageLog(msg)\n  MessageLog other = null\n}';
  assert.equal(receiverKind(v2Script, 'msg'), 'message');
  assert.equal(receiverKind(v2Script, 'log'), 'messageLog');
  assert.equal(receiverKind(v2Script, 'other'), 'messageLog');
  assert.equal(receiverKind(v2Script, 'messageLogFactory'), 'messageLogFactory');
  assert.equal(receiverKind(v2Script, 'ITApiFactory'), 'itApiFactory');
  assert.equal(receiverKind(v2Script, 'payload'), null);
  assert.ok(membersFor(v2Script, 'msg')!.some((m) => m.name === 'resetBodyStream'));
  assert.ok(!membersFor(v2Script, 'msg')!.some((m) => m.name === 'getAttachmentWrapperObjects'));
  assert.ok(membersFor(v2Script, 'msg')!.some((m) => m.name === 'getSoapHeaders'));
  const v1Script = 'import com.sap.gateway.ip.core.customdev.util.Message\ndef Message processData(Message message) {}';
  assert.ok(membersFor(v1Script, 'message')!.some((m) => m.name === 'getSoapHeaders'));
  assert.equal(membersFor(v1Script, 'payload'), null);
});

test('memberAccess reads the receiver and the typed part', () => {
  assert.deepEqual(memberAccess('    message.setHea'), { receiver: 'message', partial: 'setHea' });
  assert.deepEqual(memberAccess('def b = message.'), { receiver: 'message', partial: '' });
  assert.deepEqual(memberAccess('    log?.setStr'), { receiver: 'log', partial: 'setStr' });
  assert.equal(memberAccess('def b = 1'), null);
});
