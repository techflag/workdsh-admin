package com.techflag.workdsh.admin;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** One deployment signing record, never a member authorization or administrator UI. */
@RestController
@RequestMapping("/api/internal/transport-record")
public class TransportRecordController {
 private static final String KEY="client-connection/browser-session";
 private final JdbcTemplate db;private final MemberSecrets cipher;private final ObjectMapper json;private final String serviceKey;private final DatabaseDialect dialect;
 public TransportRecordController(JdbcTemplate db,MemberSecrets cipher,ObjectMapper json,DatabaseDialect dialect,@Value("${workdsh.secrets.service-key:}")String serviceKey){this.db=db;this.cipher=cipher;this.json=json;this.dialect=dialect;this.serviceKey=serviceKey;}
 private void server(String supplied){
  if(serviceKey.length()<32||supplied==null||!MessageDigest.isEqual(serviceKey.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))throw new ResponseStatusException(HttpStatus.FORBIDDEN);
 }
 public record Stored(long revision,JsonNode record){}
 public record Update(@Min(0)long revision,JsonNode record){}
 private Stored stored(){
  var rows=db.query("select revision,encrypted_value from server_transport_records where record_key=?",(rs,n)->{
   try{return new Stored(rs.getLong(1),json.readTree(cipher.cryptTransport(false,rs.getString(2))));}
   catch(java.io.IOException error){throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"Invalid transport record");}
  },KEY);
  return rows.isEmpty()?new Stored(0,null):rows.get(0);
 }
 @GetMapping public ResponseEntity<Stored> read(@RequestHeader(value="X-WorkDSH-Service-Key",required=false)String key){server(key);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(stored());}
 @PutMapping @Transactional public ResponseEntity<Stored> write(@RequestHeader(value="X-WorkDSH-Service-Key",required=false)String key,@Valid @RequestBody Update input){
  server(key);var record=input.record();
  if(record==null||!record.isObject()||!record.path("kind").asText().equals("grant")||!record.has("payload")||record.toString().length()>65536)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid transport grant");
  String encrypted=cipher.cryptTransport(true,record.toString());long next=Math.addExact(input.revision(),1);
  if(input.revision()==0)db.update(dialect.initializeTransportRecord(),KEY);
  if(db.update("update server_transport_records set revision=?,encrypted_value=? where record_key=? and revision=?",next,encrypted,KEY,input.revision())!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"Transport record changed");
  return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new Stored(next,record));
 }
}
