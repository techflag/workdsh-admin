import assert from 'node:assert/strict';
import {test} from 'node:test';
import {runInNewContext} from 'node:vm';
import {createServer} from 'node:http';
import {createServer as createSecureServer} from 'node:https';
import {spawn, execFileSync} from 'node:child_process';
import {mkdtemp, readFile, rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {gzipSync} from 'node:zlib';
import {adoptAccountStorage, accountOwnerKey, fenceOfficialDocument, fenceContentSecurityPolicy}
  from '../deploy/docker/account-storage-fence.mjs';

const ownerA = JSON.stringify(['org', 'member-a']);
const ownerB = JSON.stringify(['org', 'member-b']);
function storage(values = {}) {
  const entries = new Map(Object.entries(values));
  return {get length() { return entries.size; }, key: index => [...entries.keys()][index] ?? null,
    getItem: key => entries.get(key) ?? null, setItem: (key, value) => entries.set(key, String(value)),
    removeItem: key => entries.delete(key)};
}

test('verified account adoption removes private drafts and metadata while preserving display preferences', () => {
  const local = storage({'dsh.sidebar-right.v1.a': 'private path', 'dsh.sidebar-browser.v1.a': 'private URL',
    'dsh.conversation.a': 'unsent draft', 'dsh.user-questions.drafts.v1.a': 'answer',
    'dsh.schedule.task-tab.v1.a': 'task', 'dsh.sessions.current': 'a', 'dsh.workspace.view.v5': 'order',
    'dsh.workspace.view.v5.a': 'scoped order', 'dsh.theme': 'dark', 'customerPreference': 'kept'});
  const session = storage({localClipboard: 'private sheet text'});
  assert.equal(adoptAccountStorage(local, session, ownerA), true);
  for (const key of ['dsh.sidebar-right.v1.a', 'dsh.sidebar-browser.v1.a', 'dsh.conversation.a',
    'dsh.user-questions.drafts.v1.a', 'dsh.schedule.task-tab.v1.a', 'dsh.sessions.current',
    'dsh.workspace.view.v5', 'dsh.workspace.view.v5.a']) assert.equal(local.getItem(key), null);
  assert.equal(session.getItem('localClipboard'), null);
  assert.equal(local.getItem('dsh.theme'), 'dark');
  assert.equal(local.getItem('customerPreference'), 'kept');
  local.setItem('dsh.conversation.a', 'new draft');
  assert.equal(adoptAccountStorage(local, session, ownerA), false);
  assert.equal(local.getItem('dsh.conversation.a'), 'new draft');
  assert.equal(adoptAccountStorage(local, session, ownerB), true);
  assert.equal(local.getItem('dsh.conversation.a'), null);
});

test('another tab adopting an account still clears this tab’s old spreadsheet clipboard', () => {
  const local = storage({[accountOwnerKey]: ownerB, 'dsh.conversation.b': 'B draft'});
  const session = storage({[accountOwnerKey]: ownerA, localClipboard: 'A sheet'});
  adoptAccountStorage(local, session, ownerB);
  assert.equal(session.getItem('localClipboard'), null);
  assert.equal(local.getItem('dsh.conversation.b'), 'B draft');
});

test('failed cleanup remains pending so the next attempt cannot restore stale account data', () => {
  const local = storage({[accountOwnerKey]: ownerA, 'dsh.conversation.a': 'A draft'});
  const remove = local.removeItem;
  local.removeItem = () => { throw Error('storage unavailable'); };
  assert.throws(() => adoptAccountStorage(local, storage(), ownerB));
  assert.notEqual(local.getItem(accountOwnerKey), ownerB);
  local.removeItem = remove;
  adoptAccountStorage(local, storage(), ownerB);
  assert.equal(local.getItem('dsh.conversation.a'), null);
  assert.equal(local.getItem(accountOwnerKey), ownerB);
});

test('preboot script locks an older document on owner change and cannot embed actor markup', () => {
  const listeners = new Map();
  let stopped = false, emptied = false, destination;
  const local = storage(), session = storage();
  const window = {localStorage: local, sessionStorage: session, stop: () => { stopped = true; },
    location: {replace: value => { destination = value; }}, addEventListener: (key, value) => listeners.set(key, value)};
  const document = {documentElement: {replaceChildren: () => { emptied = true; }}, addEventListener: () => {}};
  const html = fenceOfficialDocument('<html><head><title>Official</title></head><body></body></html>',
    {organizationId: 'org', id: 'member-a'}, 'a'.repeat(32));
  runInNewContext(html.match(/<script nonce="[^"]+">([\s\S]*?)<\/script>/)[1], {window, document});
  local.setItem(accountOwnerKey, ownerB);
  listeners.get('storage')({key: accountOwnerKey});
  assert.equal(stopped, true); assert.equal(emptied, true); assert.equal(destination, '/login');
  const injected = fenceOfficialDocument('<head></head>', {organizationId: 'org', id: '</script><script>evil()'}, 'b'.repeat(32));
  assert.equal((injected.match(/<script/g) ?? []).length, 1);
  assert.throws(() => fenceOfficialDocument('<head></head>', {id: 'a'}, 'c'.repeat(32)));
});

test('CSP retains official inline boot allowances and existing frame restrictions', () => {
  assert.equal(fenceContentSecurityPolicy(undefined, 'x'), undefined);
  const legacy = "default-src 'self'; script-src 'self' 'unsafe-inline'; frame-src https://example.test";
  assert.equal(fenceContentSecurityPolicy(legacy, 'x'), legacy);
  const strict = fenceContentSecurityPolicy("default-src 'self'; script-src 'self' 'nonce-official'; script-src-elem 'none'; frame-src https://example.test", 'new');
  assert.match(strict, /script-src 'self' 'nonce-official' 'nonce-new'/);
  assert.match(strict, /script-src-elem 'nonce-new'/);
  assert.match(strict, /frame-src https:\/\/example.test/);
  assert.deepEqual(fenceContentSecurityPolicy(["script-src 'self'", "frame-src 'none'"], 'x'),
    ["script-src 'self' 'nonce-x'", "frame-src 'none'; script-src 'self' 'nonce-x'"]);
});
