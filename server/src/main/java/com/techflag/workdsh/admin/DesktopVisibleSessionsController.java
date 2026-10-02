package com.techflag.workdsh.admin;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.IOException;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** Public member Bearer endpoint; never accepts the server service-key storage contract. */
@RestController
@RequestMapping("/api/member/visible-sessions")
public class DesktopVisibleSessionsController {
    private final DesktopVisibleSessions sessions;private final ObjectMapper json;private final AuthService auth;
    public DesktopVisibleSessionsController(DesktopVisibleSessions sessions,ObjectMapper json,AuthService auth){this.sessions=sessions;this.json=json;this.auth=auth;}
    private String bearer(HttpServletRequest request){
        // BrowserSessionFilter may synthesize Bearer from a cookie for management APIs.
        // This API requires the original explicit member Authorization header.
        while(request instanceof HttpServletRequestWrapper wrapper && wrapper.getRequest() instanceof HttpServletRequest original)request=original;
        String value=request.getHeader("Authorization");
        if(value==null||!value.startsWith("Bearer "))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"Explicit member Bearer required");
        auth.requireReady(auth.actor(value));return value;
    }
    private ResponseEntity<DesktopVisibleSessions.Receipt> result(DesktopVisibleSessions.Receipt receipt){return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(receipt);}
    @PostMapping(consumes="application/json")
    public ResponseEntity<DesktopVisibleSessions.Receipt> ingest(HttpServletRequest request)throws IOException{
        String bearer=bearer(request);byte[] body=request.getInputStream().readNBytes(DesktopVisibleSessions.MAX_REQUEST_BYTES+1);
        if(body.length>DesktopVisibleSessions.MAX_REQUEST_BYTES)throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Visible-text request limit exceeded");
        com.fasterxml.jackson.databind.JsonNode input;
        try(var parser=json.getFactory().createParser(body)){parser.enable(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION);input=json.readTree(parser);if(parser.nextToken()!=null)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Expected one visible-text object");}catch(com.fasterxml.jackson.core.JsonProcessingException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid visible-text JSON");}
        return result(sessions.ingest(bearer,input));
    }
    @GetMapping
    public ResponseEntity<DesktopVisibleSessions.Receipt> status(HttpServletRequest request,@RequestParam String deviceId,@RequestParam String sessionId){return result(sessions.status(bearer(request),deviceId,sessionId));}
    @DeleteMapping
    public ResponseEntity<DesktopVisibleSessions.Receipt> delete(HttpServletRequest request,@RequestParam String deviceId,@RequestParam String sessionId){return result(sessions.delete(bearer(request),deviceId,sessionId));}
}
