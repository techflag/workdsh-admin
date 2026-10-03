package com.techflag.workdsh.admin;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.servlet.http.Cookie;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:desktop-visible;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","workdsh.bootstrap.admin-email=desktop-owner@example.test","workdsh.bootstrap.admin-password=DesktopFixturePassword123!","workdsh.secrets.service-key=desktop-service-fixture-000000000000"})
@AutoConfigureMockMvc
class DesktopVisibleSessionsTest {
 @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate db;@Autowired AuthService auth;@Autowired DesktopVisibleSessions service;
 record Account(String id,String org,String bearer){}
 Account owner(){var login=auth.login("desktop-owner@example.test","DesktopFixturePassword123!");return new Account(login.member().id(),login.member().organizationId(),"Bearer "+login.token());}
 Account member(String org,boolean ready){String id=UUID.randomUUID().toString(),email=id+"@example.test";db.update("insert into members values(?,?,?,?,?,?,?,true,1)",id,org,email,"Desktop member","MEMBER",auth.encode("DesktopFixturePassword123!"),!ready);return new Account(id,org,"Bearer "+auth.login(email,"DesktopFixturePassword123!").token());}
 String session(){return "session-"+UUID.randomUUID();}
 Map<String,Object> entry(int seq){return Map.of("seq",seq,"recordId","record-"+seq,"role",seq%2==0?"user":"assistant","text",seq%2==0?"Visible question "+seq:"Visible answer "+seq);}
 ObjectNode snapshot(String id,String device,int revision,int count){var entries=new ArrayList<>();for(int i=0;i<count;i++)entries.add(entry(i));return json.valueToTree(Map.of("version",1,"deviceId",device,"sessionId",id,"revision",revision,"requestId","request-"+revision,"entries",entries));}
 JsonNode call(MockHttpServletRequestBuilder request,int code)throws Exception{var response=mvc.perform(request).andReturn().getResponse();assertEquals(code,response.getStatus());return response.getContentAsString().isBlank()?json.nullNode():json.readTree(response.getContentAsString());}
 JsonNode ingest(Account account,ObjectNode input,int code)throws Exception{return call(post("/api/member/visible-sessions").header("Authorization",account.bearer()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(input)),code);}
 MockHttpServletRequestBuilder status(Account a,String id,String device){return get("/api/member/visible-sessions").header("Authorization",a.bearer()).param("sessionId",id).param("deviceId",device);}
 @Test void explicitMemberBearerFreshAuthorizationAndNoSelfReportedIdentity()throws Exception{
  var o=owner();var a=member(o.org(),true);String id=session();var body=snapshot(id,"device-A",1,1);
  call(post("/api/member/visible-sessions").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)).header("X-WorkDSH-Service-Key","desktop-service-fixture-000000000000"),401);
  call(post("/api/member/visible-sessions").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(body)).header("Origin","http://127.0.0.1:18891").cookie(new Cookie(BrowserSessionFilter.COOKIE_NAME,a.bearer().substring(7))),401);
  var notReady=member(o.org(),false);ingest(notReady,body,403);
  for(String field:new String[]{"organizationId","memberId","owner","header","tool","thinking","files","secrets"}){var spoof=body.deepCopy();spoof.put(field,"client-assertion");ingest(a,spoof,400);}
  assertEquals(0,db.queryForObject("select count(*) from member_desktop_body_sessions where member_id=?",Integer.class,a.id()));
  ingest(a,body,200);auth.logout(a.bearer());ingest(a,body,401);
  var disabled=member(o.org(),true);db.update("update members set active=false where id=?",disabled.id());ingest(disabled,snapshot(session(),"device-A",1,1),401);
  var expired=member(o.org(),true);db.update("update auth_sessions set expires_at=? where member_id=?",java.sql.Timestamp.from(java.time.Instant.now().minusSeconds(60)),expired.id());ingest(expired,snapshot(session(),"device-A",1,1),401);
 }
 @Test void orderedImmutablePrefixAndStableRequestReceiptSurviveLostAcknowledgments()throws Exception{
  var a=member(owner().org(),true);String id=session();var first=snapshot(id,"device-A",1,1);
  var accepted=ingest(a,first,200);assertTrue(accepted.path("sessionId").asText().startsWith("desktop-body:"));assertEquals(accepted,ingest(a,first,200));
  assertEquals(1,accepted.path("recordCount").asLong());assertEquals(2,accepted.path("nextRevision").asLong());
  var second=snapshot(id,"device-A",2,2);var next=ingest(a,second,200);assertEquals(2,next.path("recordCount").asLong());assertEquals(accepted,ingest(a,first,200));
  assertEquals(3,call(status(a,id,"device-A"),200).path("nextRevision").asLong());
  var reuse=first.deepCopy();((ObjectNode)reuse.path("entries").get(0)).put("text","Changed retry");ingest(a,reuse,409);
  var rewrite=snapshot(id,"device-A",3,3);((ObjectNode)rewrite.path("entries").get(0)).put("text","Changed accepted prefix");ingest(a,rewrite,409);
  ingest(a,snapshot(id,"device-A",4,3),409);ingest(a,snapshot(id,"device-B",3,3),409);call(status(a,id,"device-B"),409);
  ingest(a,snapshot(id,"device-A",3,2),409);
  assertEquals(2,db.queryForObject("select count(*) from member_desktop_body_records where member_id=? and client_session_id=?",Integer.class,a.id(),id));
  var response=mvc.perform(status(a,id,"device-A")).andReturn().getResponse();assertEquals("no-store",response.getHeader("Cache-Control"));
 }
 @Test void adminBodyReadIsOrganizationScopedAuditedAndDeletionIsMemberScoped()throws Exception{
  var o=owner();var a=member(o.org(),true);String id=session();
  var receipt=ingest(a,snapshot(id,"device-A",1,2),200);String server=receipt.path("sessionId").asText();
  var listed=call(get("/api/admin/sessions").header("Authorization",o.bearer()),200);assertTrue(listed.toString().contains(server));
  var detail=call(get("/api/admin/sessions/"+a.id()+"/"+server).header("Authorization",o.bearer()),200);assertEquals(2,detail.path("messages").size());assertEquals("assistant",detail.path("messages").get(1).path("role").asText());assertEquals("text",detail.path("messages").get(1).path("content").get(0).path("type").asText());
  assertTrue(db.queryForObject("select count(*) from audit_events where organization_id=? and action='session.content.viewed'",Integer.class,o.org())>0);
  call(get("/api/admin/sessions/"+a.id()+"/"+server).header("Authorization",a.bearer()),403);
  var other=member(o.org(),true);call(status(other,id,"device-A"),404);
  String foreignOrg=UUID.randomUUID().toString();db.update("insert into organizations values(?,?)",foreignOrg,"Foreign org");var foreign=member(foreignOrg,true);db.update("update members set role='OWNER' where id=?",foreign.id());
  call(get("/api/admin/sessions/"+a.id()+"/"+server).header("Authorization",foreign.bearer()),404);assertFalse(call(get("/api/admin/sessions").header("Authorization",foreign.bearer()),200).toString().contains(server));
  var deleted=call(delete("/api/member/visible-sessions").header("Authorization",a.bearer()).param("sessionId",id).param("deviceId","device-A"),200);assertTrue(deleted.path("deleted").asBoolean());assertEquals(0,deleted.path("recordCount").asInt());assertEquals(deleted,call(delete("/api/member/visible-sessions").header("Authorization",a.bearer()).param("sessionId",id).param("deviceId","device-A"),200));
  assertTrue(call(status(a,id,"device-A"),200).path("deleted").asBoolean());ingest(a,snapshot(id,"device-A",1,2),410);
  call(get("/api/admin/sessions/"+a.id()+"/"+server).header("Authorization",o.bearer()),404);
  assertEquals(0,db.queryForObject("select count(*) from member_desktop_body_records where member_id=?",Integer.class,a.id()));assertEquals(1,db.queryForObject("select count(*) from member_desktop_body_receipts where member_id=?",Integer.class,a.id()));
  // The same local identifier belongs independently to another authenticated member.
  assertNotEquals(server,ingest(other,snapshot(id,"device-A",1,1),200).path("sessionId").asText());
 }
 @Test void onlyBoundedVisibleTextIsAcceptedAndFailuresAreAtomic()throws Exception{
  var a=member(owner().org(),true);String id=session();var valid=snapshot(id,"device-A",1,1);
  for(String field:new String[]{"thinking","tool","attachments","apiKey"}){var bad=valid.deepCopy();((ObjectNode)bad.path("entries").get(0)).put(field,"not-visible");ingest(a,bad,400);}
  for(String role:new String[]{"tool","thinking","system"}){var bad=valid.deepCopy();((ObjectNode)bad.path("entries").get(0)).put("role",role);ingest(a,bad,400);}
  var bad=valid.deepCopy();((ObjectNode)bad.path("entries").get(0)).putObject("text").put("file","not-text");ingest(a,bad,400);
  bad=valid.deepCopy();bad.putArray("entries");ingest(a,bad,400);
  bad=valid.deepCopy();((ObjectNode)bad.path("entries").get(0)).put("text","sk-"+"x".repeat(32));ingest(a,bad,400);
  bad=valid.deepCopy();((ObjectNode)bad.path("entries").get(0)).put("text","x".repeat(65537));ingest(a,bad,413);
  var duplicate=json.writeValueAsString(valid).replace("\"version\":1","\"version\":1,\"version\":1");call(post("/api/member/visible-sessions").header("Authorization",a.bearer()).contentType(MediaType.APPLICATION_JSON).content(duplicate),400);
  call(post("/api/member/visible-sessions").header("Authorization",a.bearer()).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(valid)+"{}"),400);
  call(post("/api/member/visible-sessions").header("Authorization",a.bearer()).contentType(MediaType.APPLICATION_JSON).content(" ".repeat(524289)),413);
  assertEquals(0,db.queryForObject("select count(*) from member_desktop_body_sessions where member_id=?",Integer.class,a.id()));
 }
 @Test void concurrentRetriesCommitOnceAndAnotherDeviceCannotTakeOwnership()throws Exception{
  var a=member(owner().org(),true);String id=session();var input=snapshot(id,"device-A",1,1);var start=new CountDownLatch(1);var pool=Executors.newFixedThreadPool(2);
  try{
   Callable<DesktopVisibleSessions.Receipt> retry=()->{start.await();return service.ingest(a.bearer(),input);};var one=pool.submit(retry);var two=pool.submit(retry);start.countDown();assertEquals(one.get(10,TimeUnit.SECONDS),two.get(10,TimeUnit.SECONDS));
   assertEquals(1,db.queryForObject("select count(*) from member_desktop_body_records where member_id=? and client_session_id=?",Integer.class,a.id(),id));
   String race=session();var ready=new CountDownLatch(1);
   Callable<Integer> first=()->{ready.await();try{service.ingest(a.bearer(),snapshot(race,"device-A",1,1));return 200;}catch(ResponseStatusException e){return e.getStatusCode().value();}};
   Callable<Integer> second=()->{ready.await();try{service.ingest(a.bearer(),snapshot(race,"device-B",1,1));return 200;}catch(ResponseStatusException e){return e.getStatusCode().value();}};
   var left=pool.submit(first);var right=pool.submit(second);ready.countDown();assertEquals(Set.of(200,409),Set.of(left.get(10,TimeUnit.SECONDS),right.get(10,TimeUnit.SECONDS)));
   assertEquals(1,db.queryForObject("select count(*) from member_desktop_body_receipts where member_id=? and client_session_id=?",Integer.class,a.id(),race));
  }finally{pool.shutdownNow();}
 }
}
