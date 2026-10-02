package com.techflag.workdsh.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.servlet.context.ServletWebServerApplicationContext;
import static org.junit.jupiter.api.Assertions.*;

class PersistentOrganizationRestartTest {
    @TempDir Path directory;
    final ObjectMapper json = new ObjectMapper();
    final HttpClient http = HttpClient.newHttpClient();
    final String password = "RestartAcceptancePassword123!";
    String origin;

    ServletWebServerApplicationContext start() {
        var context = (ServletWebServerApplicationContext)new SpringApplicationBuilder(AdminApplication.class).run(
            "--server.address=127.0.0.1", "--server.port=0",
            "--spring.datasource.url=jdbc:h2:file:"+directory.resolve("organization")+";MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE",
            "--workdsh.bootstrap.admin-email=restart-admin@example.test",
            "--workdsh.bootstrap.admin-password="+password,
            "--workdsh.organization-name=Restart acceptance");
        origin="http://127.0.0.1:"+context.getWebServer().getPort();
        return context;
    }
    JsonNode call(String method,String path,String token,Object body,int expected) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(origin+path));
        if(token!=null)builder.header("Authorization","Bearer "+token);
        if(body!=null)builder.header("Content-Type","application/json");
        builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
        var response=http.send(builder.build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(expected,response.statusCode(),path+": "+response.body());
        return response.body().isBlank()?json.createObjectNode():json.readTree(response.body());
    }
    String login(String email) throws Exception {return call("POST","/api/auth/login",null,Map.of("email",email,"password",password),200).path("token").asText();}
    String member(String admin,String email) throws Exception {
        var created=call("POST","/api/admin/members",admin,Map.of("email",email,"displayName",email,"role","MEMBER"),201);
        String initial=created.path("temporaryPassword").asText();
        String token=call("POST","/api/auth/login",null,Map.of("email",email,"password",initial),200).path("token").asText();
        call("POST","/api/auth/change-password",token,Map.of("currentPassword",initial,"newPassword",password),204);
        return created.path("member").path("id").asText();
    }
    @Test void organizationAndSharedFilesSurviveClosedServerAndDatabase() throws Exception {
        String aId,bId,cId,materialId,departmentId,organizationId;
        byte[] original="SKU,amount\nA,960\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        String hash=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(original));
        try(var first=start()) {
            String admin=login("restart-admin@example.test");
            aId=member(admin,"restart-a@example.test");bId=member(admin,"restart-b@example.test");cId=member(admin,"restart-c@example.test");
            organizationId=first.getBean(AuthService.class).actor("Bearer "+admin).organizationId();
            departmentId=call("POST","/api/admin/organization/departments",admin,Map.of("name","分析部门"),200).path("id").asText();
            String a=login("restart-a@example.test");
            materialId=call("POST","/api/collaboration/materials",a,Map.of("recipientId",bId,"summary","共享分析资料","requestKey","restart-material","context","结果 960","files",List.of(Map.of("name","analysis.csv","sha256",hash,"data",Base64.getEncoder().encodeToString(original)))),200).path("id").asText();
            call("PUT","/api/admin/organization/policy",admin,Map.of("directoryScope","DEPARTMENT","sharingEnabled",false,"revision",0),200);
            call("PATCH","/api/admin/members/"+cId,admin,Map.of("active",false,"expectedRevision",2),200);
        }
        try(var second=start()) {
            String admin=login("restart-admin@example.test"),a=login("restart-a@example.test"),b=login("restart-b@example.test");
            assertEquals(organizationId,second.getBean(AuthService.class).actor("Bearer "+admin).organizationId());
            var organization=call("GET","/api/admin/organization",admin,null,200);
            assertTrue(organization.path("departments").toString().contains(departmentId));
            assertEquals("DEPARTMENT",organization.path("policy").path("directoryScope").asText());
            assertFalse(organization.path("policy").path("sharingEnabled").asBoolean());
            assertEquals(1,organization.path("policy").path("revision").asInt());
            var material=call("GET","/api/collaboration/materials/"+materialId,b,null,200);
            assertEquals("结果 960",material.path("context").asText());
            assertArrayEquals(original,Base64.getDecoder().decode(material.path("files").get(0).path("data").asText()));
            assertEquals(hash,material.path("files").get(0).path("sha256").asText());
            call("GET","/api/collaboration/materials/"+materialId,admin,null,404);
            call("POST","/api/collaboration/materials",a,Map.of("recipientId",bId,"summary","策略已关闭","requestKey","after-restart","context","新的共享内容","files",List.of()),403);
            call("POST","/api/auth/login",null,Map.of("email","restart-c@example.test","password",password),401);
            assertEquals(4,call("GET","/api/admin/members",admin,null,200).size(),"Bootstrap must not duplicate members on restart");
        }
    }
}
