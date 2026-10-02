package com.techflag.workdsh.admin;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Private Host-to-backend adapter. Browser and model cannot choose data ownership. */
@RestController
@RequestMapping("/api/internal/member-storage")
public class MemberStorageController {
 private final MemberStorage storage;private final String serviceKey;
 public MemberStorageController(MemberStorage storage,@Value("${workdsh.secrets.service-key:}")String serviceKey){this.storage=storage;this.serviceKey=serviceKey;}
 private void server(String supplied){if(serviceKey.length()<32||supplied==null||!MessageDigest.isEqual(serviceKey.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8)))throw new ResponseStatusException(HttpStatus.FORBIDDEN);}
 public record Read(MemberStorage.Descriptor descriptor){}
 public record Write(MemberStorage.Descriptor descriptor,String operation,String table,String key,JsonNode value){}
 @PostMapping("/read")
 public ResponseEntity<JsonNode> read(@RequestHeader(value="X-WorkDSH-Service-Key",required=false)String key,@RequestHeader(value="Authorization",required=false)String bearer,@RequestBody Read input){server(key);return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(storage.read(bearer,input.descriptor()));}
 @PostMapping("/write") @ResponseStatus(HttpStatus.NO_CONTENT)
 public void write(@RequestHeader(value="X-WorkDSH-Service-Key",required=false)String key,@RequestHeader(value="Authorization",required=false)String bearer,@RequestBody Write input){server(key);storage.write(bearer,input.descriptor(),input.operation(),input.table(),input.key(),input.value());}
}
