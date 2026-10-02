package com.techflag.workdsh.admin;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.FileSystemResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import static org.junit.jupiter.api.Assertions.*;
class SchemaDeliveryTest {
 @Test void deliveredPostgresMigrationChainContainsCurrentModelAndTransportStorage() throws Exception {
  var source=new DriverManagerDataSource("jdbc:h2:mem:delivery-schema;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1","sa","");
  try(var connection=source.getConnection();var paths=Files.list(Path.of("../deploy/postgres/migrations"))){
   for(var path:paths.filter(p->p.toString().endsWith(".sql")).sorted().toList())ScriptUtils.executeSqlScript(connection,new FileSystemResource(path));
  }
  var db=new JdbcTemplate(source);Set<String> expected=new TreeSet<>();var matcher=Pattern.compile("create table if not exists ([a-z_]+)").matcher(Files.readString(Path.of("src/main/resources/schema.sql")));while(matcher.find())expected.add(matcher.group(1));
  assertEquals(expected,new TreeSet<>(db.queryForList("select table_name from information_schema.tables where table_schema='public'",String.class)));
  db.update("insert into organization_model_providers values (?,?,?)","fixture-org",1,"[]");db.update("insert into organization_model_gateway values (?,?)","fixture-org","fixture-hash");db.update("insert into organization_model_gateway_keys values (?,?)","fixture-org","fixture-encrypted-key");db.update("insert into server_transport_records values (?,?,?)","fixture-record",1,"fixture-encrypted-value");
  assertEquals("[]",db.queryForObject("select providers_json from organization_model_providers where organization_id=?",String.class,"fixture-org"));assertEquals("fixture-hash",db.queryForObject("select access_hash from organization_model_gateway where organization_id=?",String.class,"fixture-org"));assertEquals(1L,db.queryForObject("select revision from server_transport_records where record_key=?",Long.class,"fixture-record"));
 }
}
