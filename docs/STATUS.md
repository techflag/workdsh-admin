# 当前开发状态（2026-10-03）

当前实现采用单 ECS、按认证账号运行官方 DSH 原生进程。唯一成员运行源码位于 WorkDSH 主仓库 `deploy/member-process`；Admin 提供组织后台、成员登录、协作、成员数据库存储/会话审计、Spring AI 内部模型 API，以及成员入口复用的登录/浏览器账户隔离资产。当前共同官方目标为 `0.2.0-rc.2`，个人、企业和 Desktop 的实际安装与发行门禁分别记录，不能由后台构建代签。

订单/审核/订单源 API、页面和表已删除。旧单模型配置 API、Runtime 专属凭据、启动票据、双 Host 门户网关和共享 Host 发布路线已删除，无旧配置读 fallback 或测试数据导入。明确批准的 58 文件旧源码补丁已经应用并移除零字节文件。当前没有简单演示账号替换；登录只验证实际成员密码。当前模型只使用多个 provider 配置，供应商密钥加密保存；成员在官方自定义模型 API 设置中手动填写地址、内部 Key、协议和模型 ID。

## 本轮实际证据

- Java17 后端 clean test package 通过：18 套/25 项，23 项实际执行成功、2 项真实 MySQL 环境测试跳过、0 失败；退役接口/表否定测试同时验证现行成员登录、协作与管理权限。
- PostgreSQL V001–V007 新库交付 SQL 在 H2 PostgreSQL 模式执行，通过与当前 24 表 schema 的一致性检查。V006 补齐多个模型 provider、内部模型 Key 与传输签名记录，V007 增加独立 Desktop 正文/回执/墓碑；无旧模型数据迁移。
- ED07 新成员 Bearer 正文 API 的 H2 HTTP/并发 5 项与真实 SQLite 1 项测试通过：Cookie/service-key 拒绝、当前成员归属、退出/过期/禁用、首次改密、严格正文结构/限额、顺序与不可变前缀、持久原回执重试、并发设备竞争、独立 Web 存储、组织管理员只读审计与删除墓碑。见 [接口契约](DESKTOP-VISIBLE-SESSIONS-API.md)。这不代替 Desktop 实际集成验收。
- ED07 写入路径已采用当前行锁读取，避免 MySQL 默认 REPEATABLE_READ 在等锁后继续读取旧快照；实际 JDBC SQL 回归检查覆盖正文 session/receipt/prefix 锁查询与无锁状态读取，真实 SQLite 不含不支持的锁语法。MySQL 真引擎并发验收仍未执行。
- 本轮跨工程真实非模型 headless 联调 9 项通过：独立 Java17 JAR/临时 H2/随机 loopback 端口、Desktop Main 真实登录与 Bearer 代理、WorkDSH 构建 DesktopAuthority/BodySync、可见正文投影脱敏、实际管理员列表/正文/审计、丢 real commit 回执后的持久重试、前缀拒绝、删除墓碑及远端 logout 撤权。会话 inspect 为合成官方事件形状 fixture；临时服务/数据已清理，模型调用为零。这不是实际官方 DSH Host 或 packaged-runtime 验收。
- 随后实际官方 DSH Host 的独立企业 Profile 联调 7 项通过：官方 CLI 显式安装 alpha.2 外置插件、无第二 core、完整官方客户端及同一账号页、Main 账号/同步接口、真实 Session append/flush 的两条人工正文、后台管理员详情及访问审计、撤权后 401。没有 GUI 或模型调用；人工正文不作为供应商模型对话验收。
- 上轮 Node 22.23.2 管理 Web 构建通过。账户存储隔离与 HTTPS 后台转接 6 项测试通过，包含凭据过滤、跨站/任意目标拒绝与模型服务 Key 保留。
- 本轮封闭打包/严格安装 2 项测试通过；ED07 候选封闭包包含新 JAR、V007 和接口契约，不含测试数据或凭据。上轮真实 JAR + Web 制品通过 Java17 临时 H2/loopback 运行 smoke：登录退出、静态页面、退役路由 404、旧 Runtime 拒绝。模型调用为零。

可复现命令见 [README](../README.md) 和 [后台候选交付](../deploy/ADMIN-SERVER-CANDIDATE.md)。测试使用合成数据与独立临时服务；当前本地部署、旧测试库与个人 Home 不作为新版本证据；本轮仅整理本地源码提交，未推送、发布或替换现部署。

## 待完成与被挡项

- 旧环境、按成员容器策略、旧 Profile、ingress/preflight、Runtime v1 契约、member-test recipe/JSONL/pnpm probes 与专用测试均已删除。环境白名单已由当前启动器实际使用并通过测试；官方版本图审计和原生 spawn/PTY/koffi/scrub 探针迁入主仓库唯一 member-process，版本审计 2 项测试通过，原生探针仅做语法检查，尚未在 Linux 执行。此前自动审批拒绝的清理已在具体安全替代与源码快照证据下完成，无 pending 旧源码清理。
- 当前成员服务器的完整 Linux/ECS 构建、UID 文件与网络边界、官方完整 Web/插件、上传/真实会话恢复、撤权时活跃连接及冷启动恢复须在目标配方实测。已删除的旧双容器/共享 Host 成功记录不代替新路线证明。
- PostgreSQL 真引擎迁移/TLS/运行角色、MySQL 实例、备份恢复、正式域名/CA/开机恢复未由本轮本地检查覆盖。H2 的 SQL 交付一致性不等于真实 PostgreSQL 验收。
- 上游真实模型与官方完整对话、扩展参数、取消和错误兼容未验收；内部 Key 是组织共享调用凭据，模型目录手工维护。
- 服务器插件后台只读 API 已有权限/时效测试；当前 member-process 尚未上报实际运行清单，页面返回未知/不可用，真实 inventory 上报和页面未验收。
- Desktop 新正文 API 已完成后台接口测试；显式插件安装与实际官方 Host 正文事件链已通过独立 headless 联调；受信任 HTTPS、图形登录切换/本机工具任务及平台安装由 Desktop 独立验收，不声称完整终端审计。Desktop 升级、打包运行与个人历史数据验收独立进行。企业外置插件不默认捆绑个人/Desktop；企业 AI 资源权限继续按用户决定暂缓。
