# Docker 源码职责

`Admin.Dockerfile` 构建 Java 管理服务及管理前端，须显式固定 `JAVA_IMAGE`。`AdminFront.Dockerfile` 与 `admin-loopback-front.mjs` 是可选本机 HTTP 前门；后台保持 HTTPS 证书验证，不能作为公网 TLS 模板。

`AdminBuild.Dockerfile` 是多阶段源码构建配方（Maven 编译 JAR、Node 构建 `web/dist`），供 docker compose 使用，与 `Admin.Dockerfile` 的运行时约定一致；它不替代候选交付的预构建产物镜像。

Agent、工具、文件和本机会话在员工 Desktop 运行。后台部署参数与验证见 [后台交付](../ADMIN-SERVER-CANDIDATE.md)。

## 用 docker compose 启动

默认栈（H2 文件库，单容器，适合本地开发与验收）：

```sh
cp .env.example .env   # 填写管理员账号、两个密钥；改端口时同步改 WORKDSH_ADMIN_WEB_ORIGIN
docker compose up --build -d
```

浏览器访问 `http://127.0.0.1:18890/`。首次启动必须提供 `WORKDSH_BOOTSTRAP_ADMIN_EMAIL` 与至少 12 位的 `WORKDSH_BOOTSTRAP_ADMIN_PASSWORD`；`organizations` 表非空后该引导不再执行。H2 数据保存在命名卷 `workdsh-state`。

PostgreSQL 验收栈（叠加 override）：

```sh
docker compose -f docker-compose.yml -f docker-compose.postgres.yml up --build -d
```

首次初始化由 `deploy/postgres/docker/init-schema.sh` 完成：创建最小权限角色 `workdsh_app`、按 `deploy/postgres/migrations` 应用 V001–V007 并写入 `workdsh_schema_history`（含 SHA256 校验）、执行 `runtime-grants.sql`。应用使用 `workdsh_app` 连接，不使用维护账号，且不自动 DDL。初始化只发生在 `workdsh-pgdata` 卷为空时；使用同样的 `-f` 参数执行 `docker compose -f docker-compose.yml -f docker-compose.postgres.yml down -v` 会删除全部数据并允许重新初始化。

## 部署注意

- 生产环境必须把 `JAVA_BUILD_IMAGE`、`NODE_IMAGE`、`JAVA_IMAGE`（以及叠加栈的 `POSTGRES_IMAGE`）固定为 digest；默认 tag 只用于本地验收。
- compose 默认仅绑定 `127.0.0.1`，且不终止 TLS，不能直接作为公网入口。对外暴露需自行配置反向代理、证书与访问控制；Desktop 成员 Bearer API 必须走受信任的 HTTPS 入口。
- 凭据只放在 `.env` 或服务器 secret 中，不下发浏览器、不写入镜像。`.env` 已在 `.gitignore` 内。
- `WORKDSH_ADMIN_WEB_ORIGIN` 必须与浏览器实际访问地址完全一致，否则登录等写请求会被 Origin 校验拒绝。
- 切换数据库后需重新初始化：PostgreSQL 迁移链只用于新验收库，不接管已有未版本化数据库，也不迁移旧测试数据。
