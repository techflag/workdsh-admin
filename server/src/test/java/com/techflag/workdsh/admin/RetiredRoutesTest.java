package com.techflag.workdsh.admin;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import com.fasterxml.jackson.databind.ObjectMapper;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:retired-routes;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","workdsh.bootstrap.admin-email=retired@example.test","workdsh.bootstrap.admin-password=RetiredRoutesPassword123!"})
@AutoConfigureMockMvc
class RetiredRoutesTest {
 @Autowired MockMvc mvc; @Autowired AuthService auth; @Autowired JdbcTemplate db; @Autowired ObjectMapper json;
 @Test void retiredRoutesAndTablesAreAbsentWhileMemberCollaborationWorks() throws Exception {
  String bearer="Bearer "+auth.login("retired@example.test","RetiredRoutesPassword123!").token();
  for(String path:new String[]{"/api/admin/server-plugins","/api/orders","/api/orders/nonexistent","/api/orders/nonexistent/sources","/api/reviews/inbox","/api/admin/orders","/api/admin/model-config","/api/internal/organization-model","/api/internal/runtime/identity"})mvc.perform(get(path).header("Authorization",bearer)).andExpect(status().isNotFound());
  for(String path:new String[]{"/api/internal/member-storage/read","/api/internal/member-storage/write","/api/internal/member-sessions","/api/orders","/api/orders/nonexistent/review","/api/orders/nonexistent/sources/preview","/api/dsh/launch","/api/auth/runtime-login","/api/admin/members/nonexistent/runtime-credential"})mvc.perform(post(path).header("Authorization",bearer).contentType("application/json").content("{}")).andExpect(status().isNotFound());
  for(String table:new String[]{"member_storage_units","member_sessions","member_session_events","orders","order_lines","order_sources","order_access","review_events","runtime_credentials","dsh_launch_tickets","organization_models"})assertEquals(0,db.queryForObject("select count(*) from information_schema.tables where table_schema='public' and table_name=?",Integer.class,table));
  mvc.perform(get("/api/auth/me").header("Authorization",bearer)).andExpect(status().isOk());mvc.perform(get("/api/admin/members").header("Authorization",bearer)).andExpect(status().isOk());
  var created=mvc.perform(post("/api/admin/members").header("Authorization",bearer).contentType("application/json").content(json.writeValueAsString(Map.of("email","active-member@example.test","displayName","Member","role","MEMBER")))).andExpect(status().isCreated()).andReturn();
  var member=json.readTree(created.getResponse().getContentAsString());String password=member.path("temporaryPassword").asText();var login=auth.login("active-member@example.test",password);auth.changePassword(login.member(),password,"ActiveMemberPassword123!");String memberBearer="Bearer "+auth.login("active-member@example.test","ActiveMemberPassword123!").token();
  mvc.perform(get("/api/collaboration/inbox").header("Authorization",memberBearer)).andExpect(status().isOk());mvc.perform(get("/api/admin/members").header("Authorization",memberBearer)).andExpect(status().isForbidden());mvc.perform(get("/api/collaboration/inbox").header("Authorization","Runtime retired-credential")).andExpect(status().isUnauthorized());
 }
}
