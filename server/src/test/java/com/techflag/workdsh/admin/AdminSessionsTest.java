package com.techflag.workdsh.admin;
import com.fasterxml.jackson.databind.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import java.util.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:admin-sessions;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","workdsh.bootstrap.admin-email=org-owner@example.test","workdsh.bootstrap.admin-password=OwnerPassword123!"})
@AutoConfigureMockMvc
class AdminSessionsTest {
 @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate db;@Autowired AuthService auth;
 JsonNode call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,int expected)throws Exception{var result=mvc.perform(request).andReturn();assertEquals(expected,result.getResponse().getStatus(),result.getResponse().getContentAsString());String body=result.getResponse().getContentAsString();return body.isBlank()?json.nullNode():json.readTree(body);}
 String login(String email)throws Exception{return call(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("email",email,"password","OwnerPassword123!"))),200).path("token").asText();}
 org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder body(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,String token,Object value)throws Exception{return request.header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(value));}
 @Test @org.springframework.transaction.annotation.Transactional void adminReadsOnlyOrganizationContentAndAudits()throws Exception{
 String owner=login("org-owner@example.test"),org=auth.actor("Bearer "+owner).organizationId();
 String member=UUID.randomUUID().toString();db.update("insert into members values(?,?,?,?,?,?,false,true,1)",member,org,member+"@example.test","Member","MEMBER",auth.encode("OwnerPassword123!"));
 String mt=login(member+"@example.test");String id="session-test";
 db.update("insert into member_sessions(organization_id,member_id,session_id,header_json,inherited_count,event_count,writer_expires) values(?,?,?,?,0,2,0)",org,member,id,"{\"id\":\"session-test\",\"createdAt\":123}");
 for(int i=0;i<2;i++){var event=Map.of("seq",i,"time",124+i,"type",i==0?"user/message":"assistant/message","data",i==0?Map.of("content",List.of(Map.of("type","text","text","Private question"))):Map.of("message",Map.of("content",List.of(Map.of("type","text","text","Private answer")))));db.update("insert into member_session_events values(?,?,?,?,?)",org,member,id,i,json.writeValueAsString(event));}
 call(get("/api/admin/sessions").header("Authorization","Bearer "+mt),403);
 call(get("/api/admin/sessions/"+member+"/"+id).header("Authorization","Bearer "+mt),403);
 assertTrue(call(get("/api/admin/sessions").header("Authorization","Bearer "+owner),200).toString().contains(member));
 var detail=call(get("/api/admin/sessions/"+member+"/"+id).header("Authorization","Bearer "+owner),200);assertEquals(2,detail.path("messages").size());assertTrue(detail.toString().contains("Private answer"));assertFalse(detail.path("hasMore").asBoolean());
 assertEquals(1,db.queryForObject("select count(*) from audit_events where organization_id=? and action='session.content.viewed'",Integer.class,org));
 String foreignOrg=UUID.randomUUID().toString(),foreignMember=UUID.randomUUID().toString();db.update("insert into organizations values(?,?)",foreignOrg,"Other");db.update("insert into members values(?,?,?,?,?,?,false,true,1)",foreignMember,foreignOrg,foreignMember+"@example.test","Other","MEMBER",auth.encode("OwnerPassword123!"));db.update("insert into member_sessions(organization_id,member_id,session_id,header_json,inherited_count,event_count,writer_expires) values(?,?,?,?,0,0,0)",foreignOrg,foreignMember,id,"{}");
 assertFalse(call(get("/api/admin/sessions").header("Authorization","Bearer "+owner),200).toString().contains(foreignMember));
 call(get("/api/admin/sessions/"+foreignMember+"/"+id).header("Authorization","Bearer "+owner),404);
 call(get("/api/admin/sessions/foreign/"+id).header("Authorization","Bearer "+owner),404);
 call(get("/api/admin/sessions?limit=1000").header("Authorization","Bearer "+owner),400);
 }
}
