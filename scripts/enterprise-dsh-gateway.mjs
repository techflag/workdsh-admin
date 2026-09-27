/** Local enterprise ingress prototype: an authenticated browser reaches only its assigned DSH Host. */
import { createServer, request as httpRequest } from 'node:http';
import { connect as tcpConnect } from 'node:net';
import { randomBytes } from 'node:crypto';
import { readFile, stat } from 'node:fs/promises';

const admin = new URL(process.env.WORKDSH_ADMIN_URL ?? 'http://127.0.0.1:18890');
const publicOrigin = new URL(process.env.WORKDSH_DSH_GATEWAY_ORIGIN ?? 'http://127.0.0.1:18892');
const adminWebOrigin = new URL(process.env.WORKDSH_ADMIN_WEB_ORIGIN ?? 'http://127.0.0.1:18891');
const secret = process.env.WORKDSH_DSH_GATEWAY_SECRET ?? '';
const instanceFile = process.env.WORKDSH_DSH_INSTANCES_FILE;
if (secret.length < 32 || !instanceFile) throw new Error('Configure gateway secret and instance file');
for (const origin of [admin, publicOrigin]) {
  const loopback = ['127.0.0.1', 'localhost', '[::1]'].includes(origin.hostname);
  if (!['http:', 'https:'].includes(origin.protocol) || (origin.protocol === 'http:' && !loopback)
      || origin.username || origin.password || origin.search || origin.hash || origin.pathname !== '/') {
    throw new Error('Admin and gateway URLs must be HTTPS origins, except on loopback');
  }
}
if (publicOrigin.protocol === 'https:') throw new Error('Run behind a trusted TLS reverse proxy; configure this process with its loopback HTTP origin');
if (process.platform !== 'win32' && ((await stat(instanceFile)).mode & 0o077) !== 0) {
  throw new Error('Instance file must be readable only by its owner');
}
const configured = JSON.parse(await readFile(instanceFile, 'utf8'));
if (!Array.isArray(configured) || !configured.length) throw new Error('Instance file must contain member bindings');
const instances = new Map();
for (const entry of configured) {
  const origin = new URL(entry.origin);
  if (!entry.memberId || !entry.organizationId || !entry.runtimeToken || instances.has(entry.memberId)
      || origin.protocol !== 'http:' || !['127.0.0.1', 'localhost', '[::1]'].includes(origin.hostname)
      || origin.username || origin.password || origin.search || origin.hash || origin.pathname !== '/'
      || typeof entry.cookie !== 'string' || !/^dsh-auth-[A-Za-z0-9_-]+=[A-Za-z0-9._-]+$/.test(entry.cookie)) {
    throw new Error('Each member needs one distinct loopback DSH Host binding');
  }
  const response = await fetch(origin, { headers: { cookie: entry.cookie }, signal: AbortSignal.timeout(5_000) });
  if (!response.ok) throw new Error('DSH Host cookie is invalid');
  instances.set(entry.memberId, { ...entry, origin });
}

const sessions = new Map();
const cookieName = 'workdsh-enterprise-session';
const expiresMs = 12 * 60 * 60 * 1000;
const send = (response, status, body, headers = {}) => {
  response.writeHead(status, { 'content-type': 'text/plain; charset=utf-8', 'cache-control': 'no-store', ...headers });
  response.end(body);
};
const parseCookie = header => header?.split(';').map(part => part.trim()).find(part => part.startsWith(`${cookieName}=`))?.slice(cookieName.length + 1);
const sameOrigin = request => {
  if (request.headers.host !== publicOrigin.host || request.headers['sec-fetch-site'] === 'cross-site') return false;
  const origin = request.headers.origin;
  return !origin || origin === publicOrigin.origin;
};
const body = async request => {
  const chunks = [];
  let size = 0;
  for await (const chunk of request) {
    size += chunk.length;
    if (size > 2048) throw new Error('Launch body too large');
    chunks.push(chunk);
  }
  return Buffer.concat(chunks).toString('utf8');
};
const activeMember = async (instance, grantId) => {
  try {
    const [runtimeResponse, grantResponse] = await Promise.all([
      fetch(new URL('/api/internal/runtime/identity', admin), {
        headers: { Authorization: `Runtime ${instance.runtimeToken}` }, signal: AbortSignal.timeout(5_000),
      }),
      fetch(new URL(`/api/internal/dsh/grants/${grantId}`, admin), {
        headers: { Authorization: `Gateway ${secret}` }, signal: AbortSignal.timeout(5_000),
      }),
    ]);
    if (!runtimeResponse.ok || !grantResponse.ok) return false;
    const [identity, grant] = await Promise.all([runtimeResponse.json(), grantResponse.json()]);
    return identity.contractVersion === 1 && identity.principalId === instance.memberId
      && identity.organizationId === instance.organizationId && identity.active === true
      && grant.grantId === grantId && grant.memberId === instance.memberId
      && grant.organizationId === instance.organizationId;
  } catch { return false; }
};
const proxy = (request, response, instance) => {
  const headers = { ...request.headers, host: instance.origin.host, cookie: instance.cookie };
  for (const name of ['authorization', 'proxy-authorization', 'x-forwarded-for', 'x-forwarded-host',
    'x-forwarded-proto', 'referer', 'connection', 'upgrade']) delete headers[name];
  if (headers.origin) headers.origin = instance.origin.origin;
  const upstream = httpRequest(instance.origin, { method: request.method, path: request.url, headers }, incoming => {
    const out = { ...incoming.headers };
    delete out['set-cookie'];
    if (typeof out.location === 'string' && out.location.startsWith(instance.origin.origin)) {
      out.location = publicOrigin.origin + out.location.slice(instance.origin.origin.length);
    }
    response.writeHead(incoming.statusCode ?? 502, out);
    incoming.pipe(response);
  });
  upstream.on('error', () => { if (!response.headersSent) send(response, 502, 'DSH Host unavailable'); else response.destroy(); });
  request.pipe(upstream);
};

