package com.techflag.workdsh.admin;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api")
public class OrderController {
    private final JdbcTemplate db;
    private final AuthService auth;
    private final MemberController members;
    private final OrderExtractionService extraction;

    public OrderController(JdbcTemplate db, AuthService auth, MemberController members, OrderExtractionService extraction) {
        this.db = db; this.auth = auth; this.members = members; this.extraction = extraction;
    }

    public record Line(String id, String customerSku, String customerReference, String customerName, int quantity,
                       String internalSku, String matchStatus, String sourceId, String sourceLocator) {}
    public record Order(String id, String customerName, String sourceType, String sourceName,
                        String status, String creatorId, String reviewerId, int revision,
                        Instant createdAt, List<Line> lines) {}
    public record OrderSummary(String id, String customerName, String sourceType, String status,
                               String creatorId, String reviewerId, int revision, Instant createdAt) {}
    public record NewLine(@Size(max=160) String customerSku, @Size(max=160) String customerReference,
                          @NotBlank String customerName,
                          @Min(1) int quantity, String internalSku) {}
    public record NewOrder(@NotBlank String customerName, @NotBlank String sourceType,
                           @NotBlank String sourceName, @NotNull List<@Valid NewLine> lines) {}
    public record MatchLine(String internalSku, int expectedRevision) {}
    public record ReviewRequest(@NotBlank String reviewerId, int expectedRevision) {}
    public record ReviewDecision(@NotBlank String decision, @Size(max=1000) String comment,
                                 int expectedRevision) {}
    public record OrderAccess(String memberId, String accessRole) {}
    public record ReviewEvent(String id, String actorId, String action, String comment, Instant createdAt) {}
    public record OrderSource(String id, String fileName, String sourceType, String mediaType,
                              long sizeBytes, String sha256, String uploadedBy, Instant createdAt) {}
    public record ImportCandidate(@NotBlank String sourceId, @NotBlank String locator, int expectedRevision) {}

