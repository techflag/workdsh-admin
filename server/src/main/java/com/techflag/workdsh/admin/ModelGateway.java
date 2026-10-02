package com.techflag.workdsh.admin;
import java.net.URI;import java.net.http.*;import java.time.Duration;import java.security.MessageDigest;import java.nio.charset.StandardCharsets;import java.util.HexFormat;import java.util.List;
import org.springframework.web.bind.annotation.*;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.http.HttpStatus;import org.springframework.http.ResponseEntity;import org.springframework.web.server.ResponseStatusException;import org.springframework.transaction.annotation.Transactional;import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
@RestController public class ModelGateway {
 private final JdbcTemplate db;private final AuthService auth;private final MemberController audit;private final com.fasterxml.jackson.databind.ObjectMapper json;
 private final MemberSecrets secrets; private final SpringAiModelClient springAi; private final ModelProviders providers;
 private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15)).followRedirects(HttpClient.Redirect.NEVER).build();
 public ModelGateway(JdbcTemplate db,AuthService auth,MemberController audit,com.fasterxml.jackson.databind.ObjectMapper json,SpringAiModelClient springAi,ModelProviders providers,MemberSecrets secrets){this.secrets=secrets;this.providers=providers;this.springAi=springAi;this.db=db;this.auth=auth;this.audit=audit;this.json=json;}
 public record Config(boolean hasAccessKey){} public record Update(String accessKey){}
 static String hash(String key){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
 @GetMapping("/api/admin/model-gateway") public Config read(@RequestHeader("Authorization")String bearer){var a=auth.actor(bearer);auth.requireReady(a);auth.requireAdmin(a);return new Config(!db.queryForList("select organization_id from organization_model_gateway where organization_id=?",String.class,a.organizationId()).isEmpty());}
 @PutMapping("/api/admin/model-gateway") @Transactional public Config update(@RequestHeader("Authorization")String bearer,@RequestBody Update u){
  var a=auth.actor(bearer);auth.requireReady(a);auth.requireAdmin(a);if(u==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Internal access configuration required");
  if(u.accessKey()!=null&&!u.accessKey().isBlank()){
   if(u.accessKey().length()<32||u.accessKey().length()>512||u.accessKey().chars().anyMatch(Character::isWhitespace))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Internal access key must contain 32–512 non-whitespace characters");
   if(db.update("update organization_model_gateway set access_hash=? where organization_id=?",hash(u.accessKey()),a.organizationId())==0)db.update("insert into organization_model_gateway(organization_id,access_hash) values(?,?)",a.organizationId(),hash(u.accessKey()));
   String encrypted=secrets.cryptOrganizationModel(true,a.organizationId(),u.accessKey());if(db.update("update organization_model_gateway_keys set encrypted_key=? where organization_id=?",encrypted,a.organizationId())==0)db.update("insert into organization_model_gateway_keys(organization_id,encrypted_key) values(?,?)",a.organizationId(),encrypted);
  }else if(!read(bearer).hasAccessKey())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Internal access key required");
  audit.audit(a,"organization.model.gateway.updated",a.organizationId());return read(bearer);
 }
 @PostMapping("/api/admin/model-gateway/reveal-key") public ResponseEntity<java.util.Map<String,String>> reveal(@RequestHeader("Authorization")String bearer){var actor=auth.actor(bearer);auth.requireReady(actor);auth.requireAdmin(actor);var keys=db.queryForList("select encrypted_key from organization_model_gateway_keys where organization_id=?",String.class,actor.organizationId());if(keys.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"Internal access key not configured");String value=secrets.cryptOrganizationModel(false,actor.organizationId(),keys.get(0));audit.audit(actor,"organization.model.gateway.key.viewed",actor.organizationId());return ResponseEntity.ok().header("Cache-Control","no-store").body(java.util.Map.of("key",value));}
 @GetMapping("/api/model-gateway/v1/models") public java.util.Map<String,Object> catalog(@RequestHeader(value="Authorization",required=false)String bearer,@RequestHeader(value="x-api-key",required=false)String key){var route=authorize(bearer,key);return java.util.Map.of("object","list","data",providers.catalog(route.org()));}
 record Route(String org){}
 Route authorize(String authorization,String apiKey){String token=authorization!=null&&authorization.startsWith("Bearer ")?authorization.substring(7):apiKey;if(token==null||token.length()<32||token.length()>512)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid internal API key");return db.query("select organization_id from organization_model_gateway where access_hash=?",(r,n)->new Route(r.getString(1)),hash(token)).stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Invalid internal API key"));}
 @PostMapping(value={"/api/model-gateway/v1/messages","/api/model-gateway/v1/chat/completions"}) public ResponseEntity<StreamingResponseBody> forward(jakarta.servlet.http.HttpServletRequest request,@RequestHeader(value="Authorization",required=false)String authorization,@RequestHeader(value="x-api-key",required=false)String apiKey,@RequestBody byte[] body){
 var route=authorize(authorization,apiKey);boolean messages=request.getRequestURI().endsWith("/messages");if(body.length>4*1024*1024)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE);
 ModelProviders.Resolved connection;try{var input=json.readTree(body);if(!input.hasNonNull("model")||!input.get("model").isTextual())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Model ID required");connection=providers.resolve(route.org(),input.get("model").asText(),messages?"anthropic-messages":"openai-completions");}catch(ResponseStatusException e){throw e;}catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid JSON request");}

 if(!messages) {
  try {
   boolean streaming=json.readTree(body).path("stream").asBoolean(false);
   if(!streaming) {
    byte[] result=json.writeValueAsBytes(springAi.complete(connection.baseUrl(),connection.apiKey(),body));
    return ResponseEntity.ok().header("Cache-Control","no-store").header("Content-Type","application/json").body(out->out.write(result));
   }
   var chunks=springAi.stream(connection.baseUrl(),connection.apiKey(),body);
   StreamingResponseBody response=out->{
    try(var iterator=chunks.toStream()) {
     for(var chunk:(Iterable<org.springframework.ai.openai.api.OpenAiApi.ChatCompletionChunk>)iterator::iterator) {
      out.write(("data: "+json.writeValueAsString(chunk)+"\n\n").getBytes(StandardCharsets.UTF_8));out.flush();
     }
     out.write("data: [DONE]\n\n".getBytes(StandardCharsets.UTF_8));out.flush();
    } catch(java.io.IOException e){throw e;}catch(Exception e){throw new java.io.IOException("Model provider stream failed");}
   };
   return ResponseEntity.ok().header("Cache-Control","no-store").header("Content-Type","text/event-stream").body(response);
  }catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Model provider request failed");}
 }
 try {
  if (!json.readTree(body).path("stream").asBoolean(false)) {
   byte[] result=json.writeValueAsBytes(springAi.completeMessages(connection.baseUrl(),connection.apiKey(),request.getHeader("anthropic-version"),body));
   return ResponseEntity.ok().header("Cache-Control","no-store").header("Content-Type","application/json").body(out->out.write(result));
  }
 } catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Model provider request failed");}
 // Preserve native Anthropic SSE events; Spring AI aggregates and filters these events.
 try{URI base=URI.create(connection.baseUrl());String path=base.getPath().replaceAll("/$","");if(messages&&base.getHost().equals("api.deepseek.com")&&path.isEmpty())path="/anthropic";if(!path.endsWith("/v1"))path+="/v1";URI target=new URI(base.getScheme(),null,base.getHost(),base.getPort(),path+(messages?"/messages":"/chat/completions"),null,null);var q=HttpRequest.newBuilder(target).timeout(Duration.ofMinutes(5)).header("Content-Type","application/json");if(messages){q.header("x-api-key",connection.apiKey());q.header("anthropic-version",request.getHeader("anthropic-version")==null?"2023-06-01":request.getHeader("anthropic-version"));}else q.header("Authorization","Bearer "+connection.apiKey());var reply=client.send(q.POST(HttpRequest.BodyPublishers.ofByteArray(body)).build(),HttpResponse.BodyHandlers.ofInputStream());if(reply.statusCode()<200||reply.statusCode()>=300){reply.body().close();throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Model provider rejected request");}StreamingResponseBody stream=out->{try(var in=reply.body()){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1){out.write(buffer,0,n);out.flush();}}};return ResponseEntity.ok().header("Cache-Control","no-store").header("Content-Type",reply.headers().firstValue("Content-Type").orElse("application/json")).body(stream);}catch(ResponseStatusException e){throw e;}catch(Exception e){if(e instanceof InterruptedException)Thread.currentThread().interrupt();throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,"Model provider connection failed");}
 }
}
