package com.techflag.workdsh.admin;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:sqlite:file:providers-test?mode=memory&cache=shared","workdsh.bootstrap.admin-email=providers@example.test","workdsh.bootstrap.admin-password=ProviderPassword123!","workdsh.secrets.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@ActiveProfiles("enterprise-sqlite")
class ModelProvidersTest {
 @Autowired ModelProviders providers;@Autowired ModelProvidersController controller;@Autowired ModelGateway gateway;@Autowired AuthService auth;@Autowired JdbcTemplate db;
 ModelProviders.Provider provider(String id,String model,String protocol,String key){return new ModelProviders.Provider(id,id,"https://example.test/v1",protocol,true,List.of(new ModelProviders.Model(model,"Model "+model,256000L,32000L,true)),false,key);}
 @Test void providersRoutingSecretsAndAtomicSave(){
  String bearer="Bearer "+auth.login("providers@example.test","ProviderPassword123!").token();var actor=auth.actor(bearer);String org=actor.organizationId();
  assertEquals(0,providers.read(actor).revision());assertTrue(providers.read(actor).providers().isEmpty());
  var first=provider("company","first","openai-completions","first-supplier-fixture");var second=provider("second","second-model","anthropic-messages","second-supplier-secret");
  assertEquals(400,assertThrows(ResponseStatusException.class,()->controller.update(bearer,new ModelProviders.Update(0,List.of(first,second),"short"))).getStatusCode().value());assertTrue(providers.read(actor).providers().isEmpty());
  var saved=controller.update(bearer,new ModelProviders.Update(0,List.of(first,second),"internal-member-key-abcdefghijklmnopqrstuvwxyz")).getBody();assertEquals(1,saved.revision());assertEquals("first-supplier-fixture",providers.reveal(actor,"company"));assertEquals("internal-member-key-abcdefghijklmnopqrstuvwxyz",gateway.reveal(bearer).getBody().get("key"));assertEquals("no-store",gateway.reveal(bearer).getHeaders().getFirst("Cache-Control"));String memberId=UUID.randomUUID().toString();db.update("insert into members(id,organization_id,email,display_name,role,password_hash,must_change_password,active,revision) values(?,?,?,?,?,?,false,true,1)",memberId,org,memberId+"@example.test","Member","MEMBER",auth.encode("MemberPassword123!"));String memberBearer="Bearer "+auth.login(memberId+"@example.test","MemberPassword123!").token();assertEquals(403,assertThrows(ResponseStatusException.class,()->gateway.reveal(memberBearer)).getStatusCode().value());assertEquals(403,assertThrows(ResponseStatusException.class,()->providers.reveal(auth.actor(memberBearer),"company")).getStatusCode().value());assertTrue(saved.providers().stream().allMatch(p->p.apiKey()==null));
  assertEquals("first-supplier-fixture",providers.resolve(org,"first","openai-completions").apiKey());assertEquals("second-supplier-secret",providers.resolve(org,"second-model","anthropic-messages").apiKey());
  assertEquals(400,assertThrows(ResponseStatusException.class,()->providers.resolve(org,"first","anthropic-messages")).getStatusCode().value());assertEquals(403,assertThrows(ResponseStatusException.class,()->providers.resolve(org,"foreign","openai-completions")).getStatusCode().value());
  assertEquals(2,providers.catalog(org).size());assertEquals(256000L,providers.catalog(org).get(0).get("context_window"));
  String stored=db.queryForObject("select providers_json from organization_model_providers where organization_id=?",String.class,org);assertFalse(stored.contains("first-supplier-fixture"));assertFalse(stored.contains("second-supplier-secret"));
  assertEquals(409,assertThrows(ResponseStatusException.class,()->controller.update(bearer,new ModelProviders.Update(0,List.of(first),""))).getStatusCode().value());
  assertEquals(400,assertThrows(ResponseStatusException.class,()->controller.update(bearer,new ModelProviders.Update(1,List.of(first,provider("duplicate","first","openai-completions","another-secret")),""))).getStatusCode().value());
  controller.update(bearer,new ModelProviders.Update(1,List.of(second),""));assertEquals(1,providers.catalog(org).size());assertEquals("second-supplier-secret",providers.resolve(org,"second-model","anthropic-messages").apiKey());assertEquals(403,assertThrows(ResponseStatusException.class,()->providers.resolve(org,"first","openai-completions")).getStatusCode().value());
 }
}
