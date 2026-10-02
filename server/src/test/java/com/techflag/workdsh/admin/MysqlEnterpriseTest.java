package com.techflag.workdsh.admin;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={
 "spring.datasource.url=${WORKDSH_TEST_MYSQL_URL}",
 "spring.datasource.username=root",
 "spring.datasource.password=${WORKDSH_TEST_MYSQL_PASSWORD}",
 "workdsh.bootstrap.admin-email=sqlite-test@example.test",
 "workdsh.bootstrap.admin-password=SqliteTestPassword123!",
 "workdsh.secrets.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
@ActiveProfiles("enterprise-mysql")
@org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable(named="WORKDSH_TEST_MYSQL_URL",matches=".+")
class MysqlEnterpriseTest {
 @Autowired com.fasterxml.jackson.databind.ObjectMapper json;
 @Autowired MemberSecrets secrets;@Autowired AuthService auth;@Autowired JdbcTemplate db;
 @Autowired CollaborationController collaboration;
 @Test void actualMysqlLoginPrivateAuthorizationAndCollaboration(){
  var admin=auth.login("sqlite-test@example.test","SqliteTestPassword123!");
  String org=admin.member().organizationId(),a=UUID.randomUUID().toString(),b=UUID.randomUUID().toString();
  String password="SqliteMemberPassword123!";
  for(String id:java.util.List.of(a,b)) db.update("insert into members(id,organization_id,email,display_name,role,password_hash,must_change_password,active,revision) values (?,?,?,?,?,?,false,true,1)",id,org,id+"@example.test",id,"MEMBER",auth.encode(password));
  String at="Bearer "+auth.login(a+"@example.test",password).token(),bt="Bearer "+auth.login(b+"@example.test",password).token();
  secrets.write(at,"case_sensitive_ref","fixture-lower");secrets.write(at,"CASE_SENSITIVE_REF","fixture-upper");
  assertEquals("fixture-lower",secrets.read(at,"case_sensitive_ref"));assertEquals("fixture-upper",secrets.read(at,"CASE_SENSITIVE_REF"));
  secrets.delete(at,"case_sensitive_ref");secrets.delete(at,"CASE_SENSITIVE_REF");
  String ref="MCP_AUTHORIZATION";
  secrets.write(at,ref,"fixture-a");secrets.write(bt,ref,"fixture-b");
  assertEquals("fixture-a",secrets.read(at,ref));assertEquals("fixture-b",secrets.read(bt,ref));
  assertNull(secrets.read("Bearer "+admin.token(),ref));
  String cipher=db.queryForObject("select encrypted_value from member_secrets where member_id=?",String.class,a);
  assertFalse(cipher.contains("fixture-a"));
  db.update("update member_secrets set encrypted_value=? where member_id=?",cipher,b);
  assertThrows(ResponseStatusException.class,()->secrets.read(bt,ref));
  secrets.write(bt,ref,"fixture-b");secrets.delete(at,ref);
  assertNull(secrets.read(at,ref));assertEquals("fixture-b",secrets.read(bt,ref));
  var sent=collaboration.send(at,new CollaborationController.NewHandoff(b,"共享分析结果",UUID.randomUUID().toString()));
  assertEquals(a,sent.senderId());assertEquals(b,sent.recipientId());
  var grant=json.valueToTree(java.util.Map.of("kind","grant","payload",java.util.Map.of("token","fixture-record-a")));
  String recordKey="mcp-plugin/account";
  assertEquals(0,secrets.readRecord(at,recordKey).revision());
  var saved=secrets.replaceRecord(at,recordKey,0,grant);
  assertEquals(1,saved.revision());assertEquals(grant,secrets.readRecord(at,recordKey).record());
  assertNull(secrets.readRecord(bt,recordKey).record());assertTrue(secrets.listRecords(bt).isEmpty());
  assertEquals("grant",secrets.listRecords(at).get(0).kind());
  assertEquals(409,assertThrows(ResponseStatusException.class,()->secrets.replaceRecord(at,recordKey,0,grant)).getStatusCode().value());
  var removed=secrets.replaceRecord(at,recordKey,1,null);
  assertEquals(2,removed.revision());assertNull(removed.record());assertTrue(secrets.listRecords(at).isEmpty());
  assertEquals(409,assertThrows(ResponseStatusException.class,()->secrets.replaceRecord(at,recordKey,0,grant)).getStatusCode().value());
  assertEquals(3,secrets.replaceRecord(at,recordKey,2,grant).revision());
  db.update("update members set active=false where id=?",b);
  assertEquals(401,assertThrows(ResponseStatusException.class,()->secrets.read(bt,ref)).getStatusCode().value());
 }
}
