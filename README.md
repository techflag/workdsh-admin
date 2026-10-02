# WorkDSH Admin

企业管理后台与内部模型 API，使用 Spring Boot / Spring AI、Vue 3 / Arco。当前处于开发验收阶段。管理后台拥有组织、成员、部门、角色权限、协作记录、成员数据库存储、成员会话只读审计和公司模型接口；官方 DSH 负责模型选择、会话和 Agent 执行。

## 运行与交付边界

企业部署采用单 ECS、一个成员服务器容器，按已认证账号启动一份官方 DSH 原生进程。相同账号的连接复用该进程，各成员有固定身份、Linux UID 和独立文件空间。后台 Java 服务独立运行。主仓库 `workdsh/deploy/member-process` 是启动器、成员网关、数据库会话桥接与 Docker 配方的唯一源码，本仓库只提供后台和登录页/浏览器账户存储隔离资产。

产品官方核心和版本族以 WorkDSH 兼容验证目标为准，当前目标为 `0.2.0-rc.2`；不能把后台重建当成个人、企业与 Desktop 全部升级。使用同一锁定官方完整 Web 与共同功能包，企业身份、存储、授权为显式组合差异，不复制官方 Host、Client 或设置页面，不修改官方源码。企业身份与协作等外置插件独立交付，不默认捆绑到个人或 Desktop。

只保留当前按账号进程路线。订单、审核和旧单模型 API 已删除；旧 Runtime 专属凭据、启动票据网关与共享 Host 发布路线已退役。业务 API 统一验证现有成员登录 Cookie/Bearer，并检查成员状态、组织归属与首次改密；普通成员不能访问管理 API。管理员按授权只读查看本组织会话正文并记录审计。服务器插件页只读展示收到的实际运行观察；当前成员配方尚未接通上报，未取得清单时显示不可用。安装与更新由服务器运维通过官方 CLI 执行。

## 本地开发

需要 Java 17+、Maven 3.9+、Node 22.19+ 或 24+。首次启动通过受控环境设置 `WORKDSH_BOOTSTRAP_ADMIN_EMAIL` 和至少 12 位的 `WORKDSH_BOOTSTRAP_ADMIN_PASSWORD`。不要把实际凭据写入命令历史、日志或源码。

```sh
mvn -f server/pom.xml spring-boot:run
```

另一个终端：

```sh
cd web
npm ci
npm run dev
```

管理网页默认 `http://127.0.0.1:18891/`，Java 默认 `127.0.0.1:18890`。默认开发数据库为 `server/data` 下的 H2。企业服务器显式选择 `enterprise-mysql`、`enterprise-postgres` 或测试用 `enterprise-sqlite`；见 [数据库配置](docs/ENTERPRISE-DATABASES.md)。开发态旧测试数据库与 Profile 不做兼容迁移；使用当前新库 schema 初始化独立验收环境。不要对已运行数据库直接重跑新库 schema。

后台私密存储需要 `WORKDSH_SECRETS_ENCRYPTION_KEY`（32 字节随机密钥的 Base64）和服务器间 `WORKDSH_SECRETS_SERVICE_KEY`。凭据在包外管理，不下发浏览器。登录只使用实际成员密码，没有简单测试账号替换逻辑。

## 公司内部模型 API

管理员在「企业模型 API」配置多个接口：上游 HTTPS 地址、供应商 API Key、OpenAI Chat Completions 或 Anthropic Messages 协议、启用状态及允许的模型目录；另设置至少 32 位的内部访问 Key。供应商密钥加密保存、仅供服务器请求上游；管理员主动查看已保存 Key 使用 no-store 响应并记录审计。

成员在官方 DSH 设置的「自定义模型 API」中手动填写内部 API 的实际访问地址、内部 Key、协议与模型 ID，例如 `https://<内部服务地址>/api/model-gateway/v1`。模型目录为 `GET /api/model-gateway/v1/models`；推理端点为 `POST /chat/completions` 和 `POST /messages`。内部 Key 只授权模型调用，不能登录后台。后台不向 DSH 自动注入企业模型、不替换官方设置页，也不执行第二套 Agent loop。

后台通过 Spring AI 传输 OpenAI 请求与 Anthropic 非流式请求；Anthropic SSE 保留原生事件透明转发。内部 Key 当前为组织共享调用凭据，不是逐成员计费凭据。模型目录手工维护，没有上游自动发现。模型参数扩展、上游错误与取消仍需官方 DSH 完整对话兼容验收。

## 构建与候选交付

```sh
mvn -f server/pom.xml clean test package
npm --prefix web run build
python3 scripts/test-admin-release.py
python3 scripts/test-admin-packaged.py --java /absolute/path/to/java
node --test scripts/test-account-storage-fence.mjs scripts/test-admin-loopback-front.mjs
python3 scripts/pack-admin-release.py --output /private/tmp/workdsh-admin-candidate.zip
```

封闭包只收录后台 JAR、管理前端、已审查 Docker 配方、数据库维护文件和被成员服务器复用的登录/账户隔离资产。包不含成员运行实例、数据库、账号、密钥、证书或个人 Home。安装器 `scripts/install-admin-release.py` 校验清单与 SHA256，拒绝覆盖或不安全路径；详细步骤见 [后台交付](deploy/ADMIN-SERVER-CANDIDATE.md)。

成员服务器另从主仓库维护源码构建：

```sh
node "$WORKDSH_SOURCE/deploy/member-process/prepare-context.mjs" /private/tmp/workdsh-member-build "$PWD"
```

具体参数和固定依赖要求以主仓库 `deploy/member-process/README.md` 为准。不得从当前容器、临时 Home 或镜像手工导出启动源码。后台先就绪，再启动成员服务器；公网 TLS、反向代理、数据库备份与恢复、生产开机恢复及完整 Linux 隔离需要目标环境验收。当前源码和单元测试通过不等于 ECS 交付完成。
