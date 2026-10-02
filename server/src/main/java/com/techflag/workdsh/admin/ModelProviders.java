package com.techflag.workdsh.admin;
import java.net.URI;
import java.util.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ModelProviders {
 private final JdbcTemplate db; private final AuthService auth; private final MemberSecrets secrets; private final DatabaseDialect dialect; private final ObjectMapper json; private final MemberController audit;
 public ModelProviders(JdbcTemplate db,AuthService auth,MemberSecrets secrets,DatabaseDialect dialect,ObjectMapper json,MemberController audit){this.db=db;this.auth=auth;this.secrets=secrets;this.dialect=dialect;this.json=json;this.audit=audit;}
 public record Model(String id,String displayName,Long contextWindow,Long maxOutputTokens,boolean images){}
 public record Provider(String id,String displayName,String baseUrl,String protocol,boolean enabled,List<Model> models,boolean hasKey,String apiKey){}
 public record View(long revision,List<Provider> providers){}
 public record Update(long expectedRevision,List<Provider> providers,String accessKey){}
 public record Resolved(String baseUrl,String apiKey){}
 private record Stored(long revision,List<Provider> providers){}
 private Stored stored(String org){
  var rows=db.query("select revision,providers_json from organization_model_providers where organization_id=?",(r,n)->{try{return new Stored(r.getLong(1),json.readValue(r.getString(2),new TypeReference<List<Provider>>(){}));}catch(Exception e){throw new IllegalStateException("Invalid provider configuration");}},org);
  if(!rows.isEmpty())return rows.get(0);
  return new Stored(0,List.of());
 }
 public View read(AuthService.Actor actor){auth.requireReady(actor);auth.requireAdmin(actor);var s=stored(actor.organizationId());return new View(s.revision,s.providers.stream().map(p->new Provider(p.id,p.displayName,p.baseUrl,p.protocol,p.enabled,p.models,p.hasKey,null)).toList());}
 public String reveal(AuthService.Actor actor,String id){auth.requireReady(actor);auth.requireAdmin(actor);var provider=stored(actor.organizationId()).providers.stream().filter(p->p.id.equals(id)).findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));if(!provider.hasKey)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"API key not configured");String value=secrets.cryptOrganizationModel(false,actor.organizationId(),provider.apiKey);audit.audit(actor,"organization.model.provider.key.viewed",id);return value;}
 @Transactional public View update(AuthService.Actor actor,Update update){
  auth.requireReady(actor);auth.requireAdmin(actor);
  if(update==null||update.expectedRevision<0||update.providers==null||update.providers.size()>32)bad("Invalid providers");
  String org=actor.organizationId();db.queryForObject(dialect.lock("select id from organizations where id=?"),String.class,org);
  var current=stored(org);if(current.revision!=update.expectedRevision)throw new ResponseStatusException(HttpStatus.CONFLICT,"Configuration changed; refresh before saving");
  Set<String> providerIds=new HashSet<>(),modelIds=new HashSet<>();List<Provider> next=new ArrayList<>();
  for(var p:update.providers){
   if(p==null||p.id==null||!p.id.matches("[a-z][a-z0-9_-]{0,63}")||!providerIds.add(p.id)||p.displayName==null||p.displayName.isBlank()||p.displayName.length()>128||!List.of("openai-completions","anthropic-messages").contains(p.protocol)||p.models==null||p.models.size()>64)bad("Invalid provider fields");
   try{URI u=URI.create(p.baseUrl);if(!"https".equals(u.getScheme())||u.getHost()==null||u.getUserInfo()!=null||u.getQuery()!=null||u.getFragment()!=null||p.baseUrl.length()>1024)bad("HTTPS provider URL required");}catch(Exception e){bad("HTTPS provider URL required");}
   Set<String> own=new HashSet<>();for(var m:p.models){if(m==null||m.id==null||!m.id.matches("[A-Za-z0-9][A-Za-z0-9._:/-]{0,127}")||!own.add(m.id)||m.displayName==null||m.displayName.length()>128||!validLimit(m.contextWindow)||!validLimit(m.maxOutputTokens)||m.contextWindow!=null&&m.maxOutputTokens!=null&&m.maxOutputTokens>m.contextWindow)bad("Invalid model fields");if(p.enabled&&!modelIds.add(m.id))bad("Enabled interfaces must use different model IDs");}
   String encrypted=current.providers.stream().filter(old->old.id.equals(p.id)).map(Provider::apiKey).findFirst().orElse("");
   if(p.apiKey!=null&&!p.apiKey.isEmpty()){if(p.apiKey.length()>8192||p.apiKey.chars().anyMatch(Character::isISOControl))bad("Invalid API key");encrypted=secrets.cryptOrganizationModel(true,org,p.apiKey);}
   if(p.enabled&&(encrypted.isEmpty()||p.models.isEmpty()))bad("Enabled interface requires a key and at least one model");
   next.add(new Provider(p.id,p.displayName,p.baseUrl,p.protocol,p.enabled,List.copyOf(p.models),!encrypted.isEmpty(),encrypted));
  }
  try{String payload=json.writeValueAsString(next);if(current.revision==0)db.update("insert into organization_model_providers(organization_id,revision,providers_json) values(?,1,?)",org,payload);else if(db.update("update organization_model_providers set revision=?,providers_json=? where organization_id=? and revision=?",current.revision+1,payload,org,current.revision)!=1)throw new ResponseStatusException(HttpStatus.CONFLICT);}catch(ResponseStatusException e){throw e;}catch(Exception e){throw new IllegalStateException("Cannot save providers",e);}
  audit.audit(actor,"organization.model.providers.updated",org);return read(actor);
 }
 private static boolean validLimit(Long value){return value==null||value>0&&value<=100000000;}
 private static void bad(String message){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
 List<Map<String,Object>> catalog(String org){List<Map<String,Object>> result=new ArrayList<>();for(var p:stored(org).providers)if(p.enabled&&p.hasKey)for(var m:p.models){Map<String,Object> row=new LinkedHashMap<>();row.put("id",m.id);row.put("object","model");row.put("owned_by",p.id);row.put("name",m.displayName);if(m.contextWindow!=null)row.put("context_window",m.contextWindow);if(m.maxOutputTokens!=null)row.put("max_output_tokens",m.maxOutputTokens);row.put("input_modalities",m.images?List.of("text","image"):List.of("text"));result.add(row);}return result;}
 Resolved resolve(String org,String model,String protocol){for(var p:stored(org).providers)if(p.enabled&&p.hasKey&&p.models.stream().anyMatch(m->m.id.equals(model))){if(!p.protocol.equals(protocol))bad("Choose the protocol configured for this model interface");return new Resolved(p.baseUrl,secrets.cryptOrganizationModel(false,org,p.apiKey));}throw new ResponseStatusException(HttpStatus.FORBIDDEN,"Model is not allowed");}
}
