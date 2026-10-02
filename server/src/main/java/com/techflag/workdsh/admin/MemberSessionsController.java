package com.techflag.workdsh.admin;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
@RestController
@RequestMapping("/api/internal/member-sessions")
public class MemberSessionsController {
 private final MemberSessions sessions;private final String serviceKey;
 public MemberSessionsController(MemberSessions sessions,@Value("${workdsh.secrets.service-key:}")String serviceKey){this.sessions=sessions;this.serviceKey=serviceKey;}
 private void server(String supplied){if(serviceKey.length()<32||supplied==null||!MessageDigest.isEqual(serviceKey.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))throw new ResponseStatusException(HttpStatus.FORBIDDEN);}
 public record Request(String id,String operation,JsonNode header,long inheritedEventCount,String writerId,List<JsonNode> events,long offset,long length){}
 @PostMapping
 public ResponseEntity<Object> operation(@RequestHeader(value="X-WorkDSH-Service-Key",required=false)String key,@RequestHeader(value="Authorization",required=false)String bearer,@RequestBody Request input){
  server(key);if(input.operation()==null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
  Object result=switch(input.operation()){
   case "create" -> sessions.create(bearer,input.id(),input.header(),input.inheritedEventCount());
   case "open-read" -> sessions.open(bearer,input.id(),false);
   case "open-write" -> sessions.open(bearer,input.id(),true);
   case "stat" -> sessions.stat(bearer,input.id());
   case "list" -> sessions.list(bearer);
   case "read" -> sessions.read(bearer,input.id(),input.offset(),input.length());
   case "append" -> {sessions.append(bearer,input.id(),input.writerId(),input.events());yield null;}
   case "flush" -> {sessions.flush(bearer,input.id(),input.writerId());yield null;}
   case "close" -> {sessions.close(bearer,input.id(),input.writerId());yield null;}
   default -> throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
  };
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
 }
}