    @PostMapping(value="/orders/{id}/sources", consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public OrderSource uploadSource(@RequestHeader(value="Authorization", required=false) String bearer,
                                    @PathVariable String id, @RequestParam int expectedRevision,
                                    @RequestPart("file") MultipartFile file) throws IOException {
        var actor = ready(bearer);
        Order before = order(actor, id);
        if (!actor.id().equals(before.creatorId()) || !List.of("DRAFT", "CHANGES_REQUESTED").contains(before.status())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the creator can attach a source before review");
        }
        if (before.revision() != expectedRevision) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order changed; refresh first");
        }
        if (file.isEmpty() || file.getSize() > 12L * 1024 * 1024) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Source file must be between 1 byte and 12 MB");
        }
        Integer count = db.queryForObject("select count(*) from order_sources where order_id=?", Integer.class, id);
        if (count != null && count >= 10) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "At most 10 sources per order");
        String name = safeFileName(file.getOriginalFilename());
        byte[] bytes = file.getBytes();
        String type = sourceType(name, bytes);
        String mediaType = switch (type) {
            case "PDF" -> "application/pdf";
            case "EXCEL" -> name.toLowerCase().endsWith(".xlsx") ?
                    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet" : "application/vnd.ms-excel";
            default -> name.toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg";
        };
        String sourceId = UUID.randomUUID().toString();
        Instant now = Instant.now();
        String hash = sha256(bytes);
        int updated = db.update("update orders set revision=revision+1 where id=? and revision=? and status in ('DRAFT','CHANGES_REQUESTED')",
                id, expectedRevision);
        if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "Order changed; refresh first");
        db.update("insert into order_sources(id,order_id,uploaded_by,file_name,source_type,media_type,size_bytes,sha256,content,created_at) values (?,?,?,?,?,?,?,?,?,?)",
                sourceId, id, actor.id(), name, type, mediaType, bytes.length, hash, bytes, Timestamp.from(now));
        members.audit(actor, "order.source.uploaded", id);
        return new OrderSource(sourceId, name, type, mediaType, bytes.length, hash, actor.id(), now);
    }

    @GetMapping("/orders/{id}/sources")
    public List<OrderSource> sources(@RequestHeader(value="Authorization", required=false) String bearer,
                                     @PathVariable String id) {
        var actor = ready(bearer);
        order(actor, id);
        return db.query("select id,file_name,source_type,media_type,size_bytes,sha256,uploaded_by,created_at from order_sources where order_id=? order by created_at,id",
                (rs, n) -> new OrderSource(rs.getString("id"), rs.getString("file_name"), rs.getString("source_type"),
                        rs.getString("media_type"), rs.getLong("size_bytes"), rs.getString("sha256"),
                        rs.getString("uploaded_by"), rs.getTimestamp("created_at").toInstant()), id);
    }

    @GetMapping("/orders/{id}/sources/{sourceId}/download")
    public ResponseEntity<byte[]> downloadSource(@RequestHeader(value="Authorization", required=false) String bearer,
                                                  @PathVariable String id, @PathVariable String sourceId) {
        var actor = ready(bearer);
        order(actor, id);
        var source = db.query("select file_name,content from order_sources where order_id=? and id=?",
                (rs, n) -> new Download(rs.getString("file_name"), rs.getBytes("content")), id, sourceId);
        if (source.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Source not found");
        var item = source.get(0);
        return ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition", ContentDisposition.attachment().filename(item.fileName(), StandardCharsets.UTF_8).build().toString())
                .header("Cache-Control", "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(item.content());
    }

    private record Download(String fileName, byte[] content) {}
    private record SourceData(String sourceType, String sha256, byte[] content) {}

    @GetMapping("/orders/{id}/sources/{sourceId}/preview")
    public OrderExtractionService.Preview previewSource(@RequestHeader(value="Authorization", required=false) String bearer,
                                                         @PathVariable String id, @PathVariable String sourceId) {
        var actor = ready(bearer);
        order(actor, id);
        SourceData source = sourceData(id, sourceId);
        if (!"EXCEL".equals(source.sourceType())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Structured preview currently supports Excel only");
        }
        return extraction.excel(sourceId, source.sha256(), source.content());
    }

    @PostMapping("/orders/{id}/lines/import")
    @Transactional
    public Order importLine(@RequestHeader(value="Authorization", required=false) String bearer,
                            @PathVariable String id, @Valid @RequestBody ImportCandidate input) {
        var actor = ready(bearer);
        Order before = order(actor, id);
        if (!actor.id().equals(before.creatorId()) || !List.of("DRAFT", "CHANGES_REQUESTED").contains(before.status())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the creator can import order lines before review");
        }
        if (before.revision() != input.expectedRevision()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order changed; refresh first");
        }
        SourceData source = sourceData(id, input.sourceId());
        if (!"EXCEL".equals(source.sourceType())) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Structured import currently supports Excel only");
        }
        var candidate = extraction.excel(input.sourceId(), source.sha256(), source.content()).candidates().stream()
                .filter(row -> row.locator().equals(input.locator())).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Source row not found"));
        Integer existing = db.queryForObject("select count(*) from order_lines where order_id=? and source_id=? and source_locator=?",
                Integer.class, id, input.sourceId(), input.locator());
        if (existing != null && existing > 0) throw new ResponseStatusException(HttpStatus.CONFLICT, "Source row already imported");
        if (before.lines().size() >= 500) throw new ResponseStatusException(HttpStatus.CONFLICT, "Order already has 500 lines");
        int updated = db.update("update orders set revision=revision+1 where id=? and revision=? and status in ('DRAFT','CHANGES_REQUESTED')",
                id, input.expectedRevision());
        if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "Order changed; refresh first");
        db.update("insert into order_lines(id,order_id,customer_sku,customer_reference,customer_name,quantity,internal_sku,match_status,source_id,source_locator) values (?,?,?,?,?,?,null,'UNMATCHED',?,?)",
                UUID.randomUUID().toString(), id, candidate.customerSku(), candidate.customerReference(),
                candidate.customerName(), candidate.quantity(), input.sourceId(), candidate.locator());
        members.audit(actor, "order.line.imported", id);
        return order(actor, id);
    }

    private SourceData sourceData(String orderId, String sourceId) {
        var found = db.query("select source_type,sha256,content from order_sources where order_id=? and id=?",
                (rs, n) -> new SourceData(rs.getString("source_type"), rs.getString("sha256"), rs.getBytes("content")),
                orderId, sourceId);
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Source not found");
        return found.get(0);
    }

    @PostMapping("/orders")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Order create(@RequestHeader(value="Authorization", required=false) String bearer,
                        @Valid @RequestBody NewOrder input) {
        var actor = ready(bearer);
        if (!List.of("SCREENSHOT", "PDF", "EXCEL").contains(input.sourceType())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid source type");
        }
        if (input.lines().size() > 500) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Too many lines");
        String id = UUID.randomUUID().toString();
        db.update("insert into orders(id,organization_id,creator_id,customer_name,source_type,source_name,status,reviewer_id,revision,created_at) values (?,?,?,?,?,?,'DRAFT',null,1,?)",
                id, actor.organizationId(), actor.id(), input.customerName().trim(), input.sourceType(),
                input.sourceName().trim(), Timestamp.from(Instant.now()));
        db.update("insert into order_access(order_id,member_id,access_role) values (?,?,?)", id, actor.id(), "OWNER");
        for (NewLine line : input.lines()) {
            db.update("insert into order_lines(id,order_id,customer_sku,customer_reference,customer_name,quantity,internal_sku,match_status) values (?,?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(), id, textOrEmpty(line.customerSku()), textOrEmpty(line.customerReference()), line.customerName().trim(),
                    line.quantity(), normalizeSku(line.internalSku()),
                    normalizeSku(line.internalSku()) == null ? "UNMATCHED" : "MANUAL_MATCH");
        }
        members.audit(actor, "order.created", id);
        return order(actor, id);
    }

    @GetMapping("/orders")
    public List<OrderSummary> list(@RequestHeader(value="Authorization", required=false) String bearer) {
        var actor = ready(bearer);
        return db.query("""
                select o.id,o.customer_name,o.source_type,o.status,o.creator_id,o.reviewer_id,o.revision,o.created_at
                from orders o join order_access a on a.order_id=o.id
                where o.organization_id=? and a.member_id=? order by o.created_at desc
                """, (rs, n) -> new OrderSummary(rs.getString("id"), rs.getString("customer_name"),
                rs.getString("source_type"), rs.getString("status"), rs.getString("creator_id"),
                rs.getString("reviewer_id"), rs.getInt("revision"), rs.getTimestamp("created_at").toInstant()),
                actor.organizationId(), actor.id());
    }

    @GetMapping("/orders/{id}")
    public Order detail(@RequestHeader(value="Authorization", required=false) String bearer,
                        @PathVariable String id) { return order(ready(bearer), id); }

    @GetMapping("/orders/{id}/events")
    public List<ReviewEvent> events(@RequestHeader(value="Authorization", required=false) String bearer,
                                    @PathVariable String id) {
        var actor = ready(bearer);
        order(actor, id);
        return db.query("select id,actor_id,action,comment,created_at from review_events where order_id=? order by created_at,id",
                (rs, n) -> new ReviewEvent(rs.getString("id"), rs.getString("actor_id"),
                        rs.getString("action"), rs.getString("comment"), rs.getTimestamp("created_at").toInstant()), id);
    }

    @PatchMapping("/orders/{id}/lines/{lineId}/match")
    @Transactional
    public Order match(@RequestHeader(value="Authorization", required=false) String bearer,
                       @PathVariable String id, @PathVariable String lineId,
                       @RequestBody MatchLine input) {
        var actor = ready(bearer);
        Order before = order(actor, id);
        if (!actor.id().equals(before.creatorId()) || !List.of("DRAFT", "CHANGES_REQUESTED").contains(before.status())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Order is not editable");
        }
        if (before.revision() != input.expectedRevision()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Order changed; refresh first");
        }
        String sku = normalizeSku(input.internalSku());
        int changed = db.update("update order_lines set internal_sku=?,match_status=? where id=? and order_id=?",
                sku, sku == null ? "UNMATCHED" : "MANUAL_MATCH", lineId, id);
        if (changed != 1) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Line not found");
        int updated = db.update("update orders set revision=revision+1 where id=? and revision=?", id, input.expectedRevision());
        if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "Order changed; refresh first");
        members.audit(actor, "order.match.updated", id);
        return order(actor, id);
    }

    @PostMapping("/orders/{id}/submit-review")
    @Transactional
    public Order submit(@RequestHeader(value="Authorization", required=false) String bearer,
                        @PathVariable String id, @RequestBody ReviewRequest input) {
        var actor = ready(bearer);
        Order before = order(actor, id);
        if (!actor.id().equals(before.creatorId()) || !List.of("DRAFT", "CHANGES_REQUESTED").contains(before.status())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Only the creator can submit this order");
        }
        if (actor.id().equals(input.reviewerId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reviewer must be another member");
        }
        Integer sourceCount = db.queryForObject("select count(*) from order_sources where order_id=?", Integer.class, id);
        if (sourceCount == null || sourceCount == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Attach the original order file before review");
        }
        if (before.lines().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Confirm at least one order line before review");
        }
        Integer validReviewer = db.queryForObject("select count(*) from members where id=? and organization_id=? and active=true and must_change_password=false",
                Integer.class, input.reviewerId(), actor.organizationId());
        if (validReviewer == null || validReviewer != 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Reviewer is unavailable");
        }
        int changed = db.update("update orders set status='IN_REVIEW',reviewer_id=?,revision=revision+1 where id=? and organization_id=? and revision=?",
                input.reviewerId(), id, actor.organizationId(), input.expectedRevision());
        if (changed != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "Order changed; refresh first");
        db.update("delete from order_access where order_id=? and access_role='REVIEWER'", id);
        int promoted = db.update("update order_access set access_role='REVIEWER' where order_id=? and member_id=? and access_role='VIEWER'",
                id, input.reviewerId());
        if (promoted == 0) {
            db.update("insert into order_access(order_id,member_id,access_role) values (?,?,?)", id, input.reviewerId(), "REVIEWER");
        }
        event(actor, id, "SUBMITTED", null);
        members.audit(actor, "order.review.submitted", id);
        return order(actor, id);
    }

    @GetMapping("/reviews/inbox")
    public List<OrderSummary> inbox(@RequestHeader(value="Authorization", required=false) String bearer) {
        var actor = ready(bearer);
        return db.query("""
                select id,customer_name,source_type,status,creator_id,reviewer_id,revision,created_at
                from orders where organization_id=? and reviewer_id=? and status='IN_REVIEW'
                order by created_at desc
                """, (rs, n) -> new OrderSummary(rs.getString("id"), rs.getString("customer_name"),
                rs.getString("source_type"), rs.getString("status"), rs.getString("creator_id"),
                rs.getString("reviewer_id"), rs.getInt("revision"), rs.getTimestamp("created_at").toInstant()),
                actor.organizationId(), actor.id());
    }

    @PostMapping("/orders/{id}/review")
    @Transactional
    public Order decide(@RequestHeader(value="Authorization", required=false) String bearer,
                        @PathVariable String id, @RequestBody ReviewDecision input) {
        var actor = ready(bearer);
        Order before = order(actor, id);
        if (!actor.id().equals(before.reviewerId()) || !before.status().equals("IN_REVIEW")) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Not the active reviewer");
        }
        if (!List.of("APPROVED", "CHANGES_REQUESTED").contains(input.decision())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid decision");
        }
        if (input.decision().equals("CHANGES_REQUESTED") && (input.comment() == null || input.comment().isBlank())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A return reason is required");
        }
        if (input.decision().equals("APPROVED") && before.lines().stream().anyMatch(line -> line.internalSku() == null)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Every order line needs a confirmed inventory SKU");
        }
        int changed = db.update("update orders set status=?,revision=revision+1 where id=? and organization_id=? and revision=?",
                input.decision(), id, actor.organizationId(), input.expectedRevision());
        if (changed != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "Order changed; refresh first");
        event(actor, id, input.decision(), input.comment());
        members.audit(actor, "order.review." + input.decision().toLowerCase(), id);
        return order(actor, id);
    }

    @GetMapping("/admin/orders")
    public List<OrderSummary> adminOrders(@RequestHeader(value="Authorization", required=false) String bearer) {
        var actor = auth.actor(bearer);
        auth.requireReady(actor);
        auth.requireAdmin(actor);
        return db.query("""
                select id,customer_name,source_type,status,creator_id,reviewer_id,revision,created_at
                from orders where organization_id=? order by created_at desc
                """, (rs, n) -> new OrderSummary(rs.getString("id"), rs.getString("customer_name"),
                rs.getString("source_type"), rs.getString("status"), rs.getString("creator_id"),
                rs.getString("reviewer_id"), rs.getInt("revision"), rs.getTimestamp("created_at").toInstant()),
                actor.organizationId());
    }

    @PostMapping("/admin/orders/{id}/access")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void grant(@RequestHeader(value="Authorization", required=false) String bearer,
                      @PathVariable String id, @RequestBody OrderAccess grant) {
        var actor = auth.actor(bearer);
        auth.requireReady(actor);
        auth.requireAdmin(actor);
        Integer orderCount = db.queryForObject("select count(*) from orders where id=? and organization_id=?",
                Integer.class, id, actor.organizationId());
        Integer memberCount = db.queryForObject("select count(*) from members where id=? and organization_id=? and active=true",
                Integer.class, grant.memberId(), actor.organizationId());
        if (orderCount == null || orderCount != 1 || memberCount == null || memberCount != 1) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order or member not found");
        }
        if (!"VIEWER".equals(grant.accessRole())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid access role");
        Integer existing = db.queryForObject("select count(*) from order_access where order_id=? and member_id=?",
                Integer.class, id, grant.memberId());
        if (existing == null || existing == 0) {
            db.update("insert into order_access(order_id,member_id,access_role) values (?,?,?)", id, grant.memberId(), "VIEWER");
        }
        members.audit(actor, "order.access.granted", id);
    }

    private AuthService.Actor ready(String bearer) {
        var actor = auth.businessActor(bearer);
        auth.requireReady(actor);
        return actor;
    }

    private Order order(AuthService.Actor actor, String id) {
        var found = db.query("""
                select o.id,o.customer_name,o.source_type,o.source_name,o.status,o.creator_id,o.reviewer_id,o.revision,o.created_at
                from orders o join order_access a on a.order_id=o.id
                where o.id=? and o.organization_id=? and a.member_id=?
                """, (rs, n) -> new Order(rs.getString("id"), rs.getString("customer_name"),
                rs.getString("source_type"), rs.getString("source_name"), rs.getString("status"),
                rs.getString("creator_id"), rs.getString("reviewer_id"), rs.getInt("revision"),
                rs.getTimestamp("created_at").toInstant(), List.of()), id, actor.organizationId(), actor.id());
        if (found.isEmpty()) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found");
        Order o = found.get(0);
        var lines = db.query("select id,customer_sku,customer_reference,customer_name,quantity,internal_sku,match_status,source_id,source_locator from order_lines where order_id=? order by customer_sku,id",
                (rs, n) -> new Line(rs.getString("id"), rs.getString("customer_sku"), rs.getString("customer_reference"),
                        rs.getString("customer_name"), rs.getInt("quantity"),
                        rs.getString("internal_sku"), rs.getString("match_status"),
                        rs.getString("source_id"), rs.getString("source_locator")), id);
        return new Order(o.id(), o.customerName(), o.sourceType(), o.sourceName(), o.status(),
                o.creatorId(), o.reviewerId(), o.revision(), o.createdAt(), lines);
    }

    private void event(AuthService.Actor actor, String id, String action, String comment) {
        db.update("insert into review_events(id,order_id,actor_id,action,comment,created_at) values (?,?,?,?,?,?)",
                UUID.randomUUID().toString(), id, actor.id(), action, comment, Timestamp.from(Instant.now()));
    }

    private static String normalizeSku(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String textOrEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String safeFileName(String raw) {
        String name = raw == null ? "" : raw.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").trim();
        if (name.isEmpty() || name.length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid source filename");
        }
        return name;
    }

    private static String sourceType(String name, byte[] bytes) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".pdf") && starts(bytes, new byte[]{'%', 'P', 'D', 'F', '-'})) return "PDF";
        if (lower.endsWith(".png") && starts(bytes, new byte[]{(byte) 0x89, 'P', 'N', 'G', 13, 10, 26, 10})) return "SCREENSHOT";
        if ((lower.endsWith(".jpg") || lower.endsWith(".jpeg")) && starts(bytes, new byte[]{(byte) 0xff, (byte) 0xd8, (byte) 0xff})) return "SCREENSHOT";
        if (lower.endsWith(".xlsx") && starts(bytes, new byte[]{'P', 'K', 3, 4})) return "EXCEL";
        if (lower.endsWith(".xls") && starts(bytes, new byte[]{(byte) 0xd0, (byte) 0xcf, 0x11, (byte) 0xe0})) return "EXCEL";
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Only PNG, JPEG, PDF, XLS or XLSX order files are accepted");
    }

    private static boolean starts(byte[] content, byte[] signature) {
        if (content.length < signature.length) return false;
        for (int i = 0; i < signature.length; i++) if (content[i] != signature[i]) return false;
        return true;
    }

    private static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
