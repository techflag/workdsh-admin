package com.techflag.workdsh.admin;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"spring.profiles.active=enterprise-sqlite","spring.datasource.url=jdbc:sqlite:file:desktop-visible-sqlite?mode=memory&cache=shared","workdsh.bootstrap.admin-email=desktop-sqlite@example.test","workdsh.bootstrap.admin-password=DesktopSqliteFixture123!"})
class DesktopVisibleSqliteTest {
 @Autowired DesktopVisibleSessions sessions;@Autowired AuthService auth;@Autowired ObjectMapper json;@Autowired JdbcTemplate db;@Autowired AdminSessionsController admin;
 @Test void sqliteCurrentSchemaStoresVisibleTextAndExistingAdminReadsIt()throws Exception{
  String bearer="Bearer "+auth.login("desktop-sqlite@example.test","DesktopSqliteFixture123!").token();
  var input=json.valueToTree(Map.of("version",1,"deviceId","sqlite-device","sessionId","sqlite-session","revision",1,"requestId","sqlite-request","entries",List.of(Map.of("seq",0,"recordId","sqlite-record","role","user","text","SQLite visible body"))));
  var receipt=sessions.ingest(bearer,input);assertEquals(receipt,sessions.ingest(bearer,input));assertEquals(1,admin.list(bearer,0,50).size());assertEquals(1,admin.detail(bearer,auth.actor(bearer).id(),receipt.sessionId(),0).messages().size());
  assertTrue(sessions.delete(bearer,"sqlite-device","sqlite-session").deleted());assertTrue(admin.list(bearer,0,50).isEmpty());
 }
}
