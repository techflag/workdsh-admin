package com.techflag.workdsh.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.HashSet;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Opaque DSH storage units. Identity is established by the backend, never supplied in data. */
@Service
public class MemberStorage {
 public record Descriptor(String name,int version,List<String> tables,boolean hasGlobal,String layout){}
 private final JdbcTemplate db;private final AuthService auth;private final DatabaseDialect dialect;private final ObjectMapper json;
 public MemberStorage(JdbcTemplate db,AuthService auth,DatabaseDialect dialect,ObjectMapper json){this.db=db;this.auth=auth;this.dialect=dialect;this.json=json;}
 private static void bad(String message){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,message);}
 private AuthService.Actor actor(String bearer,Descriptor d){
  var a=auth.actor(bearer);auth.requireReady(a);
  if(d==null||d.name()==null||!d.name().matches("[a-z][a-z0-9_]{0,127}")||d.version()<0||d.tables()==null||d.tables().size()>128)bad("Invalid storage descriptor");
  if(new HashSet<>(d.tables()).size()!=d.tables().size())bad("Duplicate storage tables");
  for(String table:d.tables())if(table==null||!table.matches("[a-z][a-z0-9_]{0,127}"))bad("Invalid storage table");
  if(d.layout()!=null&&!List.of("single","per-record").contains(d.layout()))bad("Invalid storage layout");
  return a;
 }
 private ObjectNode descriptor(Descriptor d){var value=json.valueToTree(d);((ObjectNode)value).put("layout",d.layout()==null?"single":d.layout());return (ObjectNode)value;}
 private ObjectNode load(AuthService.Actor a,Descriptor d,boolean locked){
  var rows=db.query(locked?dialect.lock("select format_version,descriptor,payload from member_storage_units where organization_id=? and member_id=? and unit_name=?"):"select format_version,descriptor,payload from member_storage_units where organization_id=? and member_id=? and unit_name=?",(rs,n)->{
   if(rs.getInt(1)!=d.version())throw new ResponseStatusException(HttpStatus.CONFLICT,"Storage version mismatch");
   try{
    if(!json.readTree(rs.getString(2)).equals(descriptor(d)))throw new ResponseStatusException(HttpStatus.CONFLICT,"Storage descriptor mismatch");
    JsonNode value=json.readTree(rs.getString(3));
    if(!value.isObject()||!value.path("tables").isObject()||!value.has("global"))throw new IllegalArgumentException();
    return (ObjectNode)value;
   }catch(java.io.IOException|IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Malformed storage unit");}
  },a.organizationId(),a.id(),d.name());
  if(!rows.isEmpty())return rows.get(0);
  var empty=json.createObjectNode();var tables=empty.putObject("tables");for(String t:d.tables())tables.putObject(t);empty.putNull("global");return empty;
 }
 public ObjectNode read(String bearer,Descriptor d){return load(actor(bearer,d),d,false);}
 @Transactional
 public void write(String bearer,Descriptor d,String operation,String table,String key,JsonNode value){
  var a=actor(bearer,d);
  db.queryForObject(dialect.lock("select id from members where organization_id=? and id=?"),String.class,a.organizationId(),a.id());
  var empty=json.createObjectNode();var tables=empty.putObject("tables");for(String t:d.tables())tables.putObject(t);empty.putNull("global");
  // Materialize atomically before locking: no absent-row gap locks across members in MySQL.
  db.update(dialect.initializeStorageUnit(),a.organizationId(),a.id(),d.name(),d.version(),descriptor(d).toString(),empty.toString());
  var snapshot=load(a,d,true);
  if("global".equals(operation)){
   if(!d.hasGlobal())bad("Global storage not declared");snapshot.set("global",value==null?json.nullNode():value);
  }else{
   if(table==null||!d.tables().contains(table)||key==null||key.length()>4096)bad("Invalid storage record");
   if("per-record".equals(d.layout())&&!key.matches("[A-Za-z0-9_-]+"))bad("Invalid record key");
   var records=(ObjectNode)snapshot.path("tables").path(table);
   if("put".equals(operation))records.set(key,value==null?json.nullNode():value);
   else if("delete".equals(operation))records.remove(key);else bad("Invalid storage operation");
  }
  String payload=snapshot.toString();
  db.update("update member_storage_units set payload=? where organization_id=? and member_id=? and unit_name=?",payload,a.organizationId(),a.id(),d.name());
 }
}
