import { spawn, execFile } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { mkdtemp, rm } from 'node:fs/promises';
import { createServer } from 'node:net';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { promisify } from 'node:util';

const [major, minor] = process.versions.node.split('.').map(Number);
if (major < 22 || (major === 22 && minor < 19)) throw new Error('Node.js 22.19+ is required');

const exec = promisify(execFile);
const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const source = resolve(process.env.WORKDSH_SOURCE ?? join(root, '..', 'workdsh'));
const ports = { gateway: 18892, web: 18894, admin: 18895 };
const origins = {
  gateway: `http://127.0.0.1:${ports.gateway}`,
  web: `http://127.0.0.1:${ports.web}`,
  admin: `http://127.0.0.1:${ports.admin}`,
};
const directory = await mkdtemp(join(tmpdir(), 'workdsh-collaboration-demo-'));
const children = [];

async function assertAvailable(port) {
  await new Promise((resolvePort, reject) => {
    const server = createServer();
    server.once('error', reject);
    server.listen(port, '127.0.0.1', () => server.close(resolvePort));
  });
}

async function run(command, args, cwd) {
  try {
    return await exec(command, args, { cwd, maxBuffer: 4 * 1024 * 1024, timeout: 120_000 });
  } catch (error) {
    throw new Error(`${command} ${args.join(' ')} failed:\n${String(error.stderr ?? error.message).slice(-3000)}`);
  }
}

function start(command, args, cwd, env) {
  let log = '';
  const child = spawn(command, args, { cwd, env, stdio: ['ignore', 'pipe', 'pipe'] });
  for (const stream of [child.stdout, child.stderr]) stream.on('data', part => {
    log = (log + part.toString()).slice(-5000);
  });
  children.push(child);
  return { child, getLog: () => log };
}

async function waitFor(origin, processInfo) {
  const deadline = Date.now() + 30_000;
  while (Date.now() < deadline) {
    if (processInfo.child.exitCode !== null) throw new Error(`${origin} exited:\n${processInfo.getLog()}`);
    try {
      const response = await fetch(origin, { signal: AbortSignal.timeout(1000) });
      if (response.status < 500) return;
    } catch { /* The service is still starting. */ }
    await new Promise(resolveWait => setTimeout(resolveWait, 150));
  }
  throw new Error(`${origin} did not start:\n${processInfo.getLog()}`);
}

async function stop(child) {
  if (child.exitCode !== null || child.signalCode !== null) return;
  const finished = new Promise(resolveStop => child.once('close', resolveStop));
  child.kill('SIGTERM');
  const timer = setTimeout(() => child.kill('SIGKILL'), 3000);
  await finished;
  clearTimeout(timer);
}

try {
  for (const port of Object.values(ports)) await assertAvailable(port);
  const packages = [
    ['workdsh-provider-identity-enterprise', 'packages/providers/identity-enterprise'],
    ['workdsh-plugin-audit', 'packages/plugins/audit'],
    ['workdsh-plugin-access', 'packages/plugins/access'],
    ['workdsh-plugin-enterprise-collaboration', 'packages/plugins/enterprise-collaboration'],
  ];
  const tarballs = new Map();
  const corepack = join(dirname(process.execPath), 'corepack');
  const npm = join(dirname(process.execPath), 'npm');
  for (const [name, relative] of packages) {
    console.log(`Preparing ${name}...`);
    await run(corepack, ['pnpm', '--filter', name, 'build'], source);
    const { stdout } = await run(npm, ['pack', '--json', '--pack-destination', directory], join(source, relative));
    const [{ filename }] = JSON.parse(stdout);
    tarballs.set(name, join(directory, filename));
  }

  const adminEmail = `collaboration-demo-${Date.now()}@example.test`;
  const adminPassword = `Demo-${randomBytes(18).toString('base64url')}!`;
  const gatewaySecret = randomBytes(40).toString('base64url');
  const env = { ...process.env, PATH: `${dirname(process.execPath)}:${process.env.PATH}` };
  const backend = start('mvn', ['-q', 'spring-boot:run'], join(root, 'server'), {
    ...env, PORT: String(ports.admin),
    JDBC_URL: 'jdbc:h2:mem:collaboration-demo;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1',
    WORKDSH_ADMIN_WEB_ORIGIN: origins.web,
    WORKDSH_DSH_GATEWAY_ORIGIN: origins.gateway,
    WORKDSH_DSH_GATEWAY_SECRET: gatewaySecret,
    WORKDSH_BOOTSTRAP_ADMIN_EMAIL: adminEmail,
    WORKDSH_BOOTSTRAP_ADMIN_PASSWORD: adminPassword,
  });
  await waitFor(`${origins.admin}/api/auth/me`, backend);
  const frontend = start(npm, ['run', 'dev'], join(root, 'web'), {
    ...env, WORKDSH_WEB_PORT: String(ports.web), WORKDSH_ADMIN_URL: origins.admin,
  });
  await waitFor(origins.web, frontend);

  console.log('Running the two-member collaboration checks before opening the demo...');
  const probe = spawn(process.execPath, [join(root, 'scripts/probe-two-dsh-instances.mjs')], {
    cwd: root,
    env: {
      ...env,
      WORKDSH_SOURCE: source,
      WORKDSH_ADMIN_URL: origins.admin,
      WORKDSH_ADMIN_WEB_ORIGIN: origins.web,
      WORKDSH_DSH_GATEWAY_ORIGIN: origins.gateway,
      WORKDSH_DSH_GATEWAY_SECRET: gatewaySecret,
      WORKDSH_BOOTSTRAP_ADMIN_EMAIL: adminEmail,
      WORKDSH_BOOTSTRAP_ADMIN_PASSWORD: adminPassword,
      WORKDSH_ENTERPRISE_IDENTITY_TARBALL: tarballs.get('workdsh-provider-identity-enterprise'),
      WORKDSH_AUDIT_TARBALL: tarballs.get('workdsh-plugin-audit'),
      WORKDSH_ACCESS_TARBALL: tarballs.get('workdsh-plugin-access'),
      WORKDSH_ENTERPRISE_COLLABORATION_TARBALL: tarballs.get('workdsh-plugin-enterprise-collaboration'),
      WORKDSH_PROBE_WEB_SESSION: '1',
      WORKDSH_PROBE_COLLABORATION: '1',
      WORKDSH_PROBE_GATEWAY: '1',
      WORKDSH_PROBE_DEMO: '1',
    },
    stdio: 'inherit',
  });
  children.push(probe);
  const code = await new Promise(resolveExit => probe.once('close', resolveExit));
  if (code !== 0) throw new Error(`Collaboration probe exited with code ${code}`);
} finally {
  for (const child of children.reverse()) await stop(child);
  await rm(directory, { recursive: true, force: true });
}
