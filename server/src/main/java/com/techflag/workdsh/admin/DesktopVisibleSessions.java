package com.techflag.workdsh.admin;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/** Untrusted Desktop submissions: append-only visible text, separate from authoritative Web sessions. */
@Service
public class DesktopVisibleSessions {
    static final int MAX_REQUEST_BYTES=524288, MAX_RECORD_BYTES=65536, MAX_TEXT_BYTES=262144, MAX_RECORDS=512;
    private static final Set<String> INPUT_FIELDS=Set.of("version","deviceId","sessionId","revision","requestId","entries");
    private static final Set<String> ENTRY_FIELDS=Set.of("seq","recordId","role","text");
    private static final Pattern CREDENTIAL=Pattern.compile("-----BEGIN (?:RSA |EC |OPENSSH |ENCRYPTED )?PRIVATE KEY-----|\\bsk-[A-Za-z0-9_-]{20,}|\\bgh[pousr]_[A-Za-z0-9]{20,}|\\bgithub_pat_[A-Za-z0-9_]{30,}|\\bAKIA[A-Z0-9]{16}\\b|\\beyJ[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{20,}\\.[A-Za-z0-9_-]{20,}\\b|(?i:Bearer [A-Za-z0-9._-]{20,})");
    public record Entry(long seq,String recordId,String role,String text) {}
    public record Submission(int version,String deviceId,String sessionId,long revision,String requestId,List<Entry> entries) {}
    public record Receipt(int version,String sessionId,String deviceId,String clientSessionId,long revision,long nextRevision,long recordCount,boolean deleted) {}
    private record Stored(String device,String serverId,long revision,long count,long deletedAt) {}
    private final JdbcTemplate db; private final AuthService auth; private final DatabaseDialect dialect; private final ObjectMapper json;
    public DesktopVisibleSessions(JdbcTemplate db,AuthService auth,DatabaseDialect dialect,ObjectMapper json){this.db=db;this.auth=auth;this.dialect=dialect;this.json=json;}
    private static ResponseStatusException error(HttpStatus code,String reason){return new ResponseStatusException(code,reason);}
    private static void fields(JsonNode node,Set<String> expected){
        if(node==null||!node.isObject())throw error(HttpStatus.BAD_REQUEST,"Expected a visible-text object");
        Set<String> actual=new HashSet<>();node.fieldNames().forEachRemaining(actual::add);
        if(!actual.equals(expected))throw error(HttpStatus.BAD_REQUEST,"Unexpected or missing visible-text fields");
    }
    private static String identifier(JsonNode value){if(value==null||!value.isTextual())throw error(HttpStatus.BAD_REQUEST,"Invalid identifier");return identifier(value.textValue());}
    private static String identifier(String value){if(value==null||!value.matches("[A-Za-z0-9_-]{1,128}"))throw error(HttpStatus.BAD_REQUEST,"Invalid identifier");return value;}
    private static long integer(JsonNode node){if(node==null||!node.isIntegralNumber()||!node.canConvertToLong())throw error(HttpStatus.BAD_REQUEST,"Invalid sequence or revision");return node.longValue();}
    Submission parse(JsonNode input){
        fields(input,INPUT_FIELDS);
        if(integer(input.get("version"))!=1)throw error(HttpStatus.BAD_REQUEST,"Unsupported visible-text version");
        long revision=integer(input.get("revision"));if(revision<1||revision==Long.MAX_VALUE)throw error(HttpStatus.BAD_REQUEST,"Invalid revision");
        JsonNode items=input.get("entries");if(!items.isArray()||items.isEmpty())throw error(HttpStatus.BAD_REQUEST,"A nonempty visible-text snapshot is required");
        if(items.size()>MAX_RECORDS)throw error(HttpStatus.PAYLOAD_TOO_LARGE,"Visible-text snapshot limit exceeded");
        List<Entry> entries=new ArrayList<>();Set<String> ids=new HashSet<>();long total=0;
        for(JsonNode item:items){
            fields(item,ENTRY_FIELDS);long seq=integer(item.get("seq"));String id=identifier(item.get("recordId"));
            if(seq!=entries.size()||!ids.add(id))throw error(HttpStatus.BAD_REQUEST,"Records must have consecutive sequences and distinct IDs");
            JsonNode role=item.get("role"),text=item.get("text");
            if(!role.isTextual()||!Set.of("user","assistant").contains(role.textValue())||!text.isTextual()||text.textValue().isEmpty())throw error(HttpStatus.BAD_REQUEST,"Only visible user and assistant text is accepted");
            int bytes=text.textValue().getBytes(StandardCharsets.UTF_8).length;total+=bytes;
            if(bytes>MAX_RECORD_BYTES||total>MAX_TEXT_BYTES)throw error(HttpStatus.PAYLOAD_TOO_LARGE,"Visible-text snapshot limit exceeded");
            if(CREDENTIAL.matcher(text.textValue()).find())throw error(HttpStatus.BAD_REQUEST,"Visible text contains disallowed credential material");
            entries.add(new Entry(seq,id,role.textValue(),text.textValue()));
        }
        return new Submission(1,identifier(input.get("deviceId")),identifier(input.get("sessionId")),revision,identifier(input.get("requestId")),List.copyOf(entries));
    }
    private AuthService.Actor actor(String bearer){var a=auth.actor(bearer);auth.requireReady(a);return a;}
    private AuthService.Actor mutationActor(String bearer){
        var a=actor(bearer);db.queryForObject(dialect.lock("select id from members where organization_id=? and id=?"),String.class,a.organizationId(),a.id());
        String session=auth.authenticatedSessionKey(bearer);
        if(db.query(dialect.lock("select token_hash from auth_sessions where token_hash=?"),(rs,n)->rs.getString(1),session).isEmpty())throw error(HttpStatus.UNAUTHORIZED,"Authentication required");
        return actor(bearer);
    }
    // Locking reads also see the latest committed state under MySQL REPEATABLE_READ.
    private Stored find(AuthService.Actor a,String id,boolean lock){String sql="select device_id,server_session_id,revision,record_count,deleted_at from member_desktop_body_sessions where organization_id=? and member_id=? and client_session_id=?";return db.query(lock?dialect.lock(sql):sql,(rs,n)->new Stored(rs.getString(1),rs.getString(2),rs.getLong(3),rs.getLong(4),rs.getLong(5)),a.organizationId(),a.id(),id).stream().findFirst().orElse(null);}
    private static void device(Stored stored,String expected){if(!stored.device().equals(expected))throw error(HttpStatus.CONFLICT,"Desktop session belongs to another device");}
    private static Receipt receipt(Stored s,String local){return new Receipt(1,s.serverId(),s.device(),local,s.revision(),s.revision()+1,s.count(),s.deletedAt()!=0);}
    private static String digest(String value){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));}catch(Exception e){throw new IllegalStateException(e);}}
    @Transactional
    public Receipt ingest(String bearer,JsonNode input){
        var a=mutationActor(bearer);var payload=parse(input);var s=find(a,payload.sessionId(),true);
        if(s==null){
            if(payload.revision()!=1)throw error(HttpStatus.CONFLICT,"Desktop revision must start at one");
            String serverId="desktop-body:"+digest(a.organizationId()+":"+a.id()+":"+payload.sessionId());
            db.update("insert into member_desktop_body_sessions(organization_id,member_id,client_session_id,device_id,server_session_id,created_at,revision,record_count,deleted_at) values (?,?,?,?,?,?,0,0,0)",a.organizationId(),a.id(),payload.sessionId(),payload.deviceId(),serverId,System.currentTimeMillis());
            s=find(a,payload.sessionId(),true);
        }
        device(s,payload.deviceId());if(s.deletedAt()!=0)throw error(HttpStatus.GONE,"Desktop body session was deleted");
        String hash;try{hash=digest(json.writeValueAsString(payload));}catch(Exception e){throw new IllegalStateException(e);}
        var previous=db.query(dialect.lock("select revision,payload_hash,record_count from member_desktop_body_receipts where organization_id=? and member_id=? and client_session_id=? and request_id=?"),(rs,n)->new Object[]{rs.getLong(1),rs.getString(2),rs.getLong(3)},a.organizationId(),a.id(),payload.sessionId(),payload.requestId());
        if(!previous.isEmpty()){
            Object[] p=previous.get(0);if((long)p[0]!=payload.revision()||!p[1].equals(hash))throw error(HttpStatus.CONFLICT,"Desktop request ID was reused with different content");
            return new Receipt(1,s.serverId(),s.device(),payload.sessionId(),(long)p[0],(long)p[0]+1,(long)p[2],false);
        }
        if(payload.revision()!=s.revision()+1)throw error(HttpStatus.CONFLICT,"Desktop revision is out of order");
        var old=db.query(dialect.lock("select record_seq,record_id,role,body_text from member_desktop_body_records where organization_id=? and member_id=? and client_session_id=? order by record_seq"),(rs,n)->new Entry(rs.getLong(1),rs.getString(2),rs.getString(3),rs.getString(4)),a.organizationId(),a.id(),payload.sessionId());
        if(payload.entries().size()<=old.size())throw error(HttpStatus.CONFLICT,"Snapshot must append new visible records");
        if(!payload.entries().subList(0,old.size()).equals(old))throw error(HttpStatus.CONFLICT,"Accepted visible text is immutable");
        long received=System.currentTimeMillis();
        for(var e:payload.entries().subList(old.size(),payload.entries().size()))db.update("insert into member_desktop_body_records(organization_id,member_id,client_session_id,record_seq,record_id,role,body_text,received_at) values (?,?,?,?,?,?,?,?)",a.organizationId(),a.id(),payload.sessionId(),e.seq(),e.recordId(),e.role(),e.text(),received);
        long count=payload.entries().size();db.update("update member_desktop_body_sessions set revision=?,record_count=? where organization_id=? and member_id=? and client_session_id=?",payload.revision(),count,a.organizationId(),a.id(),payload.sessionId());
        db.update("insert into member_desktop_body_receipts(organization_id,member_id,client_session_id,request_id,revision,payload_hash,record_count) values (?,?,?,?,?,?,?)",a.organizationId(),a.id(),payload.sessionId(),payload.requestId(),payload.revision(),hash,count);
        return receipt(new Stored(s.device(),s.serverId(),payload.revision(),count,0),payload.sessionId());
    }
    public Receipt status(String bearer,String deviceId,String sessionId){var a=actor(bearer);identifier(deviceId);identifier(sessionId);var s=find(a,sessionId,false);if(s==null)throw error(HttpStatus.NOT_FOUND,"Desktop body session not found");device(s,deviceId);return receipt(s,sessionId);}
    @Transactional
    public Receipt delete(String bearer,String deviceId,String sessionId){
        var a=mutationActor(bearer);identifier(deviceId);identifier(sessionId);var s=find(a,sessionId,true);if(s==null)throw error(HttpStatus.NOT_FOUND,"Desktop body session not found");device(s,deviceId);
        if(s.deletedAt()==0){db.update("delete from member_desktop_body_records where organization_id=? and member_id=? and client_session_id=?",a.organizationId(),a.id(),sessionId);db.update("update member_desktop_body_sessions set record_count=0,deleted_at=? where organization_id=? and member_id=? and client_session_id=?",System.currentTimeMillis(),a.organizationId(),a.id(),sessionId);}
        return receipt(new Stored(s.device(),s.serverId(),s.revision(),0,1),sessionId);
    }
}
