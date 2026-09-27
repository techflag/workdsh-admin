import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { createServer } from 'node:http';
import { mkdtemp, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { test } from 'node:test';

const script = join(dirname(fileURLToPath(import.meta.url)), 'enterprise-dsh-gateway.mjs');
const memberId = 'member-a';
const organizationId = 'organization-a';
const grantId = 'a'.repeat(64);
const hostCookie = 'dsh-auth-test=valid';

async function listen(server) {
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  return `http://127.0.0.1:${server.address().port}`;
}

test('gateway checks the current identity contract and exact grant before routing', async () => {
  let contractVersion = 1;
  let returnedGrantId = grantId;
  const host = createServer((request, response) => {
    response.writeHead(request.headers.cookie === hostCookie ? 200 : 401);
    response.end('member A host');
  });
  const admin = createServer((request, response) => {
    response.setHeader('content-type', 'application/json');
    if (request.url === '/api/internal/runtime/identity') {
      response.end(JSON.stringify({ contractVersion, principalId: memberId, organizationId, active: true }));
    } else if (request.url === `/api/internal/dsh/grants/${grantId}`) {
      response.end(JSON.stringify({ grantId: returnedGrantId, memberId, organizationId }));
    } else if (request.url === '/api/internal/dsh/consume') {
      response.end(JSON.stringify({ grantId, memberId, organizationId }));
    } else {
      response.writeHead(404); response.end('{}');
    }
  });
  const directory = await mkdtemp(join(tmpdir(), 'workdsh-gateway-boundary-'));
  let gateway;
  try {
    const hostOrigin = await listen(host);
    const adminOrigin = await listen(admin);
    const reserved = createServer();
    const gatewayOrigin = await listen(reserved);
    await new Promise(resolve => reserved.close(resolve));
    const instanceFile = join(directory, 'instances.json');
    await writeFile(instanceFile, JSON.stringify([{ memberId, organizationId, origin: hostOrigin,
      runtimeToken: 'runtime-token', cookie: hostCookie }]), { mode: 0o600 });
    gateway = spawn(process.execPath, [script], { env: { ...process.env,
      WORKDSH_ADMIN_URL: adminOrigin, WORKDSH_DSH_GATEWAY_ORIGIN: gatewayOrigin,
      WORKDSH_ADMIN_WEB_ORIGIN: 'http://127.0.0.1:18891',
      WORKDSH_DSH_GATEWAY_SECRET: randomBytes(40).toString('base64url'),
      WORKDSH_DSH_INSTANCES_FILE: instanceFile,
    }, stdio: ['ignore', 'pipe', 'pipe'] });
    let output = '';
    gateway.stdout.on('data', chunk => { output += chunk; });
    gateway.stderr.on('data', chunk => { output += chunk; });
    const deadline = Date.now() + 5_000;
    while (!output.includes('WorkDSH enterprise DSH gateway:')) {
      if (gateway.exitCode !== null || Date.now() > deadline) throw new Error(`Gateway failed: ${output}`);
      await new Promise(resolve => setTimeout(resolve, 25));
    }
    const launch = await fetch(`${gatewayOrigin}/launch`, { method: 'POST', redirect: 'manual',
      headers: { origin: 'http://127.0.0.1:18891', 'content-type': 'application/x-www-form-urlencoded' },
      body: new URLSearchParams({ ticket: 't'.repeat(43) }) });
    assert.equal(launch.status, 303);
    const cookie = launch.headers.get('set-cookie').split(';')[0];
    const open = () => fetch(gatewayOrigin, { headers: { cookie }, signal: AbortSignal.timeout(2_000) });
    assert.equal((await open()).status, 200);
    contractVersion = 2;
    assert.equal((await open()).status, 401, 'a different identity contract must fail closed');
    contractVersion = 1;
    returnedGrantId = 'b'.repeat(64);
    assert.equal((await open()).status, 401, 'a different grant must not reuse the gateway session');
  } finally {
    if (gateway && gateway.exitCode === null) {
      const closed = new Promise(resolve => gateway.once('close', resolve));
      gateway.kill('SIGTERM');
      await closed;
    }
    await Promise.all([new Promise(resolve => host.close(resolve)), new Promise(resolve => admin.close(resolve))]);
    await rm(directory, { recursive: true, force: true });
  }
});
