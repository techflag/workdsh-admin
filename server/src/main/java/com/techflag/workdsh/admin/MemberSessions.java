package com.techflag.workdsh.admin;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Append-only native session data. Ownership and visibility come solely from authenticated membership. */
@Service
public class MemberSessions {
 public record Snapshot(JsonNode header,long inheritedEventCount,long eventCount){}
 public record Opened(Snapshot snapshot,String writerId){}
 private record Stored(Snapshot snapshot,String writer,String login,long expires){}
 private static final long LEASE_MS=60_000;
 private final JdbcTemplate db;private final AuthService auth;private final DatabaseDialect dialect;private final ObjectMapper json;
 public MemberSessions(JdbcTemplate db,AuthService auth,DatabaseDialect dialect,ObjectMapper json){this.db=db;this.auth=auth;this.dialect=dialect;this.json=json;}
 private AuthService.Actor actor(String bearer,String id){var a=auth.actor(bearer);auth.requireReady(a);if(id==null||!id.matches("[A-Za-z0-9_-]{1,128}"))throw error(HttpStatus.BAD_REQUEST,"Invalid session ID");return a;}
 private static ResponseStatusException error(HttpStatus status,String message){return new ResponseStatusException(status,message);}
 private JsonNode decode(String text){try{return json.readTree(text);}catch(java.io.IOException e){throw error(HttpStatus.SERVICE_UNAVAILABLE,"Malformed session storage");}}
 private Stored stored(AuthService.Actor a,String id,boolean lock){
  String sql="select header_json,inherited_count,event_count,writer_id,writer_auth,writer_expires from member_sessions where organization_id=? and member_id=? and session_id=?";
  var rows=db.query(lock?dialect.lock(sql):sql,(rs,n)->new Stored(new Snapshot(decode(rs.getString(1)),rs.getLong(2),rs.getLong(3)),rs.getString(4),rs.getString(5),rs.getLong(6)),a.organizationId(),a.id(),id);
  if(rows.isEmpty())throw error(HttpStatus.NOT_FOUND,"Session not found");return rows.get(0);
 }
 private void memberLock(AuthService.Actor a){db.queryForObject(dialect.lock("select id from members where organization_id=? and id=?"),String.class,a.organizationId(),a.id());}
 private void writer(Stored s,String writer,String login){if(login==null||!login.equals(s.login())||writer==null||!writer.equals(s.writer())||s.expires()<=System.currentTimeMillis())throw error(HttpStatus.CONFLICT,"Session writer ownership lost");}
 private void renew(AuthService.Actor a,String id,String writer,String login){db.update("update member_sessions set writer_id=?,writer_auth=?,writer_expires=? where organization_id=? and member_id=? and session_id=?",writer,login,System.currentTimeMillis()+LEASE_MS,a.organizationId(),a.id(),id);}
 @Transactional
 public Opened create(String bearer,String id,JsonNode header,long inherited){
  var a=actor(bearer,id);memberLock(a);
  if(header==null||!header.isObject()||!id.equals(header.path("id").asText())||inherited<0||(!header.path("isSeeded").asBoolean()&&inherited!=0))throw error(HttpStatus.BAD_REQUEST,"Invalid session metadata");
  String writer=UUID.randomUUID().toString();
  try{db.update("insert into member_sessions(organization_id,member_id,session_id,header_json,inherited_count,event_count,writer_id,writer_auth,writer_expires) values (?,?,?,?,?,0,?,?,?)",a.organizationId(),a.id(),id,header.toString(),inherited,writer,auth.authenticatedSessionKey(bearer),System.currentTimeMillis()+LEASE_MS);}
  catch(org.springframework.dao.DuplicateKeyException e){throw error(HttpStatus.CONFLICT,"Session already exists");}
  return new Opened(new Snapshot(header.deepCopy(),inherited,0),writer);
 }
 @Transactional
 public Opened open(String bearer,String id,boolean write){
  var a=actor(bearer,id);if(write)memberLock(a);var s=stored(a,id,write);
  if(!write)return new Opened(s.snapshot(),null);
  if(s.writer()!=null&&s.login()!=null&&s.expires()>System.currentTimeMillis())throw error(HttpStatus.CONFLICT,"Session already owned");
  String writer=UUID.randomUUID().toString();renew(a,id,writer,auth.authenticatedSessionKey(bearer));return new Opened(s.snapshot(),writer);
 }
 public Snapshot stat(String bearer,String id){return stored(actor(bearer,id),id,false).snapshot();}
 public List<Snapshot> list(String bearer){var a=auth.actor(bearer);auth.requireReady(a);return db.query("select header_json,inherited_count,event_count from member_sessions where organization_id=? and member_id=?",(rs,n)->new Snapshot(decode(rs.getString(1)),rs.getLong(2),rs.getLong(3)),a.organizationId(),a.id());}
 public List<JsonNode> read(String bearer,String id,long offset,long length){
  var a=actor(bearer,id);stored(a,id,false);if(offset<0||length<0||length>10000)throw error(HttpStatus.BAD_REQUEST,"Invalid event slice");
  return db.query("select event_json from member_session_events where organization_id=? and member_id=? and session_id=? and event_seq>=? order by event_seq limit ?",(rs,n)->decode(rs.getString(1)),a.organizationId(),a.id(),id,offset,length);
 }
 @Transactional
 public void append(String bearer,String id,String writer,List<JsonNode> events){
  var a=actor(bearer,id);memberLock(a);var s=stored(a,id,true);writer(s,writer,auth.authenticatedSessionKey(bearer));
  if(events==null||events.size()>10000)throw error(HttpStatus.BAD_REQUEST,"Invalid event batch");
  long cursor=s.snapshot().eventCount();
  for(JsonNode event:events){if(event==null||!event.isObject()||!event.path("seq").isIntegralNumber()||event.path("seq").asLong()!=cursor++)throw error(HttpStatus.CONFLICT,"Session event sequence mismatch");}
  for(JsonNode event:events)db.update("insert into member_session_events(organization_id,member_id,session_id,event_seq,event_json) values (?,?,?,?,?)",a.organizationId(),a.id(),id,event.path("seq").asLong(),event.toString());
  db.update("update member_sessions set event_count=?,writer_expires=? where organization_id=? and member_id=? and session_id=?",cursor,System.currentTimeMillis()+LEASE_MS,a.organizationId(),a.id(),id);
 }
 @Transactional
 public void flush(String bearer,String id,String writer){var a=actor(bearer,id);memberLock(a);var s=stored(a,id,true);writer(s,writer,auth.authenticatedSessionKey(bearer));renew(a,id,writer,auth.authenticatedSessionKey(bearer));}
 @Transactional
 public void close(String bearer,String id,String writer){
  var a=actor(bearer,id);memberLock(a);var s=stored(a,id,true);
  if(writer!=null&&writer.equals(s.writer()))db.update("update member_sessions set writer_id=null,writer_auth=null,writer_expires=0 where organization_id=? and member_id=? and session_id=?",a.organizationId(),a.id(),id);
 }
}
