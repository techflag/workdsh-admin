package com.techflag.workdsh.admin;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
/** Ephemeral runtime observations; never installation or configuration state. */
@RestController
public class ServerPluginsController {
 public record Entry(String packageName,String version,boolean enabled,String phase){}
 public record Report(String organizationId,List<Entry> entries){}
 public record Snapshot(String source,long observedAt,List<Entry> entries){}
 private final AuthService auth;private final String serviceKey;
 private final ConcurrentHashMap<String,Snapshot> snapshots=new ConcurrentHashMap<>();
 public ServerPluginsController(AuthService auth,@Value("${workdsh.secrets.service-key:}")String key){this.auth=auth;this.serviceKey=key;}
 @PutMapping("/api/internal/server-plugins") @ResponseStatus(HttpStatus.NO_CONTENT)
 public void report(@RequestHeader(value="X-WorkDSH-Service-Key",required=false)String key,@RequestBody Report input){
  if(serviceKey.length()<32||key==null||!MessageDigest.isEqual(serviceKey.getBytes(StandardCharsets.UTF_8),key.getBytes(StandardCharsets.UTF_8)))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
  if(input==null||input.organizationId()==null||!input.organizationId().matches("[a-zA-Z0-9_-]{1,128}")||input.entries()==null||input.entries().size()>500)throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
  for(var row:input.entries())if(row==null||row.packageName()==null||!row.packageName().matches("(?:@[a-z0-9._-]+/)?[a-z0-9._-]{1,128}")||row.version()==null||!row.version().matches("[a-zA-Z0-9.+_-]{1,80}")||!List.of("active","pending","failed","disposed","unknown").contains(row.phase()==null?"":row.phase()))throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
  snapshots.put(input.organizationId(),new Snapshot("official-host-inventory",Instant.now().toEpochMilli(),List.copyOf(input.entries())));
 }
 @GetMapping("/api/admin/server-plugins")
 public ResponseEntity<Snapshot> list(@RequestHeader(value="Authorization",required=false)String token){
  var actor=auth.actor(token);auth.requireReady(actor);auth.requireAdmin(actor);
  var value=snapshots.get(actor.organizationId());
  if(value==null||Instant.now().toEpochMilli()-value.observedAt()>45000)throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Runtime inventory unavailable");
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(value);
 }
}
