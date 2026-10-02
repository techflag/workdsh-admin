package com.techflag.workdsh.admin;

import java.util.List;
import java.util.Base64;
import java.security.MessageDigest;
import java.util.HexFormat;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Immutable explicitly selected material snapshot, accessible only to handoff parties. */
@RestController
@RequestMapping("/api/collaboration/materials")
public class CollaborationMaterialsController {
    private final CollaborationController collaboration;
    private final JdbcTemplate db;
    private final ObjectMapper json;
    public CollaborationMaterialsController(CollaborationController collaboration, JdbcTemplate db, ObjectMapper json) {
        this.collaboration=collaboration; this.db=db; this.json=json;
    }
    public record FileItem(@NotBlank @Size(max=200) String name, @NotBlank String sha256,
                           @NotBlank @Size(max=7000000) String data) {}
    public record Bundle(@NotBlank @Size(max=36) String recipientId,
                         @NotBlank @Size(max=128) String requestKey,
                         @NotBlank @Size(max=2000) String summary,
                         @NotBlank @Size(max=200000) String context,
                         @NotNull @Size(max=10) List<@Valid FileItem> files) {}
    public record Materials(String context,List<FileItem> files) {}
    @PostMapping
    @Transactional
    public CollaborationController.Handoff send(@RequestHeader(value="Authorization",required=false) String authorization,
                                                @Valid @RequestBody Bundle input) throws Exception {
        int total=0;
        for(var file:input.files()) {
            if(file.name().contains("/")||file.name().contains("\\")||file.name().contains("\0"))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid filename");
            byte[] bytes;
            try {bytes=Base64.getDecoder().decode(file.data());}catch(IllegalArgumentException e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"Invalid file bytes");}
            total+=bytes.length;
            if(total>5*1024*1024) throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE,"Demo material limit is 5 MiB");
            if(!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)).equals(file.sha256()))
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"File checksum mismatch");
        }
        var handoff=collaboration.send(authorization,new CollaborationController.NewHandoff(input.recipientId(),input.summary(),input.requestKey()));
        String payload=json.writeValueAsString(new Materials(input.context(),input.files()));
        var existing=db.query("select payload from collaboration_materials where handoff_id=?",(rs,n)->rs.getString(1),handoff.id());
        if(!existing.isEmpty()) {
            if(!existing.get(0).equals(payload))throw new ResponseStatusException(HttpStatus.CONFLICT,"Material retry differs from original");
        } else db.update("insert into collaboration_materials(handoff_id,payload) values (?,?)",handoff.id(),payload);
        return handoff;
    }
    @GetMapping("/{id}")
    public Materials read(@RequestHeader(value="Authorization",required=false) String authorization,@PathVariable String id) throws Exception {
        // Existing controller verifies active membership, organization and sender/recipient ACL.
        collaboration.detail(authorization,id);
        var rows=db.query("select payload from collaboration_materials where handoff_id=?",(rs,n)->rs.getString(1),id);
        if(rows.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"No shared materials");
        return json.readValue(rows.get(0),Materials.class);
    }
}
