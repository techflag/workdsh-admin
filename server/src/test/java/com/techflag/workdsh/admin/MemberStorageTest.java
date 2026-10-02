package com.techflag.workdsh.admin;
import java.util.UUID;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest(properties={"workdsh.secrets.service-key=fixture-service-key-at-least-32-characters","spring.datasource.url=jdbc:sqlite:file:member-storage-test?mode=memory&cache=shared","workdsh.bootstrap.admin-email=storage@example.test","workdsh.bootstrap.admin-password=StorageTestPassword123!"})
@ActiveProfiles("enterprise-sqlite")
class MemberStorageTest {
 @Autowired MemberSessions sessions;@Autowired MemberStorageController endpoint;@Autowired MemberStorage storage;@Autowired AuthService auth;@Autowired JdbcTemplate db;@Autowired com.fasterxml.jackson.databind.ObjectMapper json;
 @org.springframework.beans.factory.annotation.Value("${workdsh.bootstrap.admin-email}") String adminEmail;
 @org.springframework.beans.factory.annotation.Value("${workdsh.bootstrap.admin-password}") String adminPassword;
 @Test void privateDurableUnitsAndVersionChecks(){
  var admin=auth.login(adminEmail,adminPassword);String org=admin.member().organizationId();
  String a=UUID.randomUUID().toString(),b=UUID.randomUUID().toString(),password="StorageMemberPassword123!";
  for(String id:List.of(a,b))db.update("insert into members(id,organization_id,email,display_name,role,password_hash,must_change_password,active,revision) values (?,?,?,?,?,?,false,true,1)",id,org,id+"@example.test",id,"MEMBER",auth.encode(password));
  String at="Bearer "+auth.login(a+"@example.test",password).token(),bt="Bearer "+auth.login(b+"@example.test",password).token();
  var d=new MemberStorage.Descriptor("library",1,List.of("items"),true,"single");
  assertEquals(403,assertThrows(ResponseStatusException.class,()->endpoint.read(null,at,new MemberStorageController.Read(d))).getStatusCode().value());
  assertEquals("no-store",endpoint.read("fixture-service-key-at-least-32-characters",at,new MemberStorageController.Read(d)).getHeaders().getCacheControl());
  assertTrue(storage.read(at,d).path("tables").path("items").isEmpty());
  storage.write(at,d,"put","items","same",json.valueToTree("A"));storage.write(bt,d,"put","items","same",json.valueToTree("B"));
  storage.write(at,d,"put","items","another",json.valueToTree("A2"));
  assertEquals("A",storage.read(at,d).path("tables").path("items").path("same").asText());
  assertEquals("B",storage.read(bt,d).path("tables").path("items").path("same").asText());
  assertTrue(storage.read("Bearer "+admin.token(),d).path("tables").path("items").isEmpty());
  storage.write(at,d,"global",null,null,json.valueToTree("config"));
  assertTrue(storage.read(bt,d).path("global").isNull());
  storage.write(at,d,"delete","items","same",null);assertTrue(storage.read(at,d).path("tables").path("items").path("same").isMissingNode());
  assertEquals("A2",storage.read(at,d).path("tables").path("items").path("another").asText());
  var wrong=new MemberStorage.Descriptor("library",2,List.of("items"),true,"single");
  assertEquals(409,assertThrows(ResponseStatusException.class,()->storage.read(at,wrong)).getStatusCode().value());
  String session=UUID.randomUUID().toString();
  var header=json.valueToTree(java.util.Map.of("id",session,"version",1,"createdAt","2026-09-29T00:00:00.000Z","cwd","/workspace"));
  var opened=sessions.create(at,session,header,0);
  assertEquals(404,assertThrows(ResponseStatusException.class,()->sessions.stat(bt,session)).getStatusCode().value());
  assertEquals(409,assertThrows(ResponseStatusException.class,()->sessions.open(at,session,true)).getStatusCode().value());
  sessions.append(at,session,opened.writerId(),List.of(json.valueToTree(java.util.Map.of("seq",0,"type","fixture"))));
  assertEquals(1,sessions.open(at,session,false).snapshot().eventCount());
  assertEquals(409,assertThrows(ResponseStatusException.class,()->sessions.append(at,session,opened.writerId(),List.of(json.valueToTree(java.util.Map.of("seq",0,"type","fixture"))))).getStatusCode().value());
  sessions.close(at,session,opened.writerId());var restored=sessions.open(at,session,true);
  assertEquals(1,sessions.read(at,session,0,100).size());
  assertEquals(409,assertThrows(ResponseStatusException.class,()->sessions.flush(at,session,opened.writerId())).getStatusCode().value());
  sessions.flush(at,session,restored.writerId());sessions.close(at,session,restored.writerId());
  String anotherLogin="Bearer "+auth.login(a+"@example.test",password).token();
  var lease=sessions.open(anotherLogin,session,true);auth.logout(anotherLogin);
  var afterLogout=sessions.open(at,session,true);
  assertEquals(409,assertThrows(ResponseStatusException.class,()->sessions.flush(at,session,lease.writerId())).getStatusCode().value());
  sessions.close(at,session,lease.writerId());sessions.flush(at,session,afterLogout.writerId());
  db.update("update member_sessions set writer_expires=0 where organization_id=? and member_id=? and session_id=?",org,a,session);
  var afterExpiry=sessions.open(at,session,true);
  assertEquals(409,assertThrows(ResponseStatusException.class,()->sessions.flush(at,session,afterLogout.writerId())).getStatusCode().value());
  sessions.close(at,session,afterExpiry.writerId());
  db.update("update members set active=false where id=?",a);
  assertEquals(401,assertThrows(ResponseStatusException.class,()->storage.read(at,d)).getStatusCode().value());
  assertEquals("B",storage.read(bt,d).path("tables").path("items").path("same").asText());
 }
}
