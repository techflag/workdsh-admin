package com.techflag.workdsh.admin;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

/** A short-lived, one-use handoff to the separately deployed DSH Web gateway. */
@RestController
@RequestMapping("/api")
public class DshLaunchController {
    private final JdbcTemplate db;
    private final AuthService auth;
    private final String gatewayOrigin;
    private final String gatewaySecret;
    private final SecureRandom random = new SecureRandom();

    public DshLaunchController(JdbcTemplate db, AuthService auth,
            @Value("${workdsh.dsh-gateway.origin:}") String gatewayOrigin,
            @Value("${workdsh.dsh-gateway.secret:}") String gatewaySecret) {
        this.db = db;
        this.auth = auth;
        this.gatewayOrigin = gatewayOrigin;
        this.gatewaySecret = gatewaySecret;
    }

    public record Launch(String gatewayOrigin, String ticket) {}
    public record Ticket(@NotBlank String ticket) {}
    public record Consumed(String memberId, String organizationId, String grantId) {}

    @PostMapping("/dsh/launch")
    @Transactional
    public Launch launch(@RequestHeader(value = "Authorization", required = false) String bearer) {
        var actor = auth.actor(bearer);
        auth.requireReady(actor);
        verifyConfigured();
        Integer credentials = db.queryForObject("""
                select count(*) from runtime_credentials where member_id=? and active=true
                """, Integer.class, actor.id());
        if (credentials == null || credentials == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "DSH instance is not provisioned");
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        db.update("insert into dsh_launch_tickets(token_hash,member_id,auth_token_hash,expires_at,consumed_at) values (?,?,?,?,null)",
                sha256(ticket), actor.id(), sha256(bearer.substring(7)), Timestamp.from(Instant.now().plusSeconds(60)));
        return new Launch(gatewayOrigin, ticket);
    }

    @PostMapping("/internal/dsh/consume")
    @Transactional
    public Consumed consume(@RequestHeader(value = "Authorization", required = false) String gatewayAuthorization,
            @Valid @RequestBody Ticket body) {
        authorizeGateway(gatewayAuthorization);
        String hash = sha256(body.ticket());
        int consumed = db.update("""
                update dsh_launch_tickets set consumed_at=? where token_hash=?
                and consumed_at is null and expires_at>?
                and member_id in (select id from members where active=true and must_change_password=false)
                """, Timestamp.from(Instant.now()), hash, Timestamp.from(Instant.now()));
        if (consumed != 1) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Launch ticket is invalid");
        return activeGrant(hash);
    }

    @GetMapping("/internal/dsh/grants/{id}")
    public Consumed grant(@RequestHeader(value = "Authorization", required = false) String gatewayAuthorization,
            @PathVariable String id) {
        authorizeGateway(gatewayAuthorization);
        if (!id.matches("[0-9a-f]{64}")) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Invalid DSH grant");
        return activeGrant(id);
    }

    private Consumed activeGrant(String hash) {
        var rows = db.query("""
                select m.id,m.organization_id from dsh_launch_tickets t
                join auth_sessions s on s.token_hash=t.auth_token_hash
                join members m on m.id=t.member_id and m.id=s.member_id
                where t.token_hash=? and t.consumed_at is not null and s.expires_at>?
                and m.active=true and m.must_change_password=false
                """, (rs, row) -> new Consumed(rs.getString("id"), rs.getString("organization_id"), hash),
                hash, Timestamp.from(Instant.now()));
        if (rows.size() != 1) throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "DSH grant is invalid");
        return rows.get(0);
    }

    private void authorizeGateway(String authorization) {
        verifyConfigured();
        String expected = "Gateway " + gatewaySecret;
        if (authorization == null || authorization.length() > 512
                || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                        authorization.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Gateway credential required");
        }
    }

    private void verifyConfigured() {
        if (gatewaySecret.length() < 32 || gatewayOrigin.isBlank()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "DSH gateway is not configured");
        }
        URI uri;
        try { uri = URI.create(gatewayOrigin); }
        catch (IllegalArgumentException error) { throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Invalid DSH gateway origin"); }
        boolean loopback = "127.0.0.1".equals(uri.getHost()) || "localhost".equals(uri.getHost());
        if ((!"https".equals(uri.getScheme()) && !(loopback && "http".equals(uri.getScheme())))
                || uri.getRawUserInfo() != null || uri.getRawQuery() != null || uri.getRawFragment() != null
                || !"".equals(uri.getRawPath())) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Invalid DSH gateway origin");
        }
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
}
