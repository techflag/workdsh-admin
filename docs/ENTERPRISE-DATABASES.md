# 企业数据库

企业服务器复用同一Java组织数据库；支持显式MySQL、PostgreSQL或测试SQLite配置。后台组织、成员、协作、授权、KV单元和原生会话日志共享数据库，但每次操作按真实组织/成员授权。数据库连接角色本身不提供成员隔离。个人单机存储不受企业组合影响。

SQLite：`SPRING_PROFILES_ACTIVE=enterprise-sqlite`，默认 `jdbc:sqlite:./data/workdsh-enterprise.sqlite`。新开发验收库使用当前schema-sqlite.sql（后台包deploy/sqlite/schema.sql），foreign_keys开启、单JDBC连接。MySQL：`enterprise-mysql`，显式提供JDBC_URL/JDBC_USER/JDBC_PASSWORD；维护账号初始化当前schema-mysql.sql（后台包deploy/mysql/schema.sql），应用禁止自动DDL。PostgreSQL：`enterprise-postgres`，由维护账号执行deploy/postgres/migrations，再授予独立运行角色权限；应用禁止自动DDL，见deploy/postgres/README.md。

当前开发态只初始化新验收库，不兼容迁移旧测试账号、旧单模型数据、Runtime专属授权或订单。旧库不被自动重置或搬迁；不能把新schema脚本直接用于现有数据库。V006补齐当前多模型配置、内部Key与官方transport签名存储；V007增加独立Desktop可见正文/回执/墓碑表。

member_secrets与member_secret_records按组织/成员/引用或record key隔离；使用AES-256-GCM并绑定归属作为AAD，32字节随机密钥Base64通过WORKDSH_SECRETS_ENCRYPTION_KEY提供。服务间接口还要求WORKDSH_SECRETS_SERVICE_KEY与有效成员Bearer，不接受目标成员ID；key不下发浏览器。记录更新采用CAS，删除保留修订墓碑。

桌面原生会话与工作区保存在员工设备。后台只存储账号授权、协作数据、模型配置及已授权同步的可见正文，不承担 Agent 运行存储。

member_desktop_body_sessions/records/receipts保存成员Bearer正文接收的归属、追加正文与幂等回执。它们与正式Web原生日志表分离；删除正文保留revision/摘要墓碑。详见[接口契约](DESKTOP-VISIBLE-SESSIONS-API.md)。

多模型配置保存在organization_model_providers，供应商Key仅服务器解密使用；organization_model_gateway保存内部Key摘要，另有加密副本供管理员主动查看并审计。协议归各接口，不保留单模型配置表或读回退。server_transport_records保存加密签名状态，不以组织模型Key作为成员身份。

本轮SQLite/H2后端授权、CAS、会话审计、协作及模型网关测试实际执行。MySQL条件测试和跨仓联合测试需显式配置环境；跳过不等于通过。当前迁移链另在H2 PostgreSQL模式执行校验并覆盖关键模型/transport列，这不能替代真实PostgreSQL版本/角色/TLS验收。目标ECS的数据库部署、备份与恢复仍需完整测试。
