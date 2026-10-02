package com.techflag.workdsh.admin;
import com.fasterxml.jackson.databind.*;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
@RestController @RequestMapping("/api/admin/sessions")
public class AdminSessionsController {
 public record Summary(String memberId,String memberName,String sessionId,long createdAt,long eventCount){}
 public record Message(long seq,long time,String role,JsonNode content){}
 public record Detail(List<Message> messages,long nextOffset,boolean hasMore){}
 private final JdbcTemplate db;private final AuthService auth;private final ObjectMapper json;private final MemberController members;
 public AdminSessionsController(JdbcTemplate db,AuthService auth,ObjectMapper json,MemberController members){this.db=db;this.auth=auth;this.json=json;this.members=members;}
 private AuthService.Actor admin(String token){var a=auth.actor(token);auth.requireReady(a);auth.requireAdmin(a);return a;}
 private JsonNode decode(String value){try{return json.readTree(value);}catch(Exception e){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Malformed session storage");}}
 @GetMapping public List<Summary> list(@RequestHeader(value="Authorization",required=false) String token,@RequestParam(defaultValue="0") int offset,@RequestParam(defaultValue="50") int limit){
  var a=admin(token);if(offset<0||limit<1||limit>100)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
  return db.query("select s.member_id,m.display_name,s.session_id,s.header_json,s.event_count from member_sessions s join members m on m.id=s.member_id and m.organization_id=s.organization_id where s.organization_id=? order by s.member_id,s.session_id limit ? offset ?",(rs,n)->new Summary(rs.getString(1),rs.getString(2),rs.getString(3),decode(rs.getString(4)).path("createdAt").asLong(),rs.getLong(5)),a.organizationId(),limit,offset);
 }
 @GetMapping("/{memberId}/{sessionId}") public Detail detail(@RequestHeader(value="Authorization",required=false) String token,@PathVariable String memberId,@PathVariable String sessionId,@RequestParam(defaultValue="0") long offset){
  var a=admin(token);if(offset<0)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
  Long count=db.query("select inherited_count+event_count from member_sessions where organization_id=? and member_id=? and session_id=?",(rs,n)->rs.getLong(1),a.organizationId(),memberId,sessionId).stream().findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND));
  var events=db.query("select event_json from member_session_events where organization_id=? and member_id=? and session_id=? and event_seq>=? order by event_seq limit 200",(rs,n)->decode(rs.getString(1)),a.organizationId(),memberId,sessionId,offset);
  var messages=events.stream().filter(e->List.of("user/message","assistant/message").contains(e.path("type").asText())).map(e->{var data=e.path("data");var user="user/message".equals(e.path("type").asText());return new Message(e.path("seq").asLong(),e.path("time").asLong(),user?"user":"assistant",user?data.path("content"):data.path("message").path("content"));}).toList();
  long next=events.isEmpty()?offset:events.get(events.size()-1).path("seq").asLong()+1;
  members.audit(a,"session.content.viewed",java.util.UUID.nameUUIDFromBytes((a.organizationId()+":"+memberId+":"+sessionId).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString());
  return new Detail(messages,next,!events.isEmpty()&&next<count);
 }
}
