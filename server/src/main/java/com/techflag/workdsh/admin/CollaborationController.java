package com.techflag.workdsh.admin;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/collaboration")
public class CollaborationController {
    private final JdbcTemplate db;
    private final AuthService auth;

    public CollaborationController(JdbcTemplate db, AuthService auth) {
        this.db = db;
        this.auth = auth;
    }

    public record Colleague(String id, String displayName, String email) {}
    public record Handoff(String id, String senderId, String senderName, String recipientId,
                          String recipientName, String summary, String status, String resolution,
                          Instant createdAt, Instant completedAt) {}
    public record NewHandoff(@NotBlank String recipientId,
                             @NotBlank @Size(max=2000) String summary,
                             @NotBlank @Size(max=128) String requestKey) {}
    public record Completion(@NotBlank @Size(max=2000) String resolution) {}

    @GetMapping("/colleagues")
    public List<Colleague> colleagues(@RequestHeader(value="Authorization", required=false) String authorization) {
        var actor = ready(authorization);
        return db.query("select id,display_name,email from members where organization_id=? and active=true and must_change_password=false and id<>? order by display_name,email",
                (rs, n) -> new Colleague(rs.getString("id"), rs.getString("display_name"), rs.getString("email")), actor.organizationId(), actor.id());
    }

    @GetMapping("/inbox")
    public List<Handoff> inbox(@RequestHeader(value="Authorization", required=false) String authorization) {
        var actor = ready(authorization);
        return list("h.recipient_id=?", actor);
    }

    @GetMapping("/sent")
    public List<Handoff> sent(@RequestHeader(value="Authorization", required=false) String authorization) {
        var actor = ready(authorization);
        return list("h.sender_id=?", actor);
    }

    @PostMapping("/handoffs")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Handoff send(@RequestHeader(value="Authorization", required=false) String authorization,
                        @Valid @RequestBody NewHandoff input) {
        var actor = ready(authorization);
        String recipient = input.recipientId().trim();
        if (recipient.equals(actor.id()) || !recipient.matches("[0-9a-fA-F-]{36}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose another active colleague");
        }
        Integer count = db.queryForObject("select count(*) from members where id=? and organization_id=? and active=true and must_change_password=false",
                Integer.class, recipient, actor.organizationId());
        if (count == null || count != 1) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choose another active colleague");
        String summary = input.summary().trim();
        String key = input.requestKey().trim();
        if (summary.isEmpty() || key.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Summary and request key required");
        var previous = db.query("select id,recipient_id,summary from collaboration_handoffs where sender_id=? and request_key=?",
                (rs,n) -> new String[]{rs.getString("id"),rs.getString("recipient_id"),rs.getString("summary")}, actor.id(), key);
        if (!previous.isEmpty()) {
            if (!previous.get(0)[1].equals(recipient) || !previous.get(0)[2].equals(summary))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "Request key already used");
            return one(previous.get(0)[0], actor);
        }
        String id = UUID.randomUUID().toString();
        try {
            db.update("insert into collaboration_handoffs(id,organization_id,sender_id,recipient_id,request_key,summary,status,created_at) values (?,?,?,?,?,?,?,?)",
                    id, actor.organizationId(), actor.id(), recipient, key, summary, "OPEN", Timestamp.from(Instant.now()));
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Request key already used");
        }
        audit(actor, "collaboration.handoff.sent", id);
        return one(id, actor);
    }

    @PostMapping("/handoffs/{id}/complete")
    @Transactional
    public Handoff complete(@RequestHeader(value="Authorization", required=false) String authorization,
                            @PathVariable String id, @Valid @RequestBody Completion input) {
        var actor = ready(authorization);
        if (input.resolution().trim().isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Resolution required");
        int changed = db.update("update collaboration_handoffs set status='DONE',resolution=?,completed_at=? where id=? and organization_id=? and recipient_id=? and status='OPEN'",
                input.resolution().trim(), Timestamp.from(Instant.now()), id, actor.organizationId(), actor.id());
        if (changed != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "Handoff unavailable or already completed");
        audit(actor, "collaboration.handoff.completed", id);
        return one(id, actor);
    }

    private AuthService.Actor ready(String authorization) {
        var actor = auth.businessActor(authorization);
        auth.requireReady(actor);
        return actor;
    }

    private List<Handoff> list(String side, AuthService.Actor actor) {
        return db.query("""
                select h.id,h.sender_id,s.display_name as sender_name,h.recipient_id,r.display_name as recipient_name,
                       h.summary,h.status,h.resolution,h.created_at,h.completed_at
                from collaboration_handoffs h
                join members s on s.id=h.sender_id join members r on r.id=h.recipient_id
                where h.organization_id=? and """ + " " + side + " order by h.created_at desc limit 100",
                (rs,n) -> handoff(rs), actor.organizationId(), actor.id());
    }

    private Handoff one(String id, AuthService.Actor actor) {
        return db.query("""
                select h.id,h.sender_id,s.display_name as sender_name,h.recipient_id,r.display_name as recipient_name,
                       h.summary,h.status,h.resolution,h.created_at,h.completed_at
                from collaboration_handoffs h
                join members s on s.id=h.sender_id join members r on r.id=h.recipient_id
                where h.id=? and h.organization_id=? and (h.sender_id=? or h.recipient_id=?)
                """, (rs,n) -> handoff(rs), id, actor.organizationId(), actor.id(), actor.id())
                .stream().findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Handoff not found"));
    }

    private Handoff handoff(java.sql.ResultSet rs) throws java.sql.SQLException {
        Timestamp completed = rs.getTimestamp("completed_at");
        return new Handoff(rs.getString("id"), rs.getString("sender_id"), rs.getString("sender_name"),
                rs.getString("recipient_id"), rs.getString("recipient_name"), rs.getString("summary"),
                rs.getString("status"), rs.getString("resolution"), rs.getTimestamp("created_at").toInstant(),
                completed == null ? null : completed.toInstant());
    }

    private void audit(AuthService.Actor actor, String action, String id) {
        db.update("insert into audit_events(id,organization_id,actor_id,action,target_id,occurred_at) values (?,?,?,?,?,?)",
                UUID.randomUUID().toString(), actor.organizationId(), actor.id(), action, id, Timestamp.from(Instant.now()));
    }
}
