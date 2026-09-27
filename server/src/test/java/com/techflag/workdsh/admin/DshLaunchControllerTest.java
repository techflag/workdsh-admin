package com.techflag.workdsh.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:dsh-launch;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "workdsh.bootstrap.admin-email=launch-admin@example.test",
        "workdsh.bootstrap.admin-password=SafeBootstrapPassword123!",
        "workdsh.dsh-gateway.origin=http://127.0.0.1:18892",
        "workdsh.dsh-gateway.secret=fixture-gateway-secret-longer-than-thirty-two-characters",
        "workdsh.web.origin=http://127.0.0.1:18891"
})
@AutoConfigureMockMvc
class DshLaunchControllerTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    @Test
    void oneUseTicketRequiresReadyMemberAndProvisionedInstance() throws Exception {
        String admin = login("launch-admin@example.test", "SafeBootstrapPassword123!");
        JsonNode created = payload(mvc.perform(post("/api/admin/members")
                .header("Authorization", "Bearer " + admin).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"launch-member@example.test\",\"displayName\":\"成员\",\"role\":\"MEMBER\"}"))
                .andReturn(), 201);
        String memberId = created.path("member").path("id").asText();
        String temporaryPassword = created.path("temporaryPassword").asText();
        String firstLogin = login("launch-member@example.test", temporaryPassword);
        status(mvc.perform(post("/api/dsh/launch").header("Authorization", "Bearer " + firstLogin)).andReturn(), 403);
        status(mvc.perform(post("/api/auth/change-password").header("Authorization", "Bearer " + firstLogin)
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(new AuthController.PasswordChange(
                        temporaryPassword, "MemberNewPassword123!")))).andReturn(), 204);
        String member = login("launch-member@example.test", "MemberNewPassword123!");
        status(mvc.perform(post("/api/dsh/launch").header("Authorization", "Bearer " + member)).andReturn(), 409);
        payload(mvc.perform(post("/api/admin/members/" + memberId + "/runtime-credential")
                .header("Authorization", "Bearer " + admin)).andReturn(), 200);
        JsonNode launch = payload(mvc.perform(post("/api/dsh/launch")
                .header("Authorization", "Bearer " + member)).andReturn(), 200);
        assertEquals("http://127.0.0.1:18892", launch.path("gatewayOrigin").asText());
        String ticket = launch.path("ticket").asText();
        assertTrue(ticket.length() >= 40);
        String body = json.writeValueAsString(new DshLaunchController.Ticket(ticket));
        status(mvc.perform(post("/api/internal/dsh/consume").contentType(MediaType.APPLICATION_JSON)
                .content(body)).andReturn(), 401);
        JsonNode consumed = payload(mvc.perform(post("/api/internal/dsh/consume")
                .header("Authorization", "Gateway fixture-gateway-secret-longer-than-thirty-two-characters")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn(), 200);
        assertEquals(memberId, consumed.path("memberId").asText());
        String grantId = consumed.path("grantId").asText();
        assertEquals(64, grantId.length());
        payload(mvc.perform(get("/api/internal/dsh/grants/" + grantId)
                .header("Authorization", "Gateway fixture-gateway-secret-longer-than-thirty-two-characters"))
                .andReturn(), 200);
        status(mvc.perform(post("/api/internal/dsh/consume")
                .header("Authorization", "Gateway fixture-gateway-secret-longer-than-thirty-two-characters")
                .contentType(MediaType.APPLICATION_JSON).content(body)).andReturn(), 401);
        status(mvc.perform(post("/api/auth/logout").header("Authorization", "Bearer " + member)).andReturn(), 204);
        status(mvc.perform(get("/api/internal/dsh/grants/" + grantId)
                .header("Authorization", "Gateway fixture-gateway-secret-longer-than-thirty-two-characters"))
                .andReturn(), 401);
    }

    @Test
    void browserSessionUsesHttpOnlyCookieAndRejectsCrossOriginMutation() throws Exception {
        MvcResult login = mvc.perform(post("/api/auth/browser-login")
                .header("Origin", "http://127.0.0.1:18891").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new AuthController.Credentials(
                        "launch-admin@example.test", "SafeBootstrapPassword123!")))).andReturn();
        JsonNode actor = payload(login, 200);
        assertFalse(actor.has("token"));
        String cookie = login.getResponse().getHeader("Set-Cookie");
        assertNotNull(cookie);
        assertTrue(cookie.contains("HttpOnly"));
        assertTrue(cookie.contains("SameSite=Strict"));
        String browserCookie = cookie.split(";", 2)[0];
        Cookie sessionCookie = new Cookie(BrowserSessionFilter.COOKIE_NAME, browserCookie.split("=", 2)[1]);
        assertEquals(actor.path("id").asText(), payload(mvc.perform(get("/api/auth/me")
                .cookie(sessionCookie)).andReturn(), 200).path("id").asText());
        status(mvc.perform(post("/api/auth/logout").cookie(sessionCookie)
                .header("Origin", "https://attacker.example")).andReturn(), 403);
        status(mvc.perform(post("/api/auth/logout").cookie(sessionCookie)
                .header("Origin", "http://127.0.0.1:18891")).andReturn(), 204);
        status(mvc.perform(get("/api/auth/me").cookie(sessionCookie)).andReturn(), 401);
        status(mvc.perform(post("/api/auth/browser-login")
                .header("Origin", "https://attacker.example").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new AuthController.Credentials(
                        "launch-admin@example.test", "SafeBootstrapPassword123!")))).andReturn(), 403);
    }

    private String login(String email, String password) throws Exception {
        return payload(mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(new AuthController.Credentials(email, password))))
                .andReturn(), 200).path("token").asText();
    }

    private JsonNode payload(MvcResult result, int expected) throws Exception {
        status(result, expected);
        return json.readTree(result.getResponse().getContentAsString());
    }

    private void status(MvcResult result, int expected) throws Exception {
        assertEquals(expected, result.getResponse().getStatus(), result.getResponse().getContentAsString());
    }
}
