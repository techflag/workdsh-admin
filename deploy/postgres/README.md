# PostgreSQL 新开发部署

通过enterprise-postgres显式启用，提供JDBC_URL/JDBC_USER/JDBC_PASSWORD。该Profile关闭自动SQL初始化与演示简单登录，缺数据库配置时启动失败。

由维护账号配置PGHOST/PGPORT/PGDATABASE/PGUSER及受限PGPASSFILE后执行python3 scripts/migrate-postgres.py。当前迁移链只用于新开发验收库，包括组织/协作、成员授权/KV/原生日志、V006多模型与transport存储及V007独立Desktop正文/回执/墓碑；不导入旧订单、旧Runtime凭据或旧模型配置。已有无版本库不会被自动接管，当前代码也不负责迁移旧测试数据。

维护步骤创建独立workdsh_app角色（NOSUPERUSER、NOCREATEDB、NOCREATEROLE），使用受控secret设置密码，再执行runtime-grants.sql。应用只获得现有业务表SELECT/INSERT/UPDATE/DELETE和序列使用权限，不能修改维护拥有的版本证据或创建schema。配置及密钥放包外，不写入源码、浏览器或启动参数。

跨机器连接使用jdbc:postgresql://<certificate-host>:5432/<database>?sslmode=verify-full&sslrootcert=/run/secrets/postgres-ca.crt，证书SAN和主机名一致。数据库服务器证书、访问规则、备份与恢复由目标部署配置；本轮H2 PostgreSQL模式迁移测试不代替真实PostgreSQL、最小运行角色和TLS验收。
