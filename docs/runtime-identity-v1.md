# DSH 实例身份契约 v1

成功响应的机器可读定义见 [runtime-identity-v1.schema.json](runtime-identity-v1.schema.json)。双 DSH 实例探针在创建测试成员前逐字段比较管理仓库与身份插件包中的 v1 schema，并用真实 Java 响应校验字段、类型和版本；两侧独立升级时必须重跑该探针。schema 约束的是成功响应，401 等错误响应不在其中。

首版按“一名成员一个 DSH 实例”验证协作。管理员在网页后台为成员签发随机实例凭据；每个 DSH 实例仅从服务端环境变量取得该凭据，不接受浏览器提交的成员 ID。凭据只显示一次，服务端仅保存 SHA-256 摘要；应经部署密钥系统分发。停用成员或撤销凭据后，新的身份解析返回 401。本地双实例探针已验证独立 DSH_HOME、实例凭据、Web cookie 与单人撤销；订单协作仍由管理服务保存，不能用多开浏览器窗口代替权限验收。

```http
GET /api/internal/runtime/identity
Authorization: Runtime <instance-secret>
```

成功响应（只作为协议示例）：

```json
{
  "contractVersion": 1,
  "principalId": "member-uuid",
  "organizationId": "organization-uuid",
  "organizationName": "示例企业",
  "role": "MEMBER",
  "membershipRevision": 2,
  "active": true
}
```

DSH 插件在启动及每次 `resolve` 时向该端点核验；服务不可用、401、错误版本或不合法响应均拒绝新操作。插件将结果映射到 WorkDSH `IdentityService`/`ActorContext`，原有 Session Access Bridge 负责绑定 Session owner。DSH 的工具审批不是业务复核：订单待办、复核意见和审计保存在本服务端。

通用成员交接也走同一身份边界。`GET /api/collaboration/colleagues` 只返回同组织有效成员的 ID、姓名和邮箱，用邮箱区分重名成员；`POST /api/collaboration/handoffs` 由服务端从凭据确定发送人，接收人必须是同组织的另一名有效成员。`GET /api/collaboration/inbox` 与 `/sent` 仅返回当前成员接收或发出的记录；只有接收人能调用 `POST /api/collaboration/handoffs/{id}/complete` 回执。发送携带同一工具调用的 `requestKey` 去重。它不复制或开放任何 DSH Session，也不要求创建订单。

协作接口另有独立的版本检查：已就绪的成员使用相同 `Bearer` 或 `Runtime` 凭据请求 `GET /api/collaboration/contract`，成功响应为 `{ "contractVersion": 1 }`。未登录、首次登录未改密或成员失效时不能取得契约。DSH 协作插件每次操作先核对该版本；缺失、不可用或不是 v1 时拒绝读写，尤其不能先写交接再发现响应不兼容。身份契约与协作契约分别升级，并由双实例探针验证服务端和插件配套。

管理员的 Web 登录 token 与 DSH 实例凭据是不同用途的秘密。Web token 不能提交到实例身份端点，实例凭据不能作为管理员登录凭据。默认 WorkDSH 本地 Profile 仍使用 `identity-local`；企业 Profile 必须只加载 `identity-enterprise`，并为每名成员使用独立 Profile/数据目录。多实例演示不等于多人公网入口、共享文件或代码执行已隔离，相关验收完成前不得开放。

订单业务接口接受该成员的 `Runtime <instance-secret>`，每次由服务端重新查有效成员并执行订单 ACL；成员管理、组织审计和后台订单授权接口只接受管理员的网页登录 `Bearer` 凭据。DSH 订单工具在查询前还会比较身份提供方解析的主体与实例凭据对应的主体，拒绝两份插件配置串号。订单原件由创建人在草稿或退回状态上传，数据库保存字节、摘要和上传者；授权成员可列出及下载原件。Excel 原件可生成只读表格行建议，创建人逐行采用后，订单行记录原件 ID 与工作表行定位。提交复核必须先有原件及至少一条订单行。当前 DSH 工具只读，创建、上传、采用建议、提交与复核由网页操作。

## 后续契约工作

- 已加入本地受控 DSH Web 入口探针：成员登录后由 Java 服务签发 60 秒、一次性票据；Node 网关经服务端消费票据后，将该浏览器的 HTTP 与 `/api/remote.mux` WebSocket 连接转发给预先绑定的成员实例。DSH 启动 token、Host Cookie 和实例凭据只在服务器侧；网关每次请求及存续 WebSocket 定期核验成员登录会话和实例凭据。后台退出或成员凭据撤销后，新请求立即拒绝，已有 WebSocket 关闭。两个独立成员、原生会话与网页已做本地端到端验证。此网关目前仅支持 loopback HTTP 开发部署，不能作为公网入口。
- 正式部署仍需 TLS 终止、可信反向代理、实例生命周期/健康检查、配置密钥轮换与进程/文件系统隔离验收；不能仅凭当前本地网关探针开放多人远程使用。
- 管理后台浏览器登录现由 Java 服务设置 `HttpOnly`、`SameSite=Strict` Cookie；前端不再在 `sessionStorage` 保存登录 token。后台退出与改密会清除 Cookie 并撤销对应服务端会话，网关授权随之失效。跨站变更请求被 Origin/Fetch Metadata 拒绝。仍需登录限速、正式 TLS/反向代理下的 Cookie 验收、管理操作二次校验，并处理多实例网关水平扩容时的会话共享或一致性路由。
- 当前阶段以跨成员交接、回执和撤权为验收目标；订单提取、SKU 候选与审批深化暂缓，不作为协作演示或发布门槛。
