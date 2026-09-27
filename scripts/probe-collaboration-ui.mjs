import assert from 'node:assert/strict';
import { pathToFileURL } from 'node:url';
import { resolve } from 'node:path';

const source = resolve(process.env.WORKDSH_SOURCE ?? '../workdsh');
const { chromium } = await import(pathToFileURL(resolve(source, 'node_modules/@playwright/test/index.mjs')));
const adminUrl = process.env.WORKDSH_ADMIN_URL ?? 'http://127.0.0.1:18890';
const webUrl = process.env.WORKDSH_ADMIN_WEB_URL ?? 'http://127.0.0.1:18891';
const email = process.env.WORKDSH_BOOTSTRAP_ADMIN_EMAIL;
const password = process.env.WORKDSH_BOOTSTRAP_ADMIN_PASSWORD;
if (!email || !password) throw new Error('Set the local bootstrap admin credentials');

async function api(path, { method = 'GET', token, body, file } = {}) {
  const upload = file ? new FormData() : null;
  if (upload) {
    upload.set('file', new Blob([file.bytes], { type: file.mediaType }), file.name);
    upload.set('expectedRevision', String(file.expectedRevision));
  }
  const response = await fetch(`${adminUrl}${path}`, {
    method,
    headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}), ...(body ? { 'content-type': 'application/json' } : {}) },
    ...(body || upload ? { body: upload ?? JSON.stringify(body) } : {}),
    signal: AbortSignal.timeout(8_000),
  });
  const value = response.status === 204 ? null : await response.json().catch(() => null);
  if (!response.ok) throw new Error(`${method} ${path} returned ${response.status}`);
  return value;
}

const administrator = await api('/api/auth/login', { method: 'POST', body: { email, password } });
const adminToken = administrator.token;
const stamp = Date.now().toString(36);
const people = [];
let browser;
try {
  for (const kind of ['seller', 'reviewer']) {
    const account = `${kind}-ui-${stamp}@example.test`;
    const member = await api('/api/admin/members', {
      method: 'POST', token: adminToken,
      body: { email: account, displayName: `UI ${kind}`, role: 'MEMBER' },
    });
    const person = { id: member.member.id, email: account, password: `UI-Collaboration-${stamp}-${kind}!` };
    people.push(person);
    const login = await api('/api/auth/login', {
      method: 'POST', body: { email: account, password: member.temporaryPassword },
    });
    await api('/api/auth/change-password', {
      method: 'POST', token: login.token,
      body: { currentPassword: member.temporaryPassword, newPassword: person.password },
    });
  }
  browser = await chromium.launch({ headless: true });
  for (const person of people) {
    person.context = await browser.newContext();
    person.page = await person.context.newPage();
    await person.page.goto(webUrl);
    await person.page.getByPlaceholder('name@company.com').fill(person.email);
    await person.page.getByPlaceholder('输入密码').fill(person.password);
    await person.page.getByRole('button', { name: '登录' }).click();
    await person.page.getByRole('heading', { name: '组织概览' }).waitFor();
  }
  const [seller, reviewer] = people;
  assert.equal(await reviewer.page.locator('.inbox-count').count(), 0);
  const sellerLogin = await api('/api/auth/login', {
    method: 'POST', body: { email: seller.email, password: seller.password },
  });
  const reviewerLogin = await api('/api/auth/login', {
    method: 'POST', body: { email: reviewer.email, password: reviewer.password },
  });
  const order = await api('/api/orders', {
    method: 'POST', token: sellerLogin.token,
    body: { customerName: `跨成员协作 ${stamp}`, sourceType: 'PDF', sourceName: 'fixture.pdf',
      lines: [{ customerName: '测试物料', customerReference: '设备 A', quantity: 1 }] },
  });
  await api(`/api/orders/${order.id}/sources`, {
    method: 'POST', token: sellerLogin.token,
    file: { name: 'fixture.pdf', mediaType: 'application/pdf', expectedRevision: order.revision,
      bytes: new TextEncoder().encode('%PDF-1.4 local collaboration fixture') },
  });
  const beforeAssignment = await fetch(`${adminUrl}/api/orders/${order.id}`, {
    headers: { Authorization: `Bearer ${reviewerLogin.token}` }, signal: AbortSignal.timeout(5_000),
  });
  assert.equal(beforeAssignment.status, 404, 'the reviewer must not see a private order before assignment');
  await seller.page.getByRole('button', { name: '我的订单' }).first().click();
  await seller.page.getByRole('button', { name: '刷新' }).click();
  await seller.page.getByRole('row', { name: new RegExp(`跨成员协作 ${stamp}`) }).getByRole('button', { name: '打开' }).click();
  await seller.page.getByPlaceholder('选择复核员').click();
  await seller.page.getByText('UI reviewer', { exact: true }).last().click();
  await seller.page.getByRole('button', { name: '提交复核' }).click();
  await seller.page.getByText('已交给复核员').waitFor();
  await reviewer.page.locator('.rail .inbox-count').getByText('1').waitFor({ timeout: 20_000 });
  await reviewer.page.getByRole('button', { name: /复核待办/ }).first().click();
  await reviewer.page.getByRole('row', { name: new RegExp(`跨成员协作 ${stamp}`) }).getByRole('button', { name: '复核' }).click();
  await reviewer.page.getByRole('heading', { name: '我的复核结论' }).waitFor();
  await reviewer.page.getByPlaceholder('退回时请说明需要核对的内容').fill('请核对库存 SKU');
  await reviewer.page.getByRole('button', { name: '退回修改' }).click();
  await reviewer.page.getByText('已退回销售修改').waitFor();
  await seller.page.locator('.rail .inbox-count').getByText('1').waitFor({ timeout: 20_000 });
  await seller.page.getByRole('button', { name: '刷新' }).click();
  await seller.page.getByText('已退回', { exact: true }).first().waitFor();
  assert.equal((await api(`/api/orders/${order.id}`, { token: sellerLogin.token })).status, 'CHANGES_REQUESTED');
  console.log('PASS: separate browser identities complete assignment, live reviewer inbox, return notice and authoritative handback.');
} finally {
  if (browser) await browser.close();
  const directory = await api('/api/admin/members', { token: adminToken });
  for (const person of people) {
    const member = directory.find(item => item.id === person.id);
    if (member?.active) await api(`/api/admin/members/${person.id}`, {
      method: 'PATCH', token: adminToken, body: { active: false, expectedRevision: member.revision },
    });
  }
}
