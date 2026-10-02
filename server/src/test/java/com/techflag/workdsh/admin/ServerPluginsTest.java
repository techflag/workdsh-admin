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
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:server-plugins;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","workdsh.secrets.service-key=01234567890123456789012345678901","workdsh.bootstrap.admin-email=org-owner@example.test","workdsh.bootstrap.admin-password=OwnerPassword123!"})
@AutoConfigureMockMvc
class ServerPluginsTest {
 @Autowired MockMvc mvc;@Autowired ObjectMapper json;@Autowired JdbcTemplate db;@Autowired AuthService auth;
 JsonNode call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,int expected)throws Exception{var result=mvc.perform(request).andReturn();assertEquals(expected,result.getResponse().getStatus(),result.getResponse().getContentAsString());String body=result.getResponse().getContentAsString();return body.isBlank()?json.nullNode():json.readTree(body);}
 String login(String email)throws Exception{return call(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("email",email,"password","OwnerPassword123!"))),200).path("token").asText();}
 org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder body(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,String token,Object value)throws Exception{return request.header("Authorization","Bearer "+token).contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(value));}
 @Test void protectedReportAndOrganizationRead()throws Exception{
 String owner=login("org-owner@example.test"),org=auth.actor("Bearer "+owner).organizationId();
 call(get("/api/admin/server-plugins"),401);
 call(get("/api/admin/server-plugins").header("Authorization","Bearer "+owner),503);
 var report=Map.of("organizationId",org,"entries",List.of(Map.of("packageName","workdsh-plugin-skills","version","0.1.0-alpha.33","enabled",true,"phase","active")));
 call(put("/api/internal/server-plugins").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(report)),403);
 call(put("/api/internal/server-plugins").header("X-WorkDSH-Service-Key","01234567890123456789012345678901").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(report)),204);
 assertEquals(1,call(get("/api/admin/server-plugins").header("Authorization","Bearer "+owner),200).path("entries").size());
 String member=UUID.randomUUID().toString();db.update("insert into members values(?,?,?,?,?,?,false,true,1)",member,org,member+"@example.test","Member","MEMBER",auth.encode("OwnerPassword123!"));
 call(get("/api/admin/server-plugins").header("Authorization","Bearer "+login(member+"@example.test")),403);
 call(post("/api/admin/server-plugins").header("Authorization","Bearer "+owner),405);
 var foreign=Map.of("organizationId","other-org","entries",List.of());call(put("/api/internal/server-plugins").header("X-WorkDSH-Service-Key","01234567890123456789012345678901").contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(foreign)),204);
 assertEquals(1,call(get("/api/admin/server-plugins").header("Authorization","Bearer "+owner),200).path("entries").size());
 }
}
