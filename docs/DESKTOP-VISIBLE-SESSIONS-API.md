# Desktop 成员可见正文接收 API

这份接口用于接收显式安装企业身份插件的 Desktop 所同步的用户/助手可见正文记录。组织和成员由有效成员 Bearer 推导；客户端不能指定组织、成员、管理员角色、原生会话 header 或正式 Web 会话目标。Desktop Host 不应持有后台 Bearer 或服务器 service key，由 Desktop Main 在验证当前账号与固定设备后代理白名单请求。传输须使用部署受信任的 HTTPS，Bearer 放 Authorization，不能放 URL。

正文由成员设备主动提交，不是可信终端全量审计；后台无法证明设备没有漏报或伪造正文。

## 鉴权与数据归属

`POST`、`GET`、`DELETE /api/member/visible-sessions` 都要求原始请求中显式的 `Authorization: Bearer <member-token>`。仅 Cookie、仅 `X-WorkDSH-Service-Key` 或模型内部 Key 不授权本接口。每次重新检查登录未过期、成员有效及已完成首次改密。成员只能操作自身正文会话，管理员读取仍走现有只读管理 API。

归属键为服务器推导的组织/成员加客户端 `sessionId`。首次提交固定该会话的 `deviceId`，同一成员的其他逻辑设备不能接管。另一成员可以使用相同本地 ID，得到独立的服务器会话。设备 ID 是逻辑写者绑定，不是硬件证明；持有同一成员有效 Bearer 的客户端仍可自报相同逻辑设备 ID。

## 提交与重试

`POST /api/member/visible-sessions`，`Content-Type: application/json`：

```json
{
  "version": 1,
  "deviceId": "fixed-device-id",
  "sessionId": "stable-local-session-id",
  "revision": 1,
  "requestId": "persisted-request-id",
  "entries": [
    {"seq": 0, "recordId": "stable-record-id", "role": "user", "text": "可见用户正文"},
    {"seq": 1, "recordId": "another-record-id", "role": "assistant", "text": "可见助手正文"}
  ]
}
```

以上顶层与记录字段全部必需，不接受附加字段、重复 JSON 字段或多个 JSON 对象。ID 均为 1–128 个 `[A-Za-z0-9_-]` 字符。`seq` 从 0 连续排列，`recordId` 在本会话中唯一、稳定；`role` 只允许 `user` / `assistant`，`text` 只接受非空字符串。时间由服务器接收时生成，不接受客户端事件时间。Desktop 应连接真实 Admin 服务的受信任 HTTPS 入口；保留 Cookie 的可选 AdminFront 本机前门不代理此成员 Bearer 接口。

首次 `revision=1`，以后只接受当前 revision + 1。每次提交包含全部已接收记录的不可变前缀和至少一条新增记录；已提交记录的 seq/recordId/role/text 不能编辑、缩短或重排。编辑结果若需要同步，必须作为新的可见提交记录追加，不能用新文本改写已接收记录；当前 Desktop 投影检测到已接收前缀改变时会停止该次同步并保留原记录。已提交的中断助手可见正文也可作为 assistant 文本记录。这里的追加日志不承诺等于客户端当前渲染快照。

客户端先持久化完整请求，再发送。相同 requestId、revision 和解析后的内容重试返回当次原始回执，即使后续 revision 已提交；JSON 字段顺序不影响重复判定。requestId 被用于不同内容、乱序或跳号、前缀修改、无新增记录、其他 deviceId 写入都返回 409。并发写入在成员/登录会话锁下串行提交，正文和回执在同一数据库事务提交，拒绝时不保存部分正文。

成功 POST、GET、DELETE 都返回 HTTP 200、`Cache-Control: no-store`，字段固定为：

```json
{
  "version": 1,
  "sessionId": "desktop-body:<opaque-server-hash>",
  "deviceId": "fixed-device-id",
  "clientSessionId": "stable-local-session-id",
  "revision": 1,
  "nextRevision": 2,
  "recordCount": 2,
  "deleted": false
}
```

`sessionId` 是后台生成的独立正文命名空间；提交及成员查询仍使用本地 `sessionId`。重复 POST 返回原来的 revision/recordCount；GET 返回最新状态。客户端不能把旧重试回执当成当前最新状态。

## 状态与删除

`GET /api/member/visible-sessions?deviceId=<fixed>&sessionId=<local>` 返回自身最新回执。未知会话 404，设备不匹配 409；该接口不返回正文。

`DELETE` 使用相同路径与查询参数，删除该成员会话的正文记录，返回 `deleted=true`、`recordCount=0`。重复删除已有墓碑返回相同结果，未知 ID 返回 404。保留稳定会话归属、revision、请求摘要和墓碑；该固定设备的后续 POST 返回 410，不能复活；其他 deviceId 仍返回 409，不能切换设备。GET 墓碑仍返回 200。删除此后台正文副本不删除 Desktop 本地官方会话，也不触碰正式 Web 会话；Desktop 应独立执行用户请求的本地删除。

## 正文边界与限制

企业身份插件只应采集已 commit 的 `user/message` 且 `source.kind=user`，以及 `assistant/message` 的 text parts。工具调用/结果、思考、system、文件/附件、配置与凭据不能同步。后台采用严格 text DTO，拒绝这些结构字段，并拒绝可识别的常见 API token、私钥 PEM、JWT 和 Bearer token 形状。插件在本地先脱敏，不能把原始凭据发送给后台。文本检测不能识别所有未知秘密或证明正文来源，因此不能把此接口声称为完整隐私过滤或不可绕过审计。

- 原始 JSON 请求最多 512 KiB。
- 单个完整快照最多 512 条记录。
- 每条 text UTF-8 最多 64 KiB，快照 text UTF-8 合计最多 256 KiB。

超过限制返回 413，绝不截断正文；客户端必须保留本地内容并显示同步失败。空 entries、字段/ID/序列/文本错误、已识别凭据返回 400；无效登录 401，首次改密未完成 403，非 JSON 内容类型 415。

## 存储与管理员只读审计

独立表 `member_desktop_body_sessions`、`member_desktop_body_records`、`member_desktop_body_receipts` 保存归属/状态、追加正文和幂等回执。当前 schema 与 PostgreSQL V007 新开发迁移均包含这三表。不能对现运行旧数据库直接重跑新库 schema。

现有组织管理员 `GET /api/admin/sessions` 列出未删除 Desktop 正文与正式 Web 会话；正文 ID 带 `desktop-body:` 前缀。`GET /api/admin/sessions/{memberId}/{serverSessionId}` 按当前管理权限及组织范围只读返回 text content，沿用分页和 `session.content.viewed` 审计。显示的正文时间为后台接收时间。普通成员和其他组织管理员不能读取；墓碑不再出现在管理列表/正文详情。

本轮 H2 HTTP/并发测试、真实 SQLite 存储和 PostgreSQL 迁移链交付校验属于后台接口证据。它们不代替 Desktop 实际安装/同步、真实 PostgreSQL/MySQL、受信任 HTTPS 或 packaged-runtime 验收。

独立官方 Host 联调已验证外置插件经真实 CLI 安装、同一官方服务实例、公开 Session append/flush 到本接口及管理员详情/访问审计。正文来自两条明确人工构造文字，模型/GUI 请求为零；临时 Java17/H2 与成员空间已清理。它不替代真实供应商模型、生产数据库、部署 HTTPS 或 Electron 图形验收。
