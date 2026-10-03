# 当前组织持久化检查

`PersistentOrganizationRestartTest` 使用独立临时 H2 file、真实 Spring/Tomcat loopback HTTP，创建组织成员/部门/协作资料后关闭应用与连接，再启动新的应用验证数据与授权。检查共享文件字节保持、非参与管理员拒绝、策略与停用成员登录拒绝。该测试通过。

这证明本地正常停止后的应用/连接重建，不等于机器故障、在线备份、独立生产数据库、TLS 或成员 DSH 私有会话恢复。旧独立双 Host 发行探针已删除。

当前 PostgreSQL 新库 SQL 链为 V001–V006，`SchemaDeliveryTest` 在 H2 PostgreSQL 模式验证交付 schema 一致性；实际 PostgreSQL 角色/TLS/备份恢复需要目标环境实测。MySQL 与 SQLite 使用各自配置及 schema，详见 [数据库配置](ENTERPRISE-DATABASES.md)。成员文件/会话存储和组织库需要分别备份与验收。
