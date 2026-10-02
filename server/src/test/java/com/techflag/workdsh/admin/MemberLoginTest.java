package com.techflag.workdsh.admin;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:member-login;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","workdsh.bootstrap.admin-email=admin@member-login.test","workdsh.bootstrap.admin-password=MemberLoginFixture123!"})
@AutoConfigureMockMvc
class MemberLoginTest {
 @Autowired MockMvc mvc;
 @Test void onlyActualMemberCredentialsAuthenticate() throws Exception {
  for(String alias:new String[]{"a","b","admin"})mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\""+alias+"\",\"password\":\"123456\"}")).andExpect(status().isUnauthorized());
  mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"admin@member-login.test\",\"password\":\"wrong-password\"}")).andExpect(status().isUnauthorized());
  mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{\"email\":\"admin@member-login.test\",\"password\":\"MemberLoginFixture123!\"}")).andExpect(status().isOk()).andExpect(jsonPath("$.token").isNotEmpty()).andExpect(jsonPath("$.member.mustChangePassword").value(false));
 }
}
