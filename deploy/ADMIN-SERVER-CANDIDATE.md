# WorkDSH 管理后台候选交付

这是后台 JAR、管理 Web、数据库维护脚本和登录/账户存储隔离资产的封闭候选，不包含成员 DSH 进程、官方 runtime、企业插件安装、账号、密钥、证书或业务数据。

构建 `mvn -f server/pom.xml clean test package` 与 `npm --prefix web run build` 后，使用 `python3 scripts/pack-admin-release.py --output /absolute/new/admin.zip`。安装使用 `python3 scripts/install-admin-release.py ARCHIVE NEW_VERSION_DIRECTORY`；已有目录不会被覆盖，文件数/类型/字节数/SHA256必须与清单一致，不自动启动服务。

在安装目录执行 `docker build -f deploy/docker/Admin.Dockerfile --build-arg JAVA_IMAGE=<approved-image>@sha256:<digest> -t <candidate-image> .`。Java镜像必须显式固定。运行配置通过受限 env-file 或服务器 secret 提供；设置固定 `PORT`、`WORKDSH_ADMIN_WEB_ORIGIN`、数据库 Profile/JDBC 和授权加密/服务密钥。后台 `/api/auth/me` 未登录返回401可用于就绪检查，管理员入口使用后台内附静态 Web。数据库 schema 只初始化新开发验收库，不移植旧测试账号、旧模型数据或个人目录。

包包含 PostgreSQL V001–V007 新开发建库链与 包内 `docs/DESKTOP-VISIBLE-SESSIONS-API.md` 接口契约，V007 为独立正文/回执/墓碑表；不导入或兼容旧测试数据。Desktop 登录及 `/api/member/visible-sessions` 必须使用真实 Admin 服务的受信任 HTTPS 入口，该入口保留显式成员 Authorization Bearer。该正文 API 不接受管理 Cookie 或服务器 service key。

可选 `AdminFront.Dockerfile` 只用于本机loopback验收：固定 `WORKDSH_ADMIN_FRONT_ORIGIN=http://127.0.0.1:<port>`、HTTPS `WORKDSH_ADMIN_BACKEND` 和后台配置的 `WORKDSH_ADMIN_BACKEND_ORIGIN`，配置信任CA，不关闭TLS验证。管理请求只采用管理员Cookie并重写可信Origin；内部模型API单独保留Bearer/x-api-key且去掉浏览器Cookie。公网入口需另行配置真实TLS与反向代理，这个HTTP前门不能作为ECS公网模板，也不能作为 Desktop 成员 Bearer API 入口；它只保留管理 Cookie 的策略不在本轮改变。

成员进程交付只有 WorkDSH 主仓库的 `deploy/member-process/prepare-context.mjs` / Dockerfile。本仓库的 `deploy/docker/user-entry.html`、`shared-entry-client.js` 和 `account-storage-fence.mjs` 由该入口组装；不存在第二份启动器或成员网关。单ECS按账号固定官方DSH进程，Java后台独立；个人/企业共同功能包和官方Web来源保持一致。

供应商凭据在管理员页面配置，成员在官方自定义模型API中手动填内部URL/Key/协议/模型ID。镜像构建和ZIP只证明源码可收录，不代替实际Linux构建、启动、撤权、文件/执行隔离、模型流式对话、恢复与ECS部署验收。

制品检查：`python3 scripts/test-admin-packaged.py --java /absolute/path/to/java --release-root /absolute/new/version` 使用临时 H2/loopback 与虚构账号检查静态页面、登录退出及退役路由；不发模型请求，不修改已运行组织库。
