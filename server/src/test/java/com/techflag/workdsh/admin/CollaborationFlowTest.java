package com.techflag.workdsh.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:collaboration-flow;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1",
        "workdsh.bootstrap.admin-email=admin-collaboration@example.test",
        "workdsh.bootstrap.admin-password=SafeBootstrapPassword123!"
})
@AutoConfigureMockMvc
class CollaborationFlowTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate db;
    @Autowired AuthService auth;

    @Test
    void handoffMovesBetweenTwoMembersWithoutOrderAndRespectsIdentity() throws Exception {
        String admin = login("admin-collaboration@example.test", "SafeBootstrapPassword123!");
        JsonNode createdA = call(post("/api/admin/members").header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", "collab-a@example.test", "displayName", "成员甲", "role", "MEMBER"))), 201);
        JsonNode createdB = call(post("/api/admin/members").header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", "collab-b@example.test", "displayName", "成员乙", "role", "MEMBER"))), 201);
        String aId = createdA.path("member").path("id").asText();
        String bId = createdB.path("member").path("id").asText();
        String aFirst = login("collab-a@example.test", createdA.path("temporaryPassword").asText());
        String bFirst = login("collab-b@example.test", createdB.path("temporaryPassword").asText());
        call(get("/api/collaboration/inbox").header("Authorization", bearer(bFirst)), 403);
        changePassword(aFirst, createdA.path("temporaryPassword").asText(), "MemberANewPassword123!");
        changePassword(bFirst, createdB.path("temporaryPassword").asText(), "MemberBNewPassword123!");
        String a = login("collab-a@example.test", "MemberANewPassword123!");
        String b = login("collab-b@example.test", "MemberBNewPassword123!");
        String aRuntime = call(post("/api/admin/members/" + aId + "/runtime-credential")
                .header("Authorization", bearer(admin)), 200).path("runtimeToken").asText();
        String bRuntime = call(post("/api/admin/members/" + bId + "/runtime-credential")
                .header("Authorization", bearer(admin)), 200).path("runtimeToken").asText();

        JsonNode colleagues = call(get("/api/collaboration/colleagues").header("Authorization", "Runtime " + aRuntime), 200);
        assertTrue(colleagues.findValuesAsText("id").contains(bId));
        assertFalse(colleagues.findValuesAsText("id").contains(aId));
        String body = json.writeValueAsString(Map.of("recipientId", bId, "summary", "请复核分析结果并反馈", "requestKey", "native-call-1"));
        JsonNode handoff = call(post("/api/collaboration/handoffs").header("Authorization", "Runtime " + aRuntime)
                .contentType(MediaType.APPLICATION_JSON).content(body), 201);
        String id = handoff.path("id").asText();
        assertEquals(aId, handoff.path("senderId").asText());
        assertEquals("OPEN", handoff.path("status").asText());
        assertEquals(id, call(post("/api/collaboration/handoffs").header("Authorization", "Runtime " + aRuntime)
                .contentType(MediaType.APPLICATION_JSON).content(body), 201).path("id").asText());
        assertEquals(1, call(get("/api/collaboration/inbox").header("Authorization", "Runtime " + bRuntime), 200).size());
        assertEquals(0, call(get("/api/collaboration/inbox").header("Authorization", bearer(a)), 200).size());
        assertEquals(1, call(get("/api/collaboration/sent").header("Authorization", bearer(a)), 200).size());
        call(post("/api/collaboration/handoffs").header("Authorization", "Runtime " + aRuntime)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("recipientId", aId, "summary", "self", "requestKey", "self"))), 400);
        String otherOrg = java.util.UUID.randomUUID().toString();
        String outsiderId = java.util.UUID.randomUUID().toString();
        db.update("insert into organizations(id,name) values (?,?)", otherOrg, "其他组织");
        db.update("insert into members(id,organization_id,email,display_name,role,password_hash,must_change_password,active,revision) values (?,?,?,?,?,?,false,true,1)",
                outsiderId, otherOrg, "outsider-collab@example.test", "外部成员", "MEMBER", auth.encode("OutsiderPassword123!"));
        call(post("/api/collaboration/handoffs").header("Authorization", "Runtime " + aRuntime)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("recipientId", outsiderId, "summary", "cross org", "requestKey", "cross-org"))), 400);
        call(post("/api/collaboration/handoffs").header("Authorization", "Runtime " + aRuntime)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("recipientId", bId, "summary", "different", "requestKey", "native-call-1"))), 409);
        String complete = json.writeValueAsString(Map.of("resolution", "已核对，两处需修改"));
        call(post("/api/collaboration/handoffs/" + id + "/complete").header("Authorization", bearer(a))
                .contentType(MediaType.APPLICATION_JSON).content(complete), 409);
        JsonNode done = call(post("/api/collaboration/handoffs/" + id + "/complete").header("Authorization", "Runtime " + bRuntime)
                .contentType(MediaType.APPLICATION_JSON).content(complete), 200);
        assertEquals("DONE", done.path("status").asText());
        assertEquals("已核对，两处需修改", call(get("/api/collaboration/sent").header("Authorization", bearer(a)), 200)
                .get(0).path("resolution").asText());
        call(post("/api/collaboration/handoffs/" + id + "/complete").header("Authorization", bearer(b))
                .contentType(MediaType.APPLICATION_JSON).content(complete), 409);
        assertEquals(1, db.queryForObject("select count(*) from collaboration_handoffs where id=?", Integer.class, id));
        call(patch("/api/admin/members/" + bId).header("Authorization", bearer(admin))
                .contentType(MediaType.APPLICATION_JSON).content("{\"active\":false,\"expectedRevision\":2}"), 200);
        call(get("/api/collaboration/inbox").header("Authorization", "Runtime " + bRuntime), 401);
        call(post("/api/collaboration/handoffs").header("Authorization", "Runtime " + aRuntime)
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("recipientId", bId, "summary", "new", "requestKey", "native-call-2"))), 400);
    }

    private void changePassword(String token, String oldPassword, String newPassword) throws Exception {
        call(post("/api/auth/change-password").header("Authorization", bearer(token)).contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("currentPassword", oldPassword, "newPassword", newPassword))), 204);
    }
    private String login(String email, String password) throws Exception {
        return call(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("email", email, "password", password))), 200).path("token").asText();
    }
    private String bearer(String token) { return "Bearer " + token; }
    private JsonNode call(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request, int expected) throws Exception {
        var response = mvc.perform(request).andReturn().getResponse();
        assertEquals(expected, response.getStatus(), response.getContentAsString());
        return response.getContentAsString().isBlank() ? json.createObjectNode() : json.readTree(response.getContentAsString());
    }
}
