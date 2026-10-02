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
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:organization-permissions;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","workdsh.bootstrap.admin-email=org-owner@example.test","workdsh.bootstrap.admin-password=OwnerPassword123!"})
@AutoConfigureMockMvc
class OrganizationPermissionsTest {
 @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate db;@Autowired AuthService auth;
 JsonNode call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,int expected)throws Exception{var result=mvc.perform(request).andReturn();assertEquals(expected,result.getResponse().getStatus(),result.getResponse().getContentAsString());String body=result.getResponse().getContentAsString();return body.isBlank()?json.nullNode():json.readTree(body);}
 String login(String email)throws Exception{return call(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("email",email,"password","OwnerPassword123!"))),200).path("token").asText();}
 org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder body(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,String token,Object value)throws Exception{return request.header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(value));}
 @Test @org.springframework.transaction.annotation.Transactional void organizationScopeAndDshDirectoryAreEnforced()throws Exception{
 String owner=login("org-owner@example.test"),org=auth.actor("Bearer "+owner).organizationId();String a=UUID.randomUUID().toString(),b=UUID.randomUUID().toString(),ad=UUID.randomUUID().toString();for(String id:List.of(a,b,ad))db.update("insert into members values(?,?,?,?,?,?,false,true,1)",id,org,id+"@example.test",id,id.equals(ad)?"ADMIN":"MEMBER",auth.encode("OwnerPassword123!"));String at=login(a+"@example.test"),adt=login(ad+"@example.test");
 call(get("/api/admin/organization").header("Authorization","Bearer "+at),403);
 String d1=call(body(post("/api/admin/organization/departments"),owner,Map.of("name","销售")),200).path("id").asText();String d2=call(body(post("/api/admin/organization/departments"),owner,Map.of("name","财务")),200).path("id").asText();
 call(body(put("/api/admin/organization/profiles/"+a),owner,Map.of("displayName","小张","departmentId",d1,"username","zhangsan","expectedRevision",1)),200);
 call(body(put("/api/admin/organization/profiles/"+b),owner,Map.of("displayName","小李","departmentId",d2,"username","lisi","expectedRevision",1)),200);
 assertEquals(a,auth.actor("Bearer "+login("zhangsan")).id());
 call(body(put("/api/admin/organization/profiles/"+b),owner,Map.of("displayName","覆盖","departmentId",d1,"expectedRevision",1)),409);
 call(delete("/api/admin/organization/departments/"+d1).header("Authorization","Bearer "+owner),400);
 call(body(patch("/api/admin/organization/departments/"+d1),owner,Map.of("name","销售","parentId",d1,"expectedRevision",1)),400);
 call(body(put("/api/admin/organization/policy"),adt,Map.of("directoryScope","DEPARTMENT","sharingEnabled",true,"revision",0)),403);
 call(body(put("/api/admin/organization/policy"),owner,Map.of("directoryScope","DEPARTMENT","sharingEnabled",true,"revision",0)),200);
 assertFalse(call(get("/api/members").header("Authorization","Bearer "+at),200).toString().contains(b));
 assertFalse(call(get("/api/collaboration/colleagues").header("Authorization","Bearer "+at),200).toString().contains(b));
 call(body(post("/api/collaboration/handoffs"),at,Map.of("recipientId",b,"summary","分享","requestKey",UUID.randomUUID().toString())),403);
 call(body(put("/api/admin/organization/profiles/"+b),owner,Map.of("displayName","小李","departmentId",d1,"expectedRevision",2)),200);
 assertTrue(call(get("/api/collaboration/colleagues").header("Authorization","Bearer "+at),200).toString().contains(b));
 call(body(put("/api/admin/organization/policy"),owner,Map.of("directoryScope","ORGANIZATION","sharingEnabled",false,"revision",1)),200);
 call(body(post("/api/collaboration/handoffs"),at,Map.of("recipientId",b,"summary","分享","requestKey",UUID.randomUUID().toString())),403);
 call(body(patch("/api/admin/members/"+a),adt,Map.of("role","ADMIN","expectedRevision",2)),403);
 call(body(post("/api/admin/members"),adt,Map.of("email","elevated@example.test","displayName","越权","role","ADMIN")),403);
 call(body(put("/api/admin/organization/profiles/"+ad),adt,Map.of("displayName","管理员","expectedRevision",1)),403);
 String foreign=UUID.randomUUID().toString();db.update("insert into organizations values(?,?)",foreign,"其他组织");String foreignDept=UUID.randomUUID().toString();db.update("insert into departments values(?,?,?,?,1)",foreignDept,foreign,null,"部门");call(body(put("/api/admin/organization/profiles/"+a),owner,Map.of("displayName","小张","departmentId",foreignDept,"expectedRevision",2)),400);
 }
}
