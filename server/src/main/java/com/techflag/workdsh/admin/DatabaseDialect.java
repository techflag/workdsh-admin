package com.techflag.workdsh.admin;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
/** SQLite uses its single writer transaction; server databases use row locks. */
@Component
public class DatabaseDialect {
 private final boolean sqlite;private final String url;
 public DatabaseDialect(@Value("${spring.datasource.url}") String url){this.url=url;sqlite=url.startsWith("jdbc:sqlite:");}
 public String lock(String sql){return sqlite?sql:sql+" for update";}
 public String initializeTransportRecord(){
  String insert="insert into server_transport_records(record_key,revision,encrypted_value) values (?,0,'')";
  if(url.startsWith("jdbc:mysql:"))return insert+" on duplicate key update record_key=record_key";
  if(url.startsWith("jdbc:h2:"))return "merge into server_transport_records t using (values (?)) s(k) on t.record_key=s.k when not matched then insert(record_key,revision,encrypted_value) values(s.k,0,'')";
  return insert+" on conflict(record_key) do nothing";
 }
 public String initializeStorageUnit(){
  String insert="insert into member_storage_units(organization_id,member_id,unit_name,format_version,descriptor,payload) values (?,?,?,?,?,?)";
  if(url.startsWith("jdbc:mysql:"))return insert+" on duplicate key update unit_name=unit_name";
  if(url.startsWith("jdbc:h2:"))return "merge into member_storage_units t using (values (?,?,?,?,?,?)) s(o,m,n,v,d,p) on t.organization_id=s.o and t.member_id=s.m and t.unit_name=s.n when not matched then insert (organization_id,member_id,unit_name,format_version,descriptor,payload) values(s.o,s.m,s.n,s.v,s.d,s.p)";
  return insert+" on conflict(organization_id,member_id,unit_name) do nothing";
 }
 public String upsertSecret(){
  String insert="insert into member_secrets(organization_id,member_id,credential_ref,encrypted_value) values (?,?,?,?)";
  if(url.startsWith("jdbc:mysql:"))return insert+" on duplicate key update encrypted_value=values(encrypted_value)";
  if(url.startsWith("jdbc:h2:"))return "merge into member_secrets(organization_id,member_id,credential_ref,encrypted_value) key(organization_id,member_id,credential_ref) values (?,?,?,?)";
  return insert+" on conflict(organization_id,member_id,credential_ref) do update set encrypted_value=excluded.encrypted_value";
 }
}
