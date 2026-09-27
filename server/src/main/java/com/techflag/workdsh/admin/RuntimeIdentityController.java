package com.techflag.workdsh.admin;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;

import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** One runtime credential belongs to one member. It never selects identity from a browser header. */
@RestController
@RequestMapping("/api")
public class RuntimeIdentityController {
    private final JdbcTemplate db;
    private final AuthService auth;
    private final MemberController members;

    public RuntimeIdentityController(JdbcTemplate db, AuthService auth, MemberController members) {
        this.db = db; this.auth = auth; this.members = members;
    }

    public record IssuedCredential(String runtimeToken, String memberId) {}
    public record RuntimeIdentity(int contractVersion, String principalId, String organizationId, String organizationName,
                                  String role, int membershipRevision, boolean active) {}

    @PostMapping("/admin/members/{id}/runtime-credential")
    @Transactional
    public IssuedCredential issue(@RequestHeader(value="Authorization", required=false) String bearer,
                                  @PathVariable String id) {
        var actor = auth.actor(bearer);
        auth.requireReady(actor);
        auth.requireAdmin(actor);
        Integer count = db.queryForObject("select count(*) from members where id=? and organization_id=? and active=true",
                Integer.class, id, actor.organizationId());
        if (count == null || count != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Active member not found");
        String secret = auth.newTemporaryPassword();
        db.update("insert into runtime_credentials(token_hash,member_id,created_at,active) values (?,?,?,true)",
                sha256(secret), id, Timestamp.from(Instant.now()));
        members.audit(actor, "runtime.credential.issued", id);
        return new IssuedCredential(secret, id);
    }

    @PostMapping("/admin/members/{id}/runtime-credentials/revoke")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void revoke(@RequestHeader(value="Authorization", required=false) String bearer,
                       @PathVariable String id) {
        var actor = auth.actor(bearer);
        auth.requireReady(actor);
        auth.requireAdmin(actor);
        db.update("update runtime_credentials set active=false where member_id=? and member_id in (select id from members where organization_id=?)",
                id, actor.organizationId());
        members.audit(actor, "runtime.credential.revoked", id);
    }

    @GetMapping("/internal/runtime/identity")
    public RuntimeIdentity resolve(@RequestHeader(value="Authorization", required=false) String authorization) {
        if (authorization == null || !authorization.startsWith("Runtime ") || authorization.length() > 512) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Runtime credential required");
        }
        var rows = db.query("""
                select m.id,m.organization_id,o.name,m.role,m.revision,m.active
                from runtime_credentials c join members m on m.id=c.member_id
                join organizations o on o.id=m.organization_id
                where c.token_hash=? and c.active=true and m.active=true and m.must_change_password=false
                """, (rs, n) -> new RuntimeIdentity(1, rs.getString("id"), rs.getString("organization_id"),
                rs.getString("name"), rs.getString("role"), rs.getInt("revision"), rs.getBoolean("active")),
                sha256(authorization.substring(8)));
        if (rows.size() != 1) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Runtime credential is invalid");
        return rows.get(0);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
