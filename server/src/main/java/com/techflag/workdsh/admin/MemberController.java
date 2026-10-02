package com.techflag.workdsh.admin;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class MemberController {
    private final JdbcTemplate db;
    private final AuthService auth;

    public MemberController(JdbcTemplate db, AuthService auth) { this.db = db; this.auth = auth; }

    public record Member(String id, String email, String displayName, String role, boolean active,
                         boolean mustChangePassword, int revision) {}
    public record NewMember(@Email @NotBlank String email, @NotBlank @Size(max=120) String displayName,
                            @NotBlank String role) {}
    public record CreatedMember(Member member, String temporaryPassword) {}
    public record UpdateMember(String role, Boolean active, int expectedRevision) {}
    public record Audit(String id, String actorId, String action, String targetId, Instant occurredAt) {}

    @GetMapping("/members")
    public List<Member> directory(@RequestHeader(value="Authorization", required=false) String bearer) {
        var actor = auth.actor(bearer);
        auth.requireReady(actor);
        return db.query("select id,email,display_name,role,active,must_change_password,revision from members where organization_id=? and active=true and (not exists(select 1 from organization_policies where organization_id=members.organization_id and directory_scope='DEPARTMENT') or coalesce((select department_id from member_profiles where member_id=members.id),'')=coalesce((select department_id from member_profiles where member_id=?),'')) order by display_name",
                (rs,n)->new Member(rs.getString("id"),rs.getString("email"),rs.getString("display_name"),rs.getString("role"),rs.getBoolean("active"),rs.getBoolean("must_change_password"),rs.getInt("revision")),actor.organizationId(),actor.id());
    }

    @GetMapping("/admin/members")
    public List<Member> all(@RequestHeader(value="Authorization", required=false) String bearer) {
        var actor = auth.actor(bearer);
        auth.requireReady(actor);
        auth.requireAdmin(actor);
        return members(actor.organizationId(), false);
    }

    @PostMapping("/admin/members")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public CreatedMember create(@RequestHeader(value="Authorization", required=false) String bearer,
                                @Valid @RequestBody NewMember request) {
        var actor = auth.actor(bearer);
        auth.requireReady(actor);
        auth.requireAdmin(actor);
        if (!List.of("ADMIN", "MEMBER").contains(request.role())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid role");
        }
        if (request.role().equals("ADMIN") && !actor.role().equals("OWNER")) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only owner may appoint admins");
        String id = UUID.randomUUID().toString();
        String temporaryPassword = auth.newTemporaryPassword();
        try {
            db.update("insert into members(id,organization_id,email,display_name,role,password_hash,must_change_password,active,revision) values (?,?,?,?,?,?,true,true,1)",
                    id, actor.organizationId(), request.email().trim().toLowerCase(Locale.ROOT),
                    request.displayName().trim(), request.role(), auth.encode(temporaryPassword));
        } catch (DuplicateKeyException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Member email already exists");
        }
        audit(actor, "member.created", id);
        return new CreatedMember(new Member(id, request.email().trim().toLowerCase(Locale.ROOT),
                request.displayName().trim(), request.role(), true, true, 1), temporaryPassword);
    }

    @PatchMapping("/admin/members/{id}")
    @Transactional
    public Member update(@RequestHeader(value="Authorization", required=false) String bearer,
                         @PathVariable String id, @RequestBody UpdateMember change) {
        var actor = auth.actor(bearer);
        auth.requireReady(actor);
        auth.requireAdmin(actor);
        Member before = member(actor.organizationId(), id);
        if (before.role().equals("OWNER")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Owner cannot be changed here");
        }
        if (!actor.role().equals("OWNER") && (before.role().equals("ADMIN") || "ADMIN".equals(change.role()))) throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only owner may manage admins");
        String role = change.role() == null ? before.role() : change.role();
        if (!List.of("ADMIN", "MEMBER").contains(role)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid role");
        }
        boolean active = change.active() == null ? before.active() : change.active();
        if (id.equals(actor.id()) && !active) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Cannot disable yourself");
        }
        int changed = db.update("update members set role=?,active=?,revision=revision+1 where id=? and organization_id=? and revision=?",
                role, active, id, actor.organizationId(), change.expectedRevision());
        if (changed != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "Member changed; refresh first");
        if (!active) {
            db.update("delete from auth_sessions where member_id=?", id);
        }
        audit(actor, active ? "member.updated" : "member.disabled", id);
        return member(actor.organizationId(), id);
    }

    @GetMapping("/admin/audit")
    public List<Audit> auditList(@RequestHeader(value="Authorization", required=false) String bearer) {
        var actor = auth.actor(bearer);
        auth.requireReady(actor);
        auth.requireAdmin(actor);
        return db.query("select id,actor_id,action,target_id,occurred_at from audit_events where organization_id=? order by occurred_at desc limit 100",
                (rs, n) -> new Audit(rs.getString("id"), rs.getString("actor_id"), rs.getString("action"),
                        rs.getString("target_id"), rs.getTimestamp("occurred_at").toInstant()), actor.organizationId());
    }

    private List<Member> members(String org, boolean activeOnly) {
        return db.query("select id,email,display_name,role,active,must_change_password,revision from members where organization_id=?"
                        + (activeOnly ? " and active=true" : "") + " order by display_name",
                (rs, n) -> new Member(rs.getString("id"), rs.getString("email"), rs.getString("display_name"),
                        rs.getString("role"), rs.getBoolean("active"), rs.getBoolean("must_change_password"),
                        rs.getInt("revision")), org);
    }

    private Member member(String org, String id) {
        return db.query("select id,email,display_name,role,active,must_change_password,revision from members where organization_id=? and id=?",
                (rs, n) -> new Member(rs.getString("id"), rs.getString("email"), rs.getString("display_name"),
                        rs.getString("role"), rs.getBoolean("active"), rs.getBoolean("must_change_password"),
                        rs.getInt("revision")), org, id).stream().findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Member not found"));
    }

    void audit(AuthService.Actor actor, String action, String targetId) {
        db.update("insert into audit_events(id,organization_id,actor_id,action,target_id,occurred_at) values (?,?,?,?,?,?)",
                UUID.randomUUID().toString(), actor.organizationId(), actor.id(), action, targetId,
                Timestamp.from(Instant.now()));
    }
}
