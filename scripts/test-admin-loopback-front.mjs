import assert from 'node:assert/strict';
import {test} from 'node:test';
import {createServer as httpsServer} from 'node:https';
import {createServer,request} from 'node:http';
import {mkdtemp,readFile,rm} from 'node:fs/promises';
import {tmpdir} from 'node:os';
import {join} from 'node:path';
import {execFileSync} from 'node:child_process';
import {createAdminFront} from '../deploy/docker/admin-loopback-front.mjs';
const listen=server=>new Promise((resolve,reject)=>{server.once('error',reject);server.listen(0,'127.0.0.1',()=>resolve('http://127.0.0.1:'+server.address().port));});

test('internal model API preserves key while management only uses same-origin cookie',async()=>{
 const directory=await mkdtemp(join(tmpdir(),'workdsh-admin-front-'));const servers=[],sockets=new Set();
 const track=server=>{servers.push(server);server.on('connection',socket=>{sockets.add(socket);socket.once('close',()=>sockets.delete(socket));});return server;};
 try{
  const key=join(directory,'key.pem'),cert=join(directory,'cert.pem');
  execFileSync('openssl',['req','-x509','-newkey','rsa:2048','-nodes','-days','1','-subj','/CN=127.0.0.1','-addext','subjectAltName=IP:127.0.0.1','-addext','basicConstraints=critical,CA:TRUE','-keyout',key,'-out',cert],{stdio:'ignore'});
  const seen=[];const tls={key:await readFile(key),cert:await readFile(cert)};
  const backend=track(httpsServer(tls,(req,res)=>{seen.push({path:req.url,headers:req.headers});req.resume();req.once('end',()=>{res.writeHead(200,{'content-type':'application/json'});res.end('{"ok":true}');});}));
  const backendOrigin=(await listen(backend)).replace('http:','https:');
  const reserve=createServer();const origin=await listen(reserve);await new Promise(resolve=>reserve.close(resolve));
  const front=track(createAdminFront({origin,backend:backendOrigin,backendOrigin,ca:tls.cert}));await new Promise(resolve=>front.listen(Number(new URL(origin).port),'127.0.0.1',resolve));
  const cookie='other=private; workdsh-admin-session=fixture-admin';
  assert.equal((await fetch(origin+'/api/auth/browser-login',{method:'POST',body:'{}'})).status,403);
  assert.equal((await fetch(origin+'/api/auth/browser-login',{method:'POST',headers:{origin,cookie,authorization:'Bearer ignored-management-key','x-api-key':'ignored'},body:'{}'})).status,200);
  assert.equal(seen.at(-1).headers.cookie,'workdsh-admin-session=fixture-admin');assert.equal(seen.at(-1).headers.authorization,undefined);assert.equal(seen.at(-1).headers['x-api-key'],undefined);assert.equal(seen.at(-1).headers.origin,backendOrigin);
  assert.equal((await fetch(origin+'/api/model-gateway/v1/models',{headers:{authorization:'Bearer internal-model-fixture',cookie}})).status,200);
  assert.equal(seen.at(-1).headers.authorization,'Bearer internal-model-fixture');assert.equal(seen.at(-1).headers.cookie,undefined);
  assert.equal((await fetch(origin+'/api/model-gateway/v1/messages',{method:'POST',headers:{'x-api-key':'internal-messages-fixture',cookie,'content-type':'application/json'},body:'{}'})).status,200);
  assert.equal(seen.at(-1).headers['x-api-key'],'internal-messages-fixture');assert.equal(seen.at(-1).headers.cookie,undefined);
  const before=seen.length;
  assert.equal((await fetch(origin+'/api/model-gateway/v1/models?target=other')).status,404);
  assert.equal((await fetch(origin+'/api/model-gateway/v1/other',{method:'POST'})).status,404);
  assert.equal((await fetch(origin+'/api/model-gateway/v1/models',{method:'POST'})).status,405);
  assert.equal((await fetch(origin+'/api/auth/logout',{method:'POST',headers:{origin:'http://other.test'},body:'{}'})).status,403);
  const absolute=await new Promise((resolve,reject)=>{const req=request(origin,{path:'https://evil.test/api/auth/me',headers:{host:new URL(origin).host}},res=>{res.resume();res.once('end',()=>resolve(res.statusCode));});req.once('error',reject);req.end();});assert.equal(absolute,403);assert.equal(seen.length,before);
 }finally{for(const socket of sockets)socket.destroy();await Promise.all(servers.map(server=>new Promise(resolve=>server.close(resolve))));await rm(directory,{recursive:true,force:true});}
});
