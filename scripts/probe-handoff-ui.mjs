import assert from 'node:assert/strict';
import { resolve } from 'node:path';
import { pathToFileURL } from 'node:url';

const source = resolve(process.env.WORKDSH_SOURCE ?? '../workdsh');
const { chromium } = await import(pathToFileURL(resolve(source, 'node_modules/@playwright/test/index.mjs')));
const adminUrl = process.env.WORKDSH_ADMIN_URL ?? 'http://127.0.0.1:18890';
const webUrl = process.env.WORKDSH_ADMIN_WEB_URL ?? 'http://127.0.0.1:18891';
const email = process.env.WORKDSH_BOOTSTRAP_ADMIN_EMAIL;
const password = process.env.WORKDSH_BOOTSTRAP_ADMIN_PASSWORD;
if (!email || !password) throw new Error('Set bootstrap admin credentials');

async function api(path, { method = 'GET', token, body } = {}) {
  const response = await fetch(`${adminUrl}${path}`, {
    method, headers: { ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined, signal: AbortSignal.timeout(8_000),
  });
  const value = response.status === 204 ? null : await response.json().catch(() => null);
  if (!response.ok) throw new Error(`${method} ${path}: ${response.status}`);
  return value;
}

const admin = (await api('/api/auth/login', { method: 'POST', body: { email, password } })).token;
const stamp = Date.now().toString(36);
const people = [];
let browser;
try {
  for (const [index, label] of ['甲', '乙'].entries()) {
    const address = `handoff-${stamp}-${index}@example.test`;
    const created = await api('/api/admin/members', { method: 'POST', token: admin,
      body: { email: address, displayName: label, role: 'MEMBER' } });
    const newPassword = `HandoffProbe-${stamp}-${index}!`;
    const first = (await api('/api/auth/login', { method: 'POST',
      body: { email: address, password: created.temporaryPassword } })).token;
    await api('/api/auth/change-password', { method: 'POST', token: first,
      body: { currentPassword: created.temporaryPassword, newPassword } });
    people.push({ id: created.member.id, email: address, password: newPassword });
  }
  browser = await chromium.launch({ headless: true });
  const adminContext = await browser.newContext();
  const adminPage = await adminContext.newPage();
  await adminPage.goto(webUrl);
  await adminPage.getByPlaceholder('name@company.com').fill(email);
  await adminPage.getByPlaceholder('输入密码').fill(password);
  await adminPage.getByRole('button', { name: '登录' }).click();
  await adminPage.locator('.rail').getByRole('button', { name: '成员与角色' }).click();
  for (const person of people) await adminPage.getByText(person.email, { exact: true }).waitFor();
  await adminContext.close();
  for (const person of people) {
    person.context = await browser.newContext();
    person.page = await person.context.newPage();
    await person.page.goto(webUrl);
    await person.page.getByPlaceholder('name@company.com').fill(person.email);
    await person.page.getByPlaceholder('输入密码').fill(person.password);
    await person.page.getByRole('button', { name: '登录' }).click();
    await person.page.getByRole('heading', { name: '组织概览' }).waitFor();
  }
  const [sender, recipient] = people;
  await sender.page.locator('.rail').getByRole('button', { name: '协作交接' }).click();
  await sender.page.getByPlaceholder('选择同组织成员').click();
  await sender.page.getByText('乙', { exact: true }).last().click();
  const summary = `请复核分析 ${stamp}`;
  await sender.page.getByPlaceholder('写清要对方处理什么、期望怎样回执').fill(summary);
  await sender.page.getByRole('button', { name: '@ 同事并交接' }).click();
  await sender.page.getByText('已交接给同事').waitFor();
  await recipient.page.locator('.rail').getByRole('button', { name: /协作交接/ })
    .locator('.inbox-count').getByText('1').waitFor({ timeout: 20_000 });
  await recipient.page.locator('.rail').getByRole('button', { name: /协作交接/ }).click();
  await recipient.page.getByText(summary).first().waitFor({ timeout: 20_000 });
  await recipient.page.getByPlaceholder('完成后填写结果').fill('已核对，建议补充来源');
  await recipient.page.getByRole('button', { name: '完成并回执' }).click();
  await recipient.page.getByText('已回执，发送人可以查看结果').waitFor();
  await sender.page.getByRole('button', { name: '刷新' }).click();
  await sender.page.getByText('已核对，建议补充来源').first().waitFor();
  assert.equal((await api('/api/collaboration/sent', { token: (await api('/api/auth/login', {
    method: 'POST', body: { email: sender.email, password: sender.password },
  })).token }))[0].status, 'DONE');
  console.log('PASS: two browser members send a handoff, receive it, complete it and read the result without an order.');
} finally {
  if (browser) await browser.close();
  const directory = await api('/api/admin/members', { token: admin });
  for (const person of people) {
    const member = directory.find(item => item.id === person.id);
    if (member?.active) await api(`/api/admin/members/${person.id}`, { method: 'PATCH', token: admin,
      body: { active: false, expectedRevision: member.revision } });
  }
}