const server = createServer(async (request, response) => {
  try {
    if (request.url === '/launch') {
      if (request.headers.host !== publicOrigin.host || request.headers['sec-fetch-site'] === 'cross-site'
          || request.headers.origin !== adminWebOrigin.origin) return send(response, 403, 'Forbidden');
      if (request.method !== 'POST' || request.headers['content-type']?.split(';')[0] !== 'application/x-www-form-urlencoded') {
        return send(response, 405, 'Launch requires form POST');
      }
      const ticket = new URLSearchParams(await body(request)).get('ticket');
      if (!ticket || !/^[A-Za-z0-9_-]{40,100}$/.test(ticket)) return send(response, 400, 'Invalid launch ticket');
      const exchange = await fetch(new URL('/api/internal/dsh/consume', admin), {
        method: 'POST', headers: { Authorization: `Gateway ${secret}`, 'content-type': 'application/json' },
        body: JSON.stringify({ ticket }), signal: AbortSignal.timeout(5_000),
      });
      if (!exchange.ok) return send(response, 401, 'Launch ticket rejected');
      const member = await exchange.json();
      const instance = instances.get(member.memberId);
      if (!instance || instance.organizationId !== member.organizationId || !await activeMember(instance, member.grantId)) {
        return send(response, 403, 'Member instance unavailable');
      }
      const session = randomBytes(32).toString('base64url');
      sessions.set(session, { memberId: member.memberId, grantId: member.grantId, expiresAt: Date.now() + expiresMs });
      response.writeHead(303, { location: '/', 'cache-control': 'no-store',
        'set-cookie': `${cookieName}=${session}; Path=/; Max-Age=${expiresMs / 1000}; HttpOnly; SameSite=Strict` });
      return response.end();
    }
    if (!sameOrigin(request)) return send(response, 403, 'Forbidden');
    if (request.url === '/leave' && request.method === 'POST') {
      sessions.delete(parseCookie(request.headers.cookie));
      return send(response, 204, '', { 'set-cookie': `${cookieName}=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict` });
    }
    const session = sessions.get(parseCookie(request.headers.cookie));
    if (!session || session.expiresAt <= Date.now()) return send(response, 401, 'Open DSH from your enterprise account');
    const instance = instances.get(session.memberId);
    if (!instance || !await activeMember(instance, session.grantId)) return send(response, 401, 'Member instance unavailable');
    proxy(request, response, instance);
  } catch { if (!response.headersSent) send(response, 502, 'Gateway unavailable'); else response.destroy(); }
});
server.on('upgrade', async (request, socket, head) => {
  const reject = () => { socket.end('HTTP/1.1 401 Unauthorized\r\nConnection: close\r\n\r\n'); };
  try {
    if (!sameOrigin(request) || request.url !== '/api/remote.mux') return reject();
    const session = sessions.get(parseCookie(request.headers.cookie));
    if (!session || session.expiresAt <= Date.now()) return reject();
    const instance = instances.get(session.memberId);
    if (!instance || !await activeMember(instance, session.grantId)) return reject();
    const upstream = tcpConnect(Number(instance.origin.port), instance.origin.hostname);
    upstream.setTimeout(5_000, () => upstream.destroy());
    upstream.once('connect', () => {
      upstream.setTimeout(0);
      const headers = { ...request.headers, host: instance.origin.host, cookie: instance.cookie,
        origin: instance.origin.origin };
      for (const name of ['authorization', 'proxy-authorization', 'x-forwarded-for', 'x-forwarded-host',
        'x-forwarded-proto', 'referer']) delete headers[name];
      upstream.write(`${request.method} ${request.url} HTTP/1.1\r\n`
        + Object.entries(headers).map(([name, value]) => `${name}: ${value}`).join('\r\n') + '\r\n\r\n');
      if (head.length) upstream.write(head);
      socket.pipe(upstream).pipe(socket);
    });
    const recheck = setInterval(async () => {
      if (session.expiresAt <= Date.now() || !await activeMember(instance, session.grantId)) {
        socket.destroy(); upstream.destroy();
      }
    }, 1_500);
    const close = () => { clearInterval(recheck); socket.destroy(); upstream.destroy(); };
    socket.on('error', close);
    socket.on('close', close);
    upstream.on('error', close);
    upstream.on('close', close);
  } catch { reject(); }
});
server.listen(Number(publicOrigin.port), publicOrigin.hostname, () => {
  process.stdout.write(`WorkDSH enterprise DSH gateway: ${publicOrigin.origin}\n`);
});
