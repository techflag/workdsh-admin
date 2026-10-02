package com.techflag.workdsh.admin;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
/** DSH server-to-server adapter only; caller cannot select a different member. */
@RestController
@RequestMapping("/api/internal/member-secrets")
public class MemberSecretsController {
 private final MemberSecrets secrets;private final String serviceKey;
 public MemberSecretsController(MemberSecrets secrets,@Value("${workdsh.secrets.service-key:}")String serviceKey){this.secrets=secrets;this.serviceKey=serviceKey;}
 private void server(String supplied){
  if(serviceKey.length()<32||supplied==null||!MessageDigest.isEqual(serviceKey.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))
   throw new ResponseStatusException(HttpStatus.FORBIDDEN);
 }
 public record Secret(@NotBlank @Size(max=8192)String value){}
 @GetMapping("/references/{ref}")
 public ResponseEntity<Secret> read(@RequestHeader(value="X-WorkDSH-Service-Key",required=false)String key,@RequestHeader(value="Authorization",required=false)String bearer,@PathVariable String ref){
  server(key);String value=secrets.read(bearer,ref);
  return ResponseEntity.status(value==null?HttpStatus.NOT_FOUND:HttpStatus.OK).cacheControl(CacheControl.noStore()).body(value==null?null:new Secret(value));
 }
 @PutMapping("/references/{ref}") @ResponseStatus(HttpStatus.NO_CONTENT)
 public void write(@RequestHeader(value="X-WorkDSH-Service-Key",required=false)String key,@RequestHeader(value="Authorization",required=false)String bearer,@PathVariable String ref,@Valid @RequestBody Secret input){server(key);secrets.write(bearer,ref,input.value());}
 @DeleteMapping("/references/{ref}") @ResponseStatus(HttpStatus.NO_CONTENT)
 public void delete(@RequestHeader(value="X-WorkDSH-Service-Key",required=false)String key,@RequestHeader(value="Authorization",required=false)String bearer,@PathVariable String ref){server(key);secrets.delete(bearer,ref);}
 public record RecordUpdate(@jakarta.validation.constraints.Min(0)long revision,com.fasterxml.jackson.databind.JsonNode record){}
 @GetMapping("/records")
 public ResponseEntity<java.util.List<MemberSecrets.RecordEntry>> list(@RequestHeader(value="X-WorkDSH-Service-Key",required=false)String key,@RequestHeader(value="Authorization",required=false)String bearer){server(key);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(secrets.listRecords(bearer));}
 @GetMapping("/records/{scope}/{id}")
 public ResponseEntity<MemberSecrets.StoredRecord> record(@RequestHeader(value="X-WorkDSH-Service-Key",required=false)String key,@RequestHeader(value="Authorization",required=false)String bearer,@PathVariable String scope,@PathVariable String id){server(key);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(secrets.readRecord(bearer,scope+"/"+id));}
 @PutMapping("/records/{scope}/{id}")
 public ResponseEntity<MemberSecrets.StoredRecord> replace(@RequestHeader(value="X-WorkDSH-Service-Key",required=false)String key,@RequestHeader(value="Authorization",required=false)String bearer,@PathVariable String scope,@PathVariable String id,@Valid @RequestBody RecordUpdate update){server(key);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(secrets.replaceRecord(bearer,scope+"/"+id,update.revision(),update.record()));}
}
