package com.techflag.workdsh.admin;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Server-only external service secrets. No administrator read override. */
@Service
public class MemberSecrets {
 private final com.fasterxml.jackson.databind.ObjectMapper json;
 private final JdbcTemplate db;
 private final AuthService auth;
 private final DatabaseDialect dialect;
 private final String configuredKey;
 private final SecureRandom random = new SecureRandom();
 public MemberSecrets(JdbcTemplate db, AuthService auth, DatabaseDialect dialect, com.fasterxml.jackson.databind.ObjectMapper json,
   @Value("${workdsh.secrets.encryption-key:}") String key) {
  this.json=json;this.db=db;this.auth=auth;this.dialect=dialect;this.configuredKey=key;
 }
 private AuthService.Actor actor(String bearer,String ref) {
  var actor=auth.actor(bearer);auth.requireReady(actor);
  if(ref==null||!ref.matches("[A-Za-z_][A-Za-z0-9_]{0,127}"))
   throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid authorization reference");
  return actor;
 }
 public String read(String bearer,String ref) {
  var a=actor(bearer,ref);
  var rows=db.queryForList("select encrypted_value from member_secrets where organization_id=? and member_id=? and credential_ref=?",String.class,a.organizationId(),a.id(),ref);
  return rows.isEmpty()?null:crypt(false,a,ref,rows.get(0));
 }
 @Transactional
 public void write(String bearer,String ref,String value) {
  var a=actor(bearer,ref);
  if(value==null||value.isEmpty()||value.length()>8192)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid authorization value");
  db.queryForObject(dialect.lock("select id from members where organization_id=? and id=?"),String.class,a.organizationId(),a.id());
  var encrypted=crypt(true,a,ref,value);
  db.update(dialect.upsertSecret(),a.organizationId(),a.id(),ref,encrypted);
 }
 public void delete(String bearer,String ref) {
  var a=actor(bearer,ref);
  db.update("delete from member_secrets where organization_id=? and member_id=? and credential_ref=?",a.organizationId(),a.id(),ref);
 }
 public record StoredRecord(long revision,com.fasterxml.jackson.databind.JsonNode record){}
 public record RecordEntry(String key,String kind){}
 private AuthService.Actor recordActor(String bearer,String key){
  var a=auth.actor(bearer);auth.requireReady(a);
  if(key==null||key.length()>255||!key.matches("[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid authorization record key");
  return a;
 }
 public StoredRecord readRecord(String bearer,String key){
  var a=recordActor(bearer,key);return stored(a,key);
 }
 private StoredRecord stored(AuthService.Actor a,String key){
  var rows=db.query("select revision,encrypted_value from member_secret_records where organization_id=? and member_id=? and record_key=?",(rs,n)->{
   String encrypted=rs.getString(2);
   try{return new StoredRecord(rs.getLong(1),encrypted==null?null:json.readTree(crypt(false,a,"record:"+key,encrypted)));}
   catch(java.io.IOException e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Invalid authorization record");}
  },a.organizationId(),a.id(),key);
  return rows.isEmpty()?new StoredRecord(0,null):rows.get(0);
 }
 public java.util.List<RecordEntry> listRecords(String bearer){
  var a=auth.actor(bearer);auth.requireReady(a);
  return db.query("select record_key,kind from member_secret_records where organization_id=? and member_id=? and kind is not null order by record_key",(rs,n)->new RecordEntry(rs.getString(1),rs.getString(2)),a.organizationId(),a.id());
 }
 @Transactional
 public StoredRecord replaceRecord(String bearer,String key,long expected,com.fasterxml.jackson.databind.JsonNode record){
  var a=recordActor(bearer,key);
  db.queryForObject(dialect.lock("select id from members where organization_id=? and id=?"),String.class,a.organizationId(),a.id());
  var current=stored(a,key);
  if(expected<0||current.revision()!=expected)throw new ResponseStatusException(HttpStatus.CONFLICT,"Authorization record changed");
  String kind=null,encrypted=null;
  if(record!=null&&!record.isNull()){
   if(!record.isObject()||!java.util.Set.of("api-key","grant").contains(record.path("kind").asText())||record.toString().length()>65536)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid authorization record");
   kind=record.path("kind").asText();
   if(kind.equals("grant")&&!record.has("payload"))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid authorization grant");
   encrypted=crypt(true,a,"record:"+key,record.toString());
  }
  long next=Math.addExact(current.revision(),1);
  if(current.revision()==0){
   try{db.update("insert into member_secret_records(organization_id,member_id,record_key,revision,kind,encrypted_value) values (?,?,?,?,?,?)",a.organizationId(),a.id(),key,next,kind,encrypted);}
   catch(org.springframework.dao.DuplicateKeyException e){throw new ResponseStatusException(HttpStatus.CONFLICT,"Authorization record changed");}
  }else if(db.update("update member_secret_records set revision=?,kind=?,encrypted_value=? where organization_id=? and member_id=? and record_key=? and revision=?",next,kind,encrypted,a.organizationId(),a.id(),key,expected)!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"Authorization record changed");
  return new StoredRecord(next,kind==null?null:record);
 }
 private String crypt(boolean encrypt,AuthService.Actor a,String ref,String value) {
  return crypt(encrypt,a.organizationId()+"\n"+a.id()+"\n"+ref,value);
 }
 String cryptTransport(boolean encrypt,String value) {
  return crypt(encrypt,"server-transport\nclient-connection/browser-session",value);
 }
 String cryptOrganizationModel(boolean encrypt,String organizationId,String value) {
  return crypt(encrypt,"organization-model\n"+organizationId,value);
 }
 private String crypt(boolean encrypt,String aad,String value) {
  byte[] key;
  try {key=Base64.getDecoder().decode(configuredKey);if(key.length!=32)throw new IllegalArgumentException();}
  catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Authorization store key is not configured");}
  try {
   byte[] nonce=new byte[12];byte[] input;
   if(encrypt){random.nextBytes(nonce);input=value.getBytes(StandardCharsets.UTF_8);}
   else {var parts=value.split(":",-1);if(parts.length!=3||!parts[0].equals("v1"))throw new IllegalArgumentException();nonce=Base64.getDecoder().decode(parts[1]);if(nonce.length!=12)throw new IllegalArgumentException();input=Base64.getDecoder().decode(parts[2]);}
   var cipher=Cipher.getInstance("AES/GCM/NoPadding");
   cipher.init(encrypt?Cipher.ENCRYPT_MODE:Cipher.DECRYPT_MODE,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
   cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
   var output=cipher.doFinal(input);
   return encrypt?"v1:"+Base64.getEncoder().encodeToString(nonce)+":"+Base64.getEncoder().encodeToString(output):new String(output,StandardCharsets.UTF_8);
  }catch(Exception e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Authorization store operation failed");}
 }
}
