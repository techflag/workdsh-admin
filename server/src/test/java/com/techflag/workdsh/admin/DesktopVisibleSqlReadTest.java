package com.techflag.workdsh.admin;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
@SpringBootTest(properties={"spring.datasource.url=jdbc:h2:mem:desktop-locking;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","workdsh.bootstrap.admin-email=desktop-lock@example.test","workdsh.bootstrap.admin-password=DesktopLockingFixture123!"})
class DesktopVisibleSqlReadTest {
 @Autowired DesktopVisibleSessions sessions;@Autowired AuthService auth;@Autowired ObjectMapper json;
 @MockitoSpyBean JdbcTemplate db;
 private List<String> bodyReads(){return mockingDetails(db).getInvocations().stream().filter(i->i.getMethod().getName().equals("query")&&i.getArguments()[0] instanceof String).map(i->(String)i.getArguments()[0]).filter(s->s.contains("from member_desktop_body_")).distinct().toList();}
 @Test void executedMutationQueriesUseCurrentReadsWhileStatusRemainsAnOrdinaryRead()throws Exception{
  String bearer="Bearer "+auth.login("desktop-lock@example.test","DesktopLockingFixture123!").token();
  var first=json.valueToTree(Map.of("version",1,"deviceId","locking-device","sessionId","locking-session","revision",1,"requestId","locking-first","entries",List.of(Map.of("seq",0,"recordId","locking-record","role","user","text","First visible body"))));
  var second=first.deepCopy();((com.fasterxml.jackson.databind.node.ObjectNode)second).put("revision",2).put("requestId","locking-second");((com.fasterxml.jackson.databind.node.ArrayNode)second.path("entries")).add(json.valueToTree(Map.of("seq",1,"recordId","locking-answer","role","assistant","text","Second visible body")));
  clearInvocations(db);sessions.ingest(bearer,first);sessions.ingest(bearer,second);sessions.ingest(bearer,first);sessions.delete(bearer,"locking-device","locking-session");
  var reads=bodyReads();assertEquals(3,Set.copyOf(reads.stream().map(s->s.split("from ")[1].split(" ")[0]).toList()).size());
  assertFalse(reads.isEmpty());for(String sql:reads){assertTrue(sql.endsWith(" for update"),"Mutation must read current rows after its member lock");assertEquals(sql,new DatabaseDialect("jdbc:mysql://fixture").lock(sql.substring(0,sql.length()-11)));}
  clearInvocations(db);assertTrue(sessions.status(bearer,"locking-device","locking-session").deleted());assertEquals(1,bodyReads().size());assertFalse(bodyReads().get(0).endsWith(" for update"));
 }
}
