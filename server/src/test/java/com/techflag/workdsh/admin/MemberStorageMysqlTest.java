package com.techflag.workdsh.admin;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
@SpringBootTest(properties={"spring.datasource.url=${WORKDSH_TEST_MYSQL_URL}","spring.datasource.username=root","spring.datasource.password=${WORKDSH_TEST_MYSQL_PASSWORD}","workdsh.bootstrap.admin-email=sqlite-test@example.test","workdsh.bootstrap.admin-password=SqliteTestPassword123!","workdsh.secrets.service-key=fixture-service-key-at-least-32-characters"})
@ActiveProfiles(value="enterprise-mysql",inheritProfiles=false)
@EnabledIfEnvironmentVariable(named="WORKDSH_TEST_MYSQL_URL",matches=".+")
class MemberStorageMysqlTest extends MemberStorageTest {}
