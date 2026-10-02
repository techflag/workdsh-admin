/** Loopback acceptance front. Production TLS termination is deployment-owned. */
import {createServer} from 'node:http';
import {request} from 'node:https';
import {resolve} from 'node:path';
import {fileURLToPath} from 'node:url';

export function createAdminFront({origin,backend,backendOrigin,ca}) {
 origin=new URL(origin);backend=new URL(backend);const trustedOrigin=new URL(backendOrigin);
 for(const value of [origin,backend,trustedOrigin])if(value.pathname!=='/'||value.search||value.hash||value.username||value.password)throw Error('Fixed origins required');
 if(origin.protocol!=='http:'||origin.hostname!=='127.0.0.1'||backend.protocol!=='https:')throw Error('Explicit loopback front and HTTPS backend required');
 return createServer((req,res)=>{
  const fail=status=>{res.writeHead(status,{'cache-control':'no-store'});res.end();};
  let url;try{url=new URL(req.url,origin);}catch{return fail(400);}
  if(!req.url.startsWith('/')||req.url.startsWith('//')||url.origin!==origin.origin||req.headers.host!==origin.host||req.headers['sec-fetch-site']==='cross-site')return fail(403);
  const model=/^\/api\/model-gateway\/v1\/(models|messages|chat\/completions)$/.test(url.pathname);
  if(url.pathname.startsWith('/api/model-gateway/')&&(!model||url.search))return fail(404);
  if(model&&(url.pathname.endsWith('/models')?req.method!=='GET':req.method!=='POST'))return fail(405);
  if(!model&&!['GET','HEAD'].includes(req.method)&&req.headers.origin!==origin.origin)return fail(403);
  const headers={...req.headers,host:backend.host};
  for(const key of ['proxy-authorization','x-forwarded-for','x-forwarded-host','x-forwarded-proto'])delete headers[key];
  if(model){delete headers.cookie;delete headers.origin;}
  else{delete headers.authorization;delete headers['x-api-key'];headers.cookie=(req.headers.cookie??'').split(';').map(x=>x.trim()).filter(x=>x.startsWith('workdsh-admin-session=')).join('; ');if(headers.origin)headers.origin=trustedOrigin.origin;}
  const upstream=request(new URL(url.pathname+url.search,backend),{method:req.method,headers,ca},reply=>{res.writeHead(reply.statusCode,{...reply.headers,'cache-control':'no-store'});reply.pipe(res);});
  upstream.setTimeout(15000,()=>upstream.destroy());upstream.on('error',()=>{if(!res.headersSent)res.writeHead(502);res.end();});res.on('close',()=>upstream.destroy());req.pipe(upstream);
 });
}
if(process.argv[1]&&resolve(process.argv[1])===fileURLToPath(import.meta.url)) {
 const origin=new URL(process.env.WORKDSH_ADMIN_FRONT_ORIGIN);
 createAdminFront({origin,backend:process.env.WORKDSH_ADMIN_BACKEND,backendOrigin:process.env.WORKDSH_ADMIN_BACKEND_ORIGIN}).listen(Number(origin.port||80),'0.0.0.0');
}
