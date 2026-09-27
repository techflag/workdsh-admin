import assert from 'node:assert/strict';
import { spawn, execFile } from 'node:child_process';
import { chmod, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { createRequire } from 'node:module';
import { promisify } from 'node:util';

const [nodeMajor, nodeMinor] = process.versions.node.split('.').map(Number);
if (nodeMajor < 22 || (nodeMajor === 22 && nodeMinor < 19)) {
  throw new Error('This probe needs Node.js 22.19+; select it before creating test members');
}
const exec = promisify(execFile);
const source = resolve(process.env.WORKDSH_SOURCE ?? '../workdsh');
const adminIdentitySchema = JSON.parse(await readFile(resolve('docs/runtime-identity-v1.schema.json'), 'utf8'));
const pluginIdentitySchema = JSON.parse(await readFile(join(source, 'packages/providers/identity-enterprise/runtime-identity-v1.schema.json'), 'utf8'));
assert.deepEqual(pluginIdentitySchema, adminIdentitySchema, 'Admin and DSH identity plugin must use the same contract');
const assertRuntimeIdentityV1 = value => {
  const schema = adminIdentitySchema;
  assert.equal(schema.$id, 'urn:workdsh:enterprise-runtime-identity:v1');
  assert.equal(schema.additionalProperties, false);
  assert.ok(value && typeof value === 'object' && !Array.isArray(value));
  assert.deepEqual(Object.keys(value).sort(), [...schema.required].sort(), 'runtime identity response fields differ from v1');
  for (const [field, rule] of Object.entries(schema.properties)) {
    const actual = value[field];
    if (rule.type === 'integer') assert.ok(Number.isInteger(actual), `${field} must be an integer`);
    else assert.equal(typeof actual, rule.type, `${field} has the wrong type`);
    if ('const' in rule) assert.equal(actual, rule.const, `${field} has the wrong constant`);
    if (rule.enum) assert.ok(rule.enum.includes(actual), `${field} is outside the allowed values`);
    if (rule.minLength !== undefined) assert.ok(actual.length >= rule.minLength, `${field} is too short`);
    if (rule.maxLength !== undefined) assert.ok(actual.length <= rule.maxLength, `${field} is too long`);
    if (rule.minimum !== undefined) assert.ok(actual >= rule.minimum, `${field} is too small`);
  }
};
const admin = process.env.WORKDSH_ADMIN_URL ?? 'http://127.0.0.1:18890';
const adminEmail = process.env.WORKDSH_BOOTSTRAP_ADMIN_EMAIL;
const adminPassword = process.env.WORKDSH_BOOTSTRAP_ADMIN_PASSWORD;
const tarball = process.env.WORKDSH_ENTERPRISE_IDENTITY_TARBALL;
const ordersTarball = process.env.WORKDSH_ENTERPRISE_ORDERS_TARBALL;
const collaborationTarball = process.env.WORKDSH_ENTERPRISE_COLLABORATION_TARBALL;
const auditTarball = process.env.WORKDSH_AUDIT_TARBALL;
const accessTarball = process.env.WORKDSH_ACCESS_TARBALL;
const webSessionProbe = process.env.WORKDSH_PROBE_WEB_SESSION === '1';
const gatewayProbe = process.env.WORKDSH_PROBE_GATEWAY === '1';
const collaborationProbe = process.env.WORKDSH_PROBE_COLLABORATION === '1';
const demoProbe = process.env.WORKDSH_PROBE_DEMO === '1';
const realModelProbe = process.env.WORKDSH_PROBE_REAL_MODEL === '1';
let stopRequested = false;
if (demoProbe) {
  const requestStop = () => { stopRequested = true; };
  process.on('SIGINT', requestStop);
  process.on('SIGTERM', requestStop);
}
if (collaborationProbe && (!webSessionProbe || !collaborationTarball)) throw new Error('Collaboration probe needs Web Session and collaboration tarball');
if (realModelProbe && !collaborationProbe) throw new Error('Real-model probe needs WORKDSH_PROBE_COLLABORATION=1');
if (gatewayProbe && !webSessionProbe) throw new Error('Gateway probe needs WORKDSH_PROBE_WEB_SESSION=1');
if (demoProbe && (!collaborationProbe || !gatewayProbe || ordersTarball)) {
  throw new Error('Local demo needs the collaboration Web Session gateway probe without the orders fixture');
}
if (!adminEmail || !adminPassword || !tarball) {
  throw new Error('Set bootstrap admin credentials and WORKDSH_ENTERPRISE_IDENTITY_TARBALL');
}
if (Boolean(auditTarball) !== Boolean(accessTarball)) throw new Error('Set both WORKDSH_AUDIT_TARBALL and WORKDSH_ACCESS_TARBALL');
if (webSessionProbe && ((!ordersTarball && !collaborationTarball) || !auditTarball || !accessTarball)) {
  throw new Error('Web Session probe needs identity, audit, access and one domain plugin tarball');
}
const dsh = join(source, 'node_modules/@deepseek-ai/dsh/lib/bin.js');
let realModelCredentials;
if (realModelProbe) {
  const { parseDocument, stringify } = createRequire(join(source, 'packages/plugins/experts/package.json'))('yaml');
  const preview = parseDocument(await readFile(join(source, '.test-runtime/preview/.credentials.yaml'), 'utf8')).toJSON();
  const key = preview.refs?.DEEPSEEK_API_KEY;
  if (typeof key !== 'string' || key.length < 10) throw new Error('Configure the WorkDSH preview DeepSeek credential first');
  realModelCredentials = stringify({ version: 1, records: {}, refs: { DEEPSEEK_API_KEY: key } });
}
const stamp = Date.now().toString(36);
const request = async (path, { method = 'GET', bearer, runtime, body, file } = {}) => {
  const upload = file ? new FormData() : null;
  if (upload) {
    upload.set('file', new Blob([file.bytes], { type: file.mediaType }), file.name);
    upload.set('expectedRevision', String(file.expectedRevision));
  }
  const response = await fetch(`${admin}${path}`, {
    method,
    headers: { ...(bearer ? { Authorization: `Bearer ${bearer}` } : {}), ...(runtime ? { Authorization: `Runtime ${runtime}` } : {}), ...(body ? { 'content-type': 'application/json' } : {}) },
    ...(body || upload ? { body: upload ?? JSON.stringify(body) } : {}),
    signal: AbortSignal.timeout(8_000),
  });
  const value = response.status === 204 ? null : await response.json().catch(() => null);
  if (!response.ok) throw new Error(`${method} ${path} returned ${response.status}`);
  return value;
};
const login = await request('/api/auth/login', { method: 'POST', body: { email: adminEmail, password: adminPassword } });
const adminBearer = login.token;
assert.ok(adminBearer);
const people = [];
const hosts = [];
const homes = [];
let browser;
let gatewayProcess;
let gatewayBrowserContext;
let recipientGatewayContext;
const gatewaySockets = [];
try {
let probeTarball;
if (webSessionProbe) {
  const fixture = await mkdtemp(join(tmpdir(), 'workdsh-enterprise-web-fixture-'));
  homes.push(fixture);
  await writeFile(join(fixture, 'package.json'), JSON.stringify({
    name: 'workdsh-enterprise-web-fixture', version: '0.0.0', type: 'module',
    exports: { '.': './index.mjs', './client': './client.js' },
    dsh: { bundle: { patch: './patch.yml' }, client: { platform: 'web', inject: [
      '@deepseek-ai/dsh-api-session-controller', '@deepseek-ai/dsh-client-ui-workspace',
    ] } },
  }));
  await writeFile(join(fixture, 'patch.yml'), '- insert:\n    - id: enterprise-web-fixture\n      name: workdsh-enterprise-web-fixture\n');
  await writeFile(join(fixture, 'index.mjs'), `import { LlmAdapter, createMessage } from '@deepseek-ai/dsh-llm';
import { defineTool } from '@deepseek-ai/dsh-tools';
export const inject=['connection','llm','tools','sessionController','sessionPersistence','workdshSessionAccess','workdshIdentity'];
export function apply(ctx){
  const requests=[];
  let targetTool;
  let targetArgs={};
  let targetMode='single';
  let chainStage=0;
  ctx.effect(()=>ctx.tools.register(defineTool({
    name:'workdsh_fixture_calculate',description:'Calculate a quantity times a unit price for the collaboration probe.',
    parameters:{quantity:{type:'number',required:true},unit_price:{type:'number',required:true}},
    output:{schema:{type:'object',additionalProperties:false,properties:{data_json:{type:'string',required:true}}},render:(_args,value)=>[{type:'text',text:value.data_json}]},
    async execute(args){
      const total=args.quantity*args.unit_price;
      if(!Number.isFinite(total)) throw new Error('Invalid calculation');
      return {data_json:'计算结果：'+total};
    },
  })));
  class Model extends LlmAdapter {
    async listModels(provider){return [{provider,id:'fixture',name:'Enterprise fixture'}]}
    async *stream(options){
      requests.push({last:options.messages.at(-1)?.role,tools:options.tools?.map(tool=>tool.name)});
      const last=options.messages.at(-1);
      const text=JSON.stringify(last);
      let block;
      if(!options.tools?.length) block={type:'text',text:'协作订单会话'};
      else if(targetMode==='calculation-handoff' && last?.role==='tool' && chainStage===0) {
        const result=text.match(/计算结果：([0-9.]+)/);
        if(!result) throw new Error('Fixture did not receive calculation output');
        chainStage=1;
        block={type:'tool-call',id:'enterprise-fixture-'+Date.now()+'-'+Math.random(),name:'workdsh_collaboration_send',arguments:JSON.stringify({recipient_id:targetArgs.recipient_id,summary:'计算结果：'+result[1]+'，请复核'})};
      }
      else if(last?.role==='tool') block={type:'text',text:targetMode==='calculation-handoff'?'CALCULATION_HANDOFF_DONE':text.includes('多实例协作测试')?'ORDER_VISIBLE':'ORDER_HIDDEN'};
      else {
        const tool=targetTool;
        if(!tool) throw new Error('Enterprise fixture request omitted a tool name');
        block={type:'tool-call',id:'enterprise-fixture-'+Date.now()+'-'+Math.random(),name:tool,arguments:JSON.stringify(targetMode==='calculation-handoff'?{quantity:targetArgs.quantity,unit_price:targetArgs.unit_price}:targetArgs)};
      }
      yield {type:'block-start',index:0,blockType:block.type};
      yield {type:'block-end',index:0,block};
      yield {type:'finish',reason:{kind:block.type==='tool-call'?'tool-calls':'stop'}};
    }
  }
  ctx.llm.registerAdapter(['enterprise-fixture'],new Model());
  ctx.effect(()=>ctx.connection.fetch.register({path:'/api/enterprise-session-probe',methods:['POST'],requestBody:'buffered',async fetch(request){
    try {
      const input=await request.json();
      if(input.action==='identity') {
        const actor=await ctx.workdshIdentity.resolve({},request.signal);
        return Response.json({ok:true,principalId:actor.principalId,organizationId:actor.organizationId});
      }
      if(input.action==='resume') {
        if(typeof input.sessionId!=='string'||!input.sessionId.startsWith('session-')) throw new Error('Invalid Session ID');
        const resumed=await ctx.workdshSessionAccess.resolveAgent(input.sessionId,request.signal);
        if(!resumed.agent) throw new Error('Session Agent unavailable');
        return Response.json({ok:true,sessionId:input.sessionId});
      }
      if(input.action==='real-model-status') {
        if(typeof input.sessionId!=='string'||!input.sessionId.startsWith('session-')) throw new Error('Invalid Session ID');
        const opened=await ctx.sessionPersistence.open(input.sessionId,'read');
        let snapshot;
        try {snapshot=await opened.read()} finally {await opened.close()}
        const events=snapshot.events;
        return Response.json({ok:true,calledTools:events.filter(e=>e.type==='tool/call').map(e=>e.data.name),
          turnEnds:events.filter(e=>e.type==='turn/end').length,
          errors:events.filter(e=>/error|fault/.test(e.type)).map(e=>e.type)});
      }
      if(input.action==='begin-real-model-handoff'||input.action==='begin-real-model-analysis'
          ||input.action==='begin-real-model-reply') {
        const handoffRequested=input.action==='begin-real-model-handoff';
        const replyRequested=input.action==='begin-real-model-reply';
        if(handoffRequested&&(typeof input.recipientEmail!=='string'||!input.recipientEmail.endsWith('@example.test')
          ||typeof input.summary!=='string'||input.summary.length>200)) throw new Error('Invalid real-model handoff');
        if(replyRequested&&(typeof input.handoffId!=='string'||!/^[0-9a-f-]{36}$/.test(input.handoffId)))
          throw new Error('Invalid real-model handoff reply');
        const created=await ctx.workdshSessionAccess.create({cwd:process.cwd()},request.signal);
        await ctx.sessionController.selectModel({sessionId:created.sessionId,provider:'deepseek-official',model:'deepseek-flash'});
        const resolved=await ctx.workdshSessionAccess.resolveAgent(created.sessionId,request.signal);
        if(!resolved.agent) throw new Error('Session Agent unavailable');
        const prompt=handoffRequested?'请把“'+input.summary+'”交接给同事 '+input.recipientEmail+'。这是我明确授权的发送操作。'
          +'先调用 workdsh_collaboration_colleagues，按邮箱核对接收人；再调用 workdsh_collaboration_send 发送一次。'
          +'不要猜测成员 ID，不要发给其他人。完成后简短告知结果。'
          :replyRequested?'请处理同事交给我的事项 '+input.handoffId+'。这是我明确授权的完成和回执操作。'
          +'先调用 workdsh_collaboration_inbox，找到这个 ID 的交接；核对 8 × 120 的计算结果，'
          +'然后调用 workdsh_collaboration_complete，只完成这个 ID，并在回执中写明计算结果。不要发起新的交接。'
          :'请只分析 8 × 120 是否等于 960，并给我两条核对建议。这只是我自己查看的分析；不要联系同事，不要发送交接，也不要产生待办。';
        resolved.agent.followup(createMessage({role:'user',source:{kind:'user'},content:[{type:'text',text:prompt}]}));
        return Response.json({ok:true,sessionId:created.sessionId});
      }
      if(input.action==='calculation-handoff') {
        if(typeof input.recipientId!=='string'||!Number.isFinite(input.quantity)||!Number.isFinite(input.unitPrice)) throw new Error('Invalid calculation handoff');
        targetMode='calculation-handoff';chainStage=0;targetTool='workdsh_fixture_calculate';targetArgs={recipient_id:input.recipientId,quantity:input.quantity,unit_price:input.unitPrice};
      } else {
        if(!['workdsh_order_list','workdsh_review_inbox','workdsh_collaboration_send','workdsh_collaboration_inbox','workdsh_collaboration_sent','workdsh_collaboration_complete'].includes(input.tool)) throw new Error('Invalid probe tool');
        targetMode='single';targetTool=input.tool;targetArgs=input.args??{};
      }
      const created=await ctx.workdshSessionAccess.create({cwd:process.cwd()},request.signal);
      await ctx.sessionController.selectModel({sessionId:created.sessionId,provider:'enterprise-fixture',model:'fixture'});
      const resolved=await ctx.workdshSessionAccess.resolveAgent(created.sessionId,request.signal);
      if(!resolved.agent) throw new Error('Session Agent unavailable');
      resolved.agent.followup(createMessage({role:'user',source:{kind:'user'},content:[{type:'text',text:'CALL:'+input.tool}]}));
      await resolved.agent.whenIdle();
      const opened=await ctx.sessionPersistence.open(created.sessionId,'read');
      let snapshot;
      try {snapshot=await opened.read()} finally {await opened.close()}
      const events=snapshot.events;
      const calledTools=events.filter(e=>e.type==='tool/call').map(e=>e.data.name);
      return Response.json({ok:true,sessionId:created.sessionId,toolCalled:calledTools.includes(input.tool),calledTools,visible:JSON.stringify(events).includes('ORDER_VISIBLE'),hidden:JSON.stringify(events).includes('ORDER_HIDDEN'),containsExpected:typeof input.expect==='string'&&JSON.stringify(events).includes(input.expect),eventTypes:events.map(e=>e.type),requests,turnEnds:events.filter(e=>e.type==='turn/end').map(e=>e.data),errors:events.filter(e=>/error|fault|attempt/.test(e.type)).map(e=>({type:e.type,data:e.data}))});
    } catch(error) {return Response.json({ok:false,error:String(error?.message??error)},{status:500})}
  }}));
}`);
  await writeFile(join(fixture, 'client.js'), "window.__ModuleLoader__.load({id:'workdsh-enterprise-web-fixture',factory:function(){return {inject:['sessions','uiWorkspace','remote','remote.pluginInventory'],apply:function(ctx){ctx.effect(function(){window.enterpriseProbe={open:async function(id){await ctx.sessions.refresh();ctx.uiWorkspace.openSession(id)},inventory:async function(){const result=await ctx.remote.pluginInventory.list();if(!result.ok)throw Error(result.error.code);return result.value.entries.map(row=>({module:row.moduleName,phase:row.fiberPhase}))}};return function(){delete window.enterpriseProbe}})}}}});");
  const npm = join(dirname(process.execPath), 'npm');
  await exec(npm, ['pack', '--pack-destination', fixture], { cwd: fixture, timeout: 30_000 });
  probeTarball = join(fixture, 'workdsh-enterprise-web-fixture-0.0.0.tgz');
}
for (const number of [1, 2]) {
  const email = `instance-${stamp}-${number}@example.test`;
  const created = await request('/api/admin/members', {
    method: 'POST', bearer: adminBearer, body: { email, displayName: `Instance ${number}`, role: 'MEMBER' },
  });
  const person = { id: created.member.id, email, password: `LocalInstanceTest-${stamp}-${number}!`, runtimeToken: null };
  people.push(person);
  const memberLogin = await request('/api/auth/login', { method: 'POST', body: { email, password: created.temporaryPassword } });
  await request('/api/auth/change-password', {
    method: 'POST', bearer: memberLogin.token,
    body: { currentPassword: created.temporaryPassword, newPassword: person.password },
  });
  person.bearer = (await request('/api/auth/login', { method: 'POST',
    body: { email, password: person.password } })).token;
  const issued = await request(`/api/admin/members/${created.member.id}/runtime-credential`, {
    method: 'POST', bearer: adminBearer,
  });
  person.runtimeToken = issued.runtimeToken;
}
  for (const person of people) {
    const home = await mkdtemp(join(tmpdir(), 'workdsh-enterprise-instance-'));
    homes.push(home);
    if (realModelProbe)
      await writeFile(join(home, '.credentials.yaml'), realModelCredentials, { mode: 0o600 });
    const env = {
      ...process.env, DSH_HOME: home, DSH_AGENTS_HOME: join(home, 'agents'),
      WORKDSH_ADMIN_URL: admin, WORKDSH_RUNTIME_TOKEN: person.runtimeToken,
      PATH: `${join(source, 'node_modules/.bin')}:${dirname(process.execPath)}:${process.env.PATH}`,
    };
    const command = async (...args) => {
      const { stdout } = await exec(process.execPath, [dsh, ...args], {
        cwd: home, env, timeout: 90_000, maxBuffer: 6 * 1024 * 1024,
      });
      return stdout;
    };
    await command('--profile', 'enterprise', '--from-default-profile', 'web', '--dump-config');
    await command('plugin', '--profile', 'enterprise', 'add', tarball,
      ...(auditTarball ? [auditTarball, accessTarball] : []),
      ...(ordersTarball ? [ordersTarball] : []),
      ...(collaborationTarball ? [collaborationTarball] : []),
      ...(probeTarball ? [probeTarball] : []), '--offline');
    const config = await command('--profile', 'enterprise', '--dump-config');
    assert.ok(config.includes('id: workdsh-identity-enterprise'));
    if (ordersTarball) assert.ok(config.includes('id: workdsh-enterprise-orders'));
    if (collaborationTarball) assert.ok(config.includes('id: workdsh-enterprise-collaboration'));
    if (accessTarball) {
      for (const id of ['workdsh-audit', 'workdsh-access', 'workdsh-session-access', 'workdsh-tool-access']) {
        assert.ok(config.includes(`id: ${id}`), `${id} must be installed`);
      }
    }
    assert.ok(!config.includes(person.runtimeToken), 'secret must not be in the Profile');
    const installed = JSON.parse(await readFile(join(home, 'profiles/enterprise/node_modules/workdsh-provider-identity-enterprise/package.json')));
    assert.equal(installed.name, 'workdsh-provider-identity-enterprise');
    let log = '';
    const processHandle = spawn(process.execPath, [dsh, '--profile', 'enterprise', '--host', '127.0.0.1', '--port', '0', '--no-open'], {
      cwd: home, env, stdio: ['ignore', 'pipe', 'pipe'],
    });
    processHandle.stdout.on('data', part => { log += part; });
    processHandle.stderr.on('data', part => { log += part; });
    hosts.push({ person, home, processHandle, getLog: () => log });
  }
  for (const host of hosts) {
    const deadline = Date.now() + 25_000;
    while (!/http:\/\/127\.0\.0\.1:\d+\/\?token=[\w-]+/.test(host.getLog())) {
      if (host.processHandle.exitCode !== null || Date.now() > deadline) {
        throw new Error('DSH Host failed to start: ' + host.getLog().replace(/token=[^\s]+/g, 'token=[redacted]').slice(-1500));
      }
      await new Promise(resolve => setTimeout(resolve, 100));
    }
    const bootstrap = host.getLog().match(/http:\/\/127\.0\.0\.1:\d+\/\?token=[\w-]+/)[0];
    host.bootstrap = bootstrap;
    host.origin = new URL(bootstrap).origin;
    const loginResponse = await fetch(bootstrap, { redirect: 'manual', signal: AbortSignal.timeout(5_000) });
    assert.ok(loginResponse.status >= 300 && loginResponse.status < 400);
    host.cookie = loginResponse.headers.getSetCookie().map(value => value.split(';')[0]).join('; ');
    assert.ok(host.cookie);
    const own = await fetch(host.origin, { headers: { cookie: host.cookie }, signal: AbortSignal.timeout(5_000) });
    assert.equal(own.status, 200);
  }
  assert.notEqual(hosts[0].home, hosts[1].home);
  assert.notEqual(hosts[0].origin, hosts[1].origin);
  assert.notEqual(hosts[0].person.id, hosts[1].person.id);
  for (const [index, host] of hosts.entries()) {
    const other = hosts[1 - index];
    const rejected = await fetch(other.origin, { headers: { cookie: host.cookie }, signal: AbortSignal.timeout(5_000) });
    assert.equal(rejected.status, 401, 'another instance must reject this Web cookie');
  }
  const runtimeIdentity = async token => fetch(`${admin}/api/internal/runtime/identity`, {
    headers: { Authorization: `Runtime ${token}` }, signal: AbortSignal.timeout(5_000),
  });
  for (const host of hosts) {
    const response = await runtimeIdentity(host.person.runtimeToken);
    assert.equal(response.status, 200);
    const resolved = await response.json();
    assertRuntimeIdentityV1(resolved);
    assert.equal(resolved.principalId, host.person.id);
    host.organizationId = resolved.organizationId;
  }
  if (gatewayProbe) {
    const directory = await mkdtemp(join(tmpdir(), 'workdsh-gateway-fixture-'));
    homes.push(directory);
    const mapPath = join(directory, 'instances.json');
    await writeFile(mapPath, JSON.stringify(hosts.map(host => ({
      memberId: host.person.id, organizationId: host.organizationId, runtimeToken: host.person.runtimeToken,
      origin: host.origin, cookie: host.cookie,
    }))));
    await chmod(mapPath, 0o600);
    const gatewayOrigin = process.env.WORKDSH_DSH_GATEWAY_ORIGIN ?? 'http://127.0.0.1:18892';
    const gatewayEnv = { ...process.env,
      WORKDSH_ADMIN_URL: admin,
      WORKDSH_DSH_GATEWAY_ORIGIN: gatewayOrigin,
      WORKDSH_DSH_GATEWAY_SECRET: process.env.WORKDSH_DSH_GATEWAY_SECRET,
      WORKDSH_DSH_INSTANCES_FILE: mapPath,
      WORKDSH_ADMIN_WEB_ORIGIN: process.env.WORKDSH_ADMIN_WEB_ORIGIN ?? 'http://127.0.0.1:18891',
    };
    let gatewayLog = '';
    gatewayProcess = spawn(process.execPath, [join(dirname(new URL(import.meta.url).pathname), 'enterprise-dsh-gateway.mjs')], {
      cwd: directory, env: gatewayEnv, stdio: ['ignore', 'pipe', 'pipe'],
    });
    gatewayProcess.stdout.on('data', part => { gatewayLog += part; });
    gatewayProcess.stderr.on('data', part => { gatewayLog += part; });
    const deadline = Date.now() + 10_000;
    while (!gatewayLog.includes('WorkDSH enterprise DSH gateway:')) {
      if (gatewayProcess.exitCode !== null || Date.now() > deadline) throw new Error('Gateway failed: ' + gatewayLog.slice(-1500));
      await new Promise(resolve => setTimeout(resolve, 100));
    }
    for (const host of hosts) {
      const launch = await request('/api/dsh/launch', { method: 'POST', bearer: host.person.bearer });
      assert.equal(launch.gatewayOrigin, gatewayOrigin);
      const open = async () => fetch(`${gatewayOrigin}/launch`, {
        method: 'POST', headers: { origin: gatewayEnv.WORKDSH_ADMIN_WEB_ORIGIN,
          'content-type': 'application/x-www-form-urlencoded' },
        body: new URLSearchParams({ ticket: launch.ticket }), redirect: 'manual', signal: AbortSignal.timeout(5_000),
      });
      const response = await open();
      assert.equal(response.status, 303);
      host.gatewayCookie = response.headers.getSetCookie().map(value => value.split(';')[0]).join('; ');
      assert.ok(host.gatewayCookie.startsWith('workdsh-enterprise-session='));
      assert.equal((await open()).status, 401, 'launch ticket must be one-use');
      const identity = await fetch(`${gatewayOrigin}/api/enterprise-session-probe`, {
        method: 'POST', headers: { cookie: host.gatewayCookie, origin: gatewayOrigin, 'content-type': 'application/json' },
        body: JSON.stringify({ action: 'identity' }), signal: AbortSignal.timeout(5_000),
      });
      assert.equal(identity.status, 200);
      assert.equal((await identity.json()).principalId, host.person.id);
    }
    assert.notEqual(hosts[0].gatewayCookie, hosts[1].gatewayCookie);
    assert.equal((await fetch(gatewayOrigin, { headers: { cookie: 'workdsh-enterprise-session=invalid' } })).status, 401);
    console.log('PASS: one-use launch tickets route each browser to its own DSH Host without exposing Host launch tokens.');
  }
  if (collaborationProbe) {
    const nativeCall = async (host, tool, args = {}, expect) => {
      const response = await fetch(`${host.origin}/api/enterprise-session-probe`, {
        method: 'POST', headers: { cookie: host.cookie, 'content-type': 'application/json' },
        body: JSON.stringify({ tool, args, expect }), signal: AbortSignal.timeout(25_000),
      });
      const result = await response.json();
      assert.equal(response.status, 200, JSON.stringify(result));
      assert.equal(result.ok, true, JSON.stringify(result));
      assert.equal(result.toolCalled, true, JSON.stringify(result));
      if (expect) assert.equal(result.containsExpected, true, JSON.stringify(result));
      return result;
    };
    const [sender, recipient] = hosts;
    const summary = '计算结果：960，请复核';
    const calculated = await fetch(`${sender.origin}/api/enterprise-session-probe`, {
      method: 'POST', headers: { cookie: sender.cookie, 'content-type': 'application/json' },
      body: JSON.stringify({ action: 'calculation-handoff', recipientId: recipient.person.id, quantity: 8,
        unitPrice: 120, expect: summary }), signal: AbortSignal.timeout(25_000),
    });
    const sentSession = await calculated.json();
    assert.equal(calculated.status, 200, JSON.stringify(sentSession));
    assert.deepEqual(sentSession.calledTools, ['workdsh_fixture_calculate', 'workdsh_collaboration_send']);
    assert.equal(sentSession.containsExpected, true, JSON.stringify(sentSession));
    sender.webSessionId = sentSession.sessionId;
    const sent = await request('/api/collaboration/sent', { runtime: sender.person.runtimeToken });
    assert.equal(sent.length, 1);
    assert.equal(sent[0].recipientId, recipient.person.id);
    assert.equal(sent[0].summary, summary);
    for (const [host, endpoint, expected] of [[sender, 'sent', summary], [recipient, 'inbox', summary]]) {
      const response = await fetch(`${host.origin}/api/workdsh-collaboration`, {
        method: 'POST', headers: { cookie: host.cookie, 'content-type': 'application/json' },
        body: JSON.stringify({ endpoint }), signal: AbortSignal.timeout(8_000),
      });
      assert.equal(response.status, 200);
      assert.equal((await response.json()).value[0].summary, expected);
    }
    {
      const { chromium } = await import(join(source, 'node_modules/@playwright/test/index.mjs'));
      browser = await chromium.launch({ headless: true });
      const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
      await context.addCookies(recipient.cookie.split('; ').map(pair => {
        const at = pair.indexOf('=');
        return { name: pair.slice(0, at), value: pair.slice(at + 1), url: recipient.origin };
      }));
      const page = await context.newPage();
      await page.goto(recipient.origin);
      for (const name of ['Continue', 'Configure later'])
        await page.getByRole('button', { name, exact: true }).click({ timeout: 2_000 }).catch(() => {});
      await page.waitForFunction(() => !!window.enterpriseProbe?.inventory, null, { timeout: 15_000 });
      const inventory = await page.evaluate(() => window.enterpriseProbe.inventory());
      for (const name of ['workdsh-provider-identity-enterprise', 'workdsh-plugin-audit',
        'workdsh-plugin-access', 'workdsh-plugin-enterprise-collaboration']) {
        const entry = inventory.find(row => row.module === name);
        assert.ok(entry, `${name} must appear in the official plugin inventory`);
        assert.equal(String(entry.phase).toUpperCase(), 'ACTIVE', `${name} Fiber must be ACTIVE`);
      }
      await page.getByLabel('1 项待处理交接').waitFor({ timeout: 15_000 });
      await page.getByText('协作交接', { exact: true }).first().click({ timeout: 15_000 });
      await page.getByTestId('workdsh-collaboration').getByText(summary).waitFor({ timeout: 15_000 });
      assert.equal(await page.getByRole('combobox', { name: '接收人' })
        .locator('option', { hasText: sender.person.email }).count(), 1);
      const uiSummary = `请回看交接 ${stamp}`;
      await page.getByRole('combobox', { name: '接收人' }).selectOption(sender.person.id);
      await page.getByRole('textbox', { name: '交接事项' }).fill(uiSummary);
      await page.getByRole('button', { name: '发送交接' }).click();
      await page.getByText('已交接给同事').waitFor();
      const senderInbox = await request('/api/collaboration/inbox', { runtime: sender.person.runtimeToken });
      assert.ok(senderInbox.some(row => row.summary === uiSummary && row.senderId === recipient.person.id));
      const senderContext = await browser.newContext({ viewport: { width: 1440, height: 900 } });
      await senderContext.addCookies(sender.cookie.split('; ').map(pair => {
        const at = pair.indexOf('=');
        return { name: pair.slice(0, at), value: pair.slice(at + 1), url: sender.origin };
      }));
      const senderPage = await senderContext.newPage();
      const senderErrors = [];
      senderPage.on('pageerror', error => senderErrors.push(error.message));
      await senderPage.goto(sender.origin);
      for (const name of ['Continue', 'Configure later'])
        await senderPage.getByRole('button', { name, exact: true }).click({ timeout: 2_000 }).catch(() => {});
      await senderPage.waitForFunction(() => !!window.enterpriseProbe, null, { timeout: 15_000 });
      await senderPage.getByText('协作交接', { exact: true }).first().click({ timeout: 15_000 });
      await senderPage.getByTestId('workdsh-collaboration').getByText(uiSummary).waitFor({ timeout: 15_000 });
      const reply = senderPage.getByTestId('workdsh-collaboration').locator('article').filter({ hasText: uiSummary });
      await reply.getByRole('textbox', { name: /回执/ }).fill('review-done');
      await reply.getByRole('button', { name: '完成并回执' }).click({ timeout: 5_000 }).catch(async error => {
        throw new Error(`${error.message}\nBody: ${(await senderPage.locator('body').innerText()).slice(-900)}\nErrors: ${senderErrors.join(' | ')}`);
      });
      await senderPage.getByText('已完成并回执').waitFor({ timeout: 8_000 });
      assert.equal((await request('/api/collaboration/inbox', { runtime: sender.person.runtimeToken }))
        .find(row => row.summary === uiSummary)?.resolution, 'review-done');
      await senderPage.getByLabel('1 项待处理交接').waitFor({ state: 'hidden', timeout: 8_000 });
      await page.getByRole('button', { name: '刷新' }).click();
      await page.getByTestId('workdsh-collaboration').getByText('回执：review-done').waitFor({ timeout: 8_000 });
      await senderContext.close();
      await context.close(); await browser.close(); browser = undefined;
      console.log('PASS: packaged DSH sidebar shows an incoming item; native pages send and complete a handoff.');
    }
    if (gatewayProbe && !ordersTarball) {
      const { chromium } = await import(join(source, 'node_modules/@playwright/test/index.mjs'));
      browser = await chromium.launch({ headless: true });
      gatewayBrowserContext = await browser.newContext({ viewport: { width: 1440, height: 900 } });
      gatewayBrowserContext.on('page', page => page.on('websocket', socket => {
        if (socket.url().includes('/api/remote.mux')) gatewaySockets.push(socket);
      }));
      const page = await gatewayBrowserContext.newPage();
      const webOrigin = process.env.WORKDSH_ADMIN_WEB_ORIGIN ?? 'http://127.0.0.1:18891';
      const gatewayOrigin = process.env.WORKDSH_DSH_GATEWAY_ORIGIN ?? 'http://127.0.0.1:18892';
      await page.goto(webOrigin);
      await page.getByPlaceholder('name@company.com').fill(sender.person.email);
      await page.getByPlaceholder('输入密码').fill(sender.person.password);
      await page.getByRole('button', { name: '登录' }).click();
      await page.getByRole('button', { name: '打开我的 DSH' }).waitFor();
      assert.equal(await page.evaluate(() => sessionStorage.getItem('workdsh-admin-token')), null);
      const browserCookies = await gatewayBrowserContext.cookies(`${webOrigin}/api/auth/me`);
      assert.ok(browserCookies.some(cookie => cookie.name === 'workdsh-admin-session' && cookie.httpOnly));
      const popupPromise = gatewayBrowserContext.waitForEvent('page');
      await page.getByRole('button', { name: '打开我的 DSH' }).click();
      const popup = await popupPromise;
      await popup.waitForURL(url => url.origin === gatewayOrigin);
      await popup.getByText('协作交接', { exact: true }).first().waitFor({ timeout: 15_000 });
      const deadline = Date.now() + 5_000;
      while (!gatewaySockets.length && Date.now() < deadline) await new Promise(resolve => setTimeout(resolve, 50));
      assert.ok(gatewaySockets.length, 'DSH browser must establish a live WebSocket');
      recipientGatewayContext = await browser.newContext({ viewport: { width: 1440, height: 900 } });
      const recipientAdminPage = await recipientGatewayContext.newPage();
      await recipientAdminPage.goto(webOrigin);
      await recipientAdminPage.getByPlaceholder('name@company.com').fill(recipient.person.email);
      await recipientAdminPage.getByPlaceholder('输入密码').fill(recipient.person.password);
      await recipientAdminPage.getByRole('button', { name: '登录' }).click();
      const recipientPopupPromise = recipientGatewayContext.waitForEvent('page');
      await recipientAdminPage.getByRole('button', { name: '打开我的 DSH' }).click();
      const recipientPopup = await recipientPopupPromise;
      await recipientPopup.waitForURL(url => url.origin === gatewayOrigin);
      for (const name of ['Continue', 'Configure later'])
        await recipientPopup.getByRole('button', { name, exact: true }).click({ timeout: 2_000 }).catch(() => {});
      await recipientPopup.getByLabel('1 项待处理交接').waitFor({ timeout: 15_000 });
      await recipientPopup.getByText('协作交接', { exact: true }).first().click();
      await recipientPopup.getByTestId('workdsh-collaboration').getByText(summary).waitFor({ timeout: 15_000 });
      await recipientGatewayContext.close(); recipientGatewayContext = undefined;
      console.log('PASS: both members open separate collaboration-enabled DSH instances from the admin Web; the recipient sees the pending handoff without an order.');
    }
    const receivedSession = await nativeCall(recipient, 'workdsh_collaboration_inbox', {}, sent[0].id);
    recipient.webSessionId = receivedSession.sessionId;
    await nativeCall(recipient, 'workdsh_collaboration_complete', {
      handoff_id: sent[0].id, resolution: '复核完成：请补充来源说明',
    }, '复核完成');
    await nativeCall(sender, 'workdsh_collaboration_sent', {}, '复核完成');
    assert.equal((await request('/api/collaboration/sent', { runtime: sender.person.runtimeToken }))[0].status, 'DONE');
    console.log('PASS: a native DSH Session calculates first, then sends the result to a colleague; the other member completes and the sender reads back, without an order.');
    if (realModelProbe) {
      const runReal = async (host, input) => {
        const started = await fetch(`${host.origin}/api/enterprise-session-probe`, {
          method: 'POST', headers: { cookie: host.cookie, 'content-type': 'application/json' },
          body: JSON.stringify(input), signal: AbortSignal.timeout(15_000),
        });
        const beginning = await started.json();
        assert.equal(started.status, 200, JSON.stringify(beginning));
        const deadline = Date.now() + 150_000;
        let status;
        while (Date.now() < deadline) {
          const response = await fetch(`${host.origin}/api/enterprise-session-probe`, {
            method: 'POST', headers: { cookie: host.cookie, 'content-type': 'application/json' },
            body: JSON.stringify({ action: 'real-model-status', sessionId: beginning.sessionId }),
            signal: AbortSignal.timeout(10_000),
          });
          status = await response.json();
          assert.equal(response.status, 200, JSON.stringify(status));
          if (status.turnEnds > 0) break;
          await new Promise(resolve => setTimeout(resolve, 2000));
        }
        assert.ok(status?.turnEnds > 0, `Real-model turn did not finish: ${JSON.stringify(status)}`);
        return status;
      };
      const realSummary = `请核对 8 × 120 的计算结果并回执，验证编号 ${stamp}`;
      const status = await runReal(sender, { action: 'begin-real-model-handoff',
        recipientEmail: recipient.person.email, summary: realSummary });
      const colleaguesIndex = status.calledTools.indexOf('workdsh_collaboration_colleagues');
      const sendIndex = status.calledTools.indexOf('workdsh_collaboration_send');
      assert.ok(colleaguesIndex >= 0 && sendIndex > colleaguesIndex, JSON.stringify(status));
      const matching = (await request('/api/collaboration/sent', { runtime: sender.person.runtimeToken }))
        .filter(row => row.summary === realSummary);
      assert.equal(matching.length, 1, `Real-model handoff count: ${JSON.stringify(status)}`);
      const [handoff] = matching;
      assert.equal(handoff.recipientId, recipient.person.id);
      const replyStatus = await runReal(recipient, { action: 'begin-real-model-reply', handoffId: handoff.id });
      const inboxIndex = replyStatus.calledTools.indexOf('workdsh_collaboration_inbox');
      const completeIndex = replyStatus.calledTools.indexOf('workdsh_collaboration_complete');
      assert.ok(inboxIndex >= 0 && completeIndex > inboxIndex, JSON.stringify(replyStatus));
      assert.ok(!replyStatus.calledTools.includes('workdsh_collaboration_send'), JSON.stringify(replyStatus));
      const completed = (await request('/api/collaboration/inbox', { runtime: recipient.person.runtimeToken }))
        .find(row => row.id === handoff.id);
      assert.equal(completed?.status, 'DONE', JSON.stringify(replyStatus));
      assert.match(completed.resolution, /960/);
      await nativeCall(sender, 'workdsh_collaboration_sent', {}, '960');
      console.log('PASS: two real DeepSeek sessions on separate DSH Hosts sent, processed and completed one handoff.');
      const beforeAnalysis = (await request('/api/collaboration/sent', { runtime: sender.person.runtimeToken })).length;
      const analysis = await runReal(sender, { action: 'begin-real-model-analysis' });
      assert.ok(!analysis.calledTools.includes('workdsh_collaboration_send'), JSON.stringify(analysis));
      assert.equal((await request('/api/collaboration/sent', { runtime: sender.person.runtimeToken })).length,
        beforeAnalysis, 'Analysis-only request must not create a handoff');
      console.log('PASS: an analysis-only real-model request did not create a colleague handoff.');
    }
  }
  if (ordersTarball) {
    const { EnterpriseOrdersClient } = await import(join(source, 'packages/plugins/enterprise-orders/dist/index.js'));
    const [seller, reviewer] = hosts;
    let sellerSessionId;
    let reviewerSessionId;
    const client = host => new EnterpriseOrdersClient({ adminUrl: admin, runtimeToken: host.person.runtimeToken });
    const identity = host => ({ resolve: async () => ({ principalId: host.person.id, organizationId: host.organizationId }) });
    const order = await request('/api/orders', {
      method: 'POST', runtime: seller.person.runtimeToken,
      body: { customerName: '多实例协作测试', sourceType: 'EXCEL', sourceName: 'fixture.xlsx',
        lines: [{ customerReference: '1#AHU-1F-5', customerName: '板式 G4 滤网', quantity: 2 }] },
    });
    const uploadedSource = await request(`/api/orders/${order.id}/sources`, {
      method: 'POST', runtime: seller.person.runtimeToken,
      file: { name: 'fixture.pdf', mediaType: 'application/pdf', expectedRevision: order.revision,
        bytes: new TextEncoder().encode('%PDF-1.4 local synthetic fixture') },
    });
    assert.equal(uploadedSource.sourceType, 'PDF');
    const sessionProbe = async (host, tool) => {
      const response = await fetch(`${host.origin}/api/enterprise-session-probe`, {
        method: 'POST', headers: { cookie: host.cookie, 'content-type': 'application/json' },
        body: JSON.stringify({ tool }), signal: AbortSignal.timeout(25_000),
      });
      const result = await response.json();
      assert.equal(response.status, 200, JSON.stringify(result));
      assert.equal(result.ok, true, JSON.stringify(result));
      assert.equal(result.toolCalled, true, JSON.stringify(result) + '\nHOST LOG: ' + host.getLog().replace(/token=[^\s]+/g, 'token=[redacted]').slice(-2500));
      return result;
    };
    if (webSessionProbe) {
      const sellerConversation = await sessionProbe(seller, 'workdsh_order_list');
      sellerSessionId = sellerConversation.sessionId;
      assert.equal(sellerConversation.visible, true);
      const reviewerBeforeAssignment = await sessionProbe(reviewer, 'workdsh_review_inbox');
      reviewerSessionId = reviewerBeforeAssignment.sessionId;
      assert.equal(reviewerBeforeAssignment.hidden, true);
      if (gatewayProbe) {
        const gatewayOrigin = process.env.WORKDSH_DSH_GATEWAY_ORIGIN ?? 'http://127.0.0.1:18892';
        const crossSession = await fetch(`${gatewayOrigin}/api/enterprise-session-probe`, {
          method: 'POST', headers: { cookie: seller.gatewayCookie, origin: gatewayOrigin,
            'content-type': 'application/json' },
          body: JSON.stringify({ action: 'resume', sessionId: reviewerSessionId }),
          signal: AbortSignal.timeout(5_000),
        });
        assert.equal(crossSession.status, 500, 'seller gateway cannot resolve reviewer Session');
      }
      const { chromium } = await import(join(source, 'node_modules/@playwright/test/index.mjs'));
      browser = await chromium.launch({ headless: true });
      if (gatewayProbe) {
        const webOrigin = process.env.WORKDSH_ADMIN_WEB_ORIGIN ?? 'http://127.0.0.1:18891';
        gatewayBrowserContext = await browser.newContext();
        gatewayBrowserContext.on('page', page => page.on('websocket', socket => {
          if (socket.url().includes('/api/remote.mux')) gatewaySockets.push(socket);
        }));
        {
          const page = await gatewayBrowserContext.newPage();
          await page.goto(webOrigin);
          await page.getByPlaceholder('name@company.com').fill(seller.person.email);
          await page.getByPlaceholder('输入密码').fill(seller.person.password);
          await page.getByRole('button', { name: '登录' }).click();
          await page.getByRole('button', { name: '打开我的 DSH' }).waitFor();
          assert.equal(await page.evaluate(() => sessionStorage.getItem('workdsh-admin-token')), null);
          const browserCookies = await gatewayBrowserContext.cookies(`${webOrigin}/api/auth/me`);
          assert.ok(browserCookies.some(cookie => cookie.name === 'workdsh-admin-session' && cookie.httpOnly));
          const popupPromise = gatewayBrowserContext.waitForEvent('page');
          await page.getByRole('button', { name: '打开我的 DSH' }).click();
          const popup = await popupPromise;
          await popup.waitForURL(url => url.origin === (process.env.WORKDSH_DSH_GATEWAY_ORIGIN ?? 'http://127.0.0.1:18892'));
          await popup.getByText('New Session').first().waitFor();
          const deadline = Date.now() + 5_000;
          while (!gatewaySockets.length && Date.now() < deadline) await new Promise(resolve => setTimeout(resolve, 50));
          assert.ok(gatewaySockets.length, 'DSH browser must establish a live WebSocket');
          console.log('PASS: member signs into the Java/Vue admin UI and opens only their DSH gateway session.');
        }
      }
      const showConversation = async (host, sessionId, expected) => {
        const context = await browser.newContext({ viewport: { width: 1440, height: 900 } });
        try {
          const browserOrigin = gatewayProbe ? (process.env.WORKDSH_DSH_GATEWAY_ORIGIN ?? 'http://127.0.0.1:18892') : host.origin;
          const browserCookie = gatewayProbe ? host.gatewayCookie : host.cookie;
          await context.addCookies(browserCookie.split('; ').map(pair => {
            const at = pair.indexOf('=');
            return { name: pair.slice(0, at), value: pair.slice(at + 1), url: browserOrigin };
          }));
          const page = await context.newPage();
          const browserErrors = [];
          page.on('pageerror', error => browserErrors.push(error.message));
          page.on('response', response => { if (response.status() >= 400) browserErrors.push(`${response.status()} ${response.url()}`); });
          page.on('requestfailed', request => browserErrors.push(`FAILED ${request.url()} ${request.failure()?.errorText}`));
          page.on('console', message => { if (message.type() === 'error') browserErrors.push(`CONSOLE ${message.text()}`); });
          await page.goto(browserOrigin);
          for (const name of ['Continue', 'Configure later']) {
            await page.getByRole('button', { name, exact: true }).click({ timeout: 2_000 }).catch(() => {});
          }
          await page.waitForFunction(() => !!window.enterpriseProbe, null, { timeout: 15_000 });
          await page.evaluate(id => window.enterpriseProbe.open(id), sessionId);
          try { await page.getByText(expected, { exact: false }).first().waitFor({ timeout: 15_000 }); }
          catch (error) { throw new Error(`${error.message}\nURL: ${page.url()}\nBody: ${(await page.locator('body').innerText()).slice(0, 1300)}\nErrors: ${browserErrors.join(' | ')}`); }
        } finally { await context.close(); }
      };
      await showConversation(seller, sellerConversation.sessionId, 'ORDER_VISIBLE');
      await showConversation(reviewer, reviewerBeforeAssignment.sessionId, 'ORDER_HIDDEN');
      console.log('PASS: separate Web clients render their own native DSH Session results.');
    }
    assert.equal((await client(seller).list(identity(seller))).find(item => item.id === order.id)?.creatorId, seller.person.id);
    assert.equal((await client(seller).detail(identity(seller), order.id)).lines[0].customerReference, '1#AHU-1F-5');
    assert.equal((await client(seller).sources(identity(seller), order.id))[0]?.sha256, uploadedSource.sha256);
    assert.equal((await client(reviewer).inbox(identity(reviewer))).length, 0);
    const hidden = await fetch(`${admin}/api/orders/${order.id}`, {
      headers: { Authorization: `Runtime ${reviewer.person.runtimeToken}` }, signal: AbortSignal.timeout(5_000),
    });
    assert.equal(hidden.status, 404);
    const hiddenSource = await fetch(`${admin}/api/orders/${order.id}/sources/${uploadedSource.id}/download`, {
      headers: { Authorization: `Runtime ${reviewer.person.runtimeToken}` }, signal: AbortSignal.timeout(5_000),
    });
    assert.equal(hiddenSource.status, 404);
    const current = await request(`/api/orders/${order.id}`, { runtime: seller.person.runtimeToken });
    const submitted = await request(`/api/orders/${order.id}/submit-review`, {
      method: 'POST', runtime: seller.person.runtimeToken,
      body: { reviewerId: reviewer.person.id, expectedRevision: current.revision },
    });
    if (webSessionProbe) {
      const reviewerConversation = await sessionProbe(reviewer, 'workdsh_review_inbox');
      assert.equal(reviewerConversation.visible, true);
      console.log('PASS: both packaged Web Hosts run official Sessions through enterprise identity, tool access and the real order service.');
    }
    assert.equal((await client(reviewer).inbox(identity(reviewer)))[0]?.id, order.id);
    assert.equal((await client(reviewer).detail(identity(reviewer), order.id)).status, 'IN_REVIEW');
    assert.equal((await client(reviewer).sources(identity(reviewer), order.id))[0]?.id, uploadedSource.id);
    const readableSource = await fetch(`${admin}/api/orders/${order.id}/sources/${uploadedSource.id}/download`, {
      headers: { Authorization: `Runtime ${reviewer.person.runtimeToken}` }, signal: AbortSignal.timeout(5_000),
    });
    assert.equal(readableSource.status, 200);
    const returned = await request(`/api/orders/${order.id}/review`, {
      method: 'POST', runtime: reviewer.person.runtimeToken,
      body: { decision: 'CHANGES_REQUESTED', comment: '请核对库存 SKU', expectedRevision: submitted.revision },
    });
    assert.equal(returned.status, 'CHANGES_REQUESTED');
    assert.equal((await client(reviewer).inbox(identity(reviewer))).length, 0);
    console.log('PASS: seller and reviewer DSH instance credentials complete an order handoff; reviewer cannot read before assignment.');
    seller.webSessionId = sellerSessionId;
    reviewer.webSessionId = reviewerSessionId;
  }
  if (demoProbe) {
    const webOrigin = process.env.WORKDSH_ADMIN_WEB_ORIGIN ?? 'http://127.0.0.1:18891';
    console.log(`LOCAL COLLABORATION DEMO READY: ${webOrigin}`);
    for (const [index, person] of people.entries()) {
      console.log(`Member ${index + 1}: ${person.email} / ${person.password}`);
    }
    console.log('Log in as either member, choose “打开我的 DSH”, then use “协作交接”. Type q and press Enter here to revoke test access and stop both DSH Hosts.');
    if (!stopRequested) await new Promise(resolve => {
      const stop = () => {
        process.stdin.off('data', onInput);
        process.off('SIGINT', stop);
        process.off('SIGTERM', stop);
        process.stdin.pause();
        process.stdin.destroy();
        resolve();
      };
      const onInput = input => { if (String(input).trim().toLowerCase() === 'q') stop(); };
      process.stdin.setEncoding('utf8');
      process.stdin.resume();
      process.stdin.on('data', onInput);
      process.once('SIGINT', stop);
      process.once('SIGTERM', stop);
    });
  }
  await request(`/api/admin/members/${people[0].id}/runtime-credentials/revoke`, {
    method: 'POST', bearer: adminBearer,
  });
  assert.equal((await runtimeIdentity(people[0].runtimeToken)).status, 401);
  assert.equal((await runtimeIdentity(people[1].runtimeToken)).status, 200);
  if (webSessionProbe) {
    const resume = async host => {
      const response = await fetch(`${host.origin}/api/enterprise-session-probe`, {
        method: 'POST', headers: { cookie: host.cookie, 'content-type': 'application/json' },
        body: JSON.stringify({ action: 'resume', sessionId: host.webSessionId }),
        signal: AbortSignal.timeout(10_000),
      });
      return { status: response.status, body: await response.json() };
    };
    const revoked = await resume(hosts[0]);
    assert.equal(revoked.status, 500, JSON.stringify(revoked.body));
    assert.match(revoked.body.error, /denied|401|credential|identity/i);
    const unaffected = await resume(hosts[1]);
    assert.equal(unaffected.status, 200, JSON.stringify(unaffected.body));
    assert.equal(unaffected.body.ok, true);
    console.log('PASS: revoked member cannot resume an existing native DSH Session; the other member can.');
    if (gatewayProbe) {
      const gatewayOrigin = process.env.WORKDSH_DSH_GATEWAY_ORIGIN ?? 'http://127.0.0.1:18892';
      assert.equal((await fetch(gatewayOrigin, { headers: { cookie: hosts[0].gatewayCookie } })).status, 401);
      assert.equal((await fetch(gatewayOrigin, { headers: { cookie: hosts[1].gatewayCookie } })).status, 200);
      const deadline = Date.now() + 5_000;
      while (!gatewaySockets.some(socket => socket.isClosed()) && Date.now() < deadline) {
        await new Promise(resolve => setTimeout(resolve, 100));
      }
      assert.ok(gatewaySockets.some(socket => socket.isClosed()), 'revocation must close an existing gateway WebSocket');
      console.log('PASS: gateway rejects the revoked member immediately and keeps the other member online.');
      await request('/api/auth/logout', { method: 'POST', bearer: hosts[1].person.bearer });
      assert.equal((await fetch(gatewayOrigin, { headers: { cookie: hosts[1].gatewayCookie } })).status, 401);
      console.log('PASS: admin logout invalidates the corresponding DSH gateway session.');
    }
  }
  console.log('PASS: two isolated DSH Hosts start with separate member credentials, data homes and Web sessions; cross-instance cookies are rejected.');
  console.log('PASS: revoking one member\'s runtime credential leaves the other member active.');
} finally {
  if (gatewayBrowserContext) await gatewayBrowserContext.close();
  if (recipientGatewayContext) await recipientGatewayContext.close();
  if (browser) await browser.close();
  if (gatewayProcess && gatewayProcess.exitCode === null && gatewayProcess.signalCode === null) {
    const stopped = new Promise(resolve => gatewayProcess.once('close', resolve));
    gatewayProcess.kill('SIGTERM');
    await stopped;
  }
  const stopResults = await Promise.allSettled(hosts.map(async ({ processHandle }) => {
    if (processHandle.exitCode !== null || processHandle.signalCode !== null) return;
    const stopped = new Promise(resolve => processHandle.once('close', resolve));
    processHandle.kill('SIGTERM');
    const timer = setTimeout(() => processHandle.kill('SIGKILL'), 3000);
    await stopped;
    clearTimeout(timer);
  }));
  try {
    await Promise.all(people.map(person => request(`/api/admin/members/${person.id}/runtime-credentials/revoke`, {
      method: 'POST', bearer: adminBearer,
    })));
    const directory = await request('/api/admin/members', { bearer: adminBearer });
    await Promise.all(people.map(person => {
      const member = directory.find(item => item.id === person.id);
      if (!member?.active) return Promise.resolve();
      return request(`/api/admin/members/${person.id}`, {
        method: 'PATCH', bearer: adminBearer,
        body: { active: false, expectedRevision: member.revision },
      });
    }));
  } finally {
    await Promise.all(homes.map(home => rm(home, { recursive: true, force: true })));
  }
  const failedStop = stopResults.find(result => result.status === 'rejected');
  if (failedStop) throw failedStop.reason;
}
