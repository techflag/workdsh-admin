# WorkDSH Admin

企业管理服务与网页版后台的首个开发切片。服务端使用 Spring Boot，前端使用 Vue 3 和 Arco Design Vue。每家企业独立部署；成员、交接和示例订单记录由此服务拥有，DSH 实例通过 `workdsh-provider-identity-enterprise` 按成员绑定。当前目标是验证多人协作，订单流程只保留必要样例。

## 当前可运行功能

- 管理员直接添加成员，显示一次临时密码；成员首次登录必须改密。
- 管理员停用成员、签发或撤销每成员 DSH 实例凭据；停用会使现有登录与实例凭据失效。
- 通用成员交接：A 在自己的 DSH 原生会话中通过 `enterprise-collaboration` 工具 `@` B，服务端生成 B 的待办；B 可在自己的 DSH 会话或管理网页中查看并回执；A 能查看完成结果。交接不依赖订单、不共享两人的会话。网页每 10 秒刷新待办并显示新交接提示。
- 销售可先创建空订单，上传截图/PDF/Excel 原件；Excel 有表格行预览，按行核对后加入订单，保留原件 ID 与工作表行位置。也可人工补录订单行、保存库存 SKU 匹配，再 `@` 指派复核员；复核员能下载原件核对，从个人待办中通过或退回。未上传原件或没有订单行时不能提交复核，库存 SKU 未确认时不能通过；编辑和复核用修订号防止覆盖他人的修改。
- 成员业务页面每 10 秒刷新自己的订单和待办：被指派的复核员收到提示及待办数字，退回后创建人收到提示及待处理数字。打开中的详情可用“刷新”重新读取服务端记录。
- 本地受控 DSH 入口：成员在概览页打开自己的 DSH；Java 服务签发一次性启动票据，独立网关按服务端绑定转发到该成员的实例，并在后台退出或凭据撤销后拒绝请求。此入口已通过双实例本地浏览器探针，不代表公网可部署。
- 管理后台浏览器登录使用服务端 `HttpOnly` Cookie；浏览器 JavaScript 不保存登录 token。脚本探针仍可用独立 Bearer 接口，DSH 实例继续使用独立运行凭据。
- 客户未提供 SKU 时可留空；设备/楼栋编号单独记为客户参考编号，不能把它伪装成客户 SKU 或库存 SKU。
- 企业订单业务接口也接受成员实例凭据；DSH 的只读订单工具可查询该成员授权范围内的订单与待办。管理员接口仍只接受网页登录凭据。
- 非授权成员不能读取订单；管理员仅看组织订单索引，不能自动读取订单详情。管理操作与复核操作记录审计。

原件限制为每单最多 10 份、单份最多 12 MB，仅接受 PNG/JPEG/PDF/XLS/XLSX；服务端校验文件头并保存 SHA-256。文件字节暂存在企业服务数据库中，只有该订单已授权成员可下载。表单中的“来源类型/文件名”仍只是人工填写的来源声明，实际原件以详情页的附件列表为准。

Excel 预览是按常见“规格/名称、数量”表头读取单元格的**确定性建议**，不是 AI 识别，也不会自动成为订单事实。在提供的“美埃”样本中，预览得到 99 个候选行，第一行没有客户 SKU；设备编号留在客户参考编号。价格只显示在预览中，尚未进入订单记录。

**当前不包含**截图/PDF 的结构化提取、AI 库存 SKU 候选、逐字段单元格来源定位、企业 OIDC、生产级浏览器会话、生产级 DSH Remote 入口和运行时隔离验收。本仓库尚不能作为公网企业服务部署。

## 本地运行

需要 JDK 17+、Maven 3.9+、Node.js 22+。首次启动前设置 `WORKDSH_BOOTSTRAP_ADMIN_EMAIL` 和至少 12 位的 `WORKDSH_BOOTSTRAP_ADMIN_PASSWORD`；运行时不会把密码打印到日志。

只想体验两人协作时，用 Node.js 22.19+ 在本仓库根目录运行：

```bash
node scripts/start-collaboration-demo.mjs
```

脚本从相邻 `workdsh` 仓库构建并打包身份、审计、权限和协作插件，启动临时 Spring Boot 服务、管理网页及两个独立 DSH Host；运行验证后在终端显示两个临时成员的账号。分别登录 `http://127.0.0.1:18894/`，点“打开我的 DSH”，在“协作交接”页发起与完成交接。输入 `q` 并回车会撤销测试访问、停用成员、关闭服务并删除临时插件包。若 WorkDSH 源码不在相邻目录，设置 `WORKDSH_SOURCE` 为其绝对路径。端口 18892、18894、18895 须空闲，且 WorkDSH 的 pnpm 依赖与管理网页 npm 依赖须已安装。此演示使用固定模型测试桩，只验证协作链路，不验证真实模型自主选择工具。

自动验收还会从官方插件清单核对企业身份、审计、权限与协作插件的 Fiber 均为 `ACTIVE`；只看到配置或导航入口不算插件成功运行。

```bash
cd server
WORKDSH_BOOTSTRAP_ADMIN_EMAIL=admin@example.test \
WORKDSH_BOOTSTRAP_ADMIN_PASSWORD='replace-with-a-long-local-password' mvn spring-boot:run
```

另一个终端：

```bash
cd web
npm ci
npm run dev
```

访问 `http://127.0.0.1:18891/`。后端默认写入 `server/data/` 中的本地 H2 文件，目录已忽略；真实部署仍需受控数据库、备份、TLS 和运行隔离。管理网页使用服务端 `HttpOnly` 会话 Cookie，不在浏览器脚本中保存登录 token。

## 测试

```bash
cd server && mvn test
cd web && npm run build
```

使用 Node.js 22.19+ 和两个临时成员启动两份隔离的官方 DSH Host，可运行 `scripts/probe-two-dsh-instances.mjs`。它验证独立实例、网页会话不能跨实例使用，以及撤销一名成员的实例凭据不会影响另一名成员。先运行本地管理服务，并设置 `WORKDSH_SOURCE`、`WORKDSH_BOOTSTRAP_ADMIN_EMAIL`、`WORKDSH_BOOTSTRAP_ADMIN_PASSWORD`、`WORKDSH_ENTERPRISE_IDENTITY_TARBALL`。设置 `WORKDSH_AUDIT_TARBALL`、`WORKDSH_ACCESS_TARBALL`、`WORKDSH_ENTERPRISE_COLLABORATION_TARBALL`、`WORKDSH_PROBE_WEB_SESSION=1` 和 `WORKDSH_PROBE_COLLABORATION=1`，即可独立于订单验证两份正式 DSH Web Host 的原生 Agent 会话交接与回执。固定模型测试桩负责确定性工具调用；撤销一名成员的实例凭据后，其已有会话不能恢复，另一名成员不受影响。可另设 `WORKDSH_ENTERPRISE_ORDERS_TARBALL` 追加订单样例和两份浏览器结果检查。脚本不会打印凭据，结束时撤销实例凭据、停用测试成员并删除临时 DSH 数据目录；测试记录和停用成员仍留在本地开发数据库中。该探针不证明真实模型会话、公网路由或操作系统级隔离。

启动管理服务和开发前端后，`scripts/probe-collaboration-ui.mjs` 使用两个独立浏览器身份验证销售指派、复核员自动看到待办、退回后销售自动看到提示和服务端状态。该脚本需要上述 `WORKDSH_SOURCE` 与管理员环境变量，以及 WorkDSH 开发依赖中的 Playwright；测试后停用临时成员，订单仍保留在本地开发数据库中。它验证成员业务页面的协作交接，**不等于 DSH 原生会话已经完成跨成员协作**。

通用交接可运行 `scripts/probe-handoff-ui.mjs`：两个独立浏览器账号发起交接、接收、回执和查看结果，不创建订单。双 DSH Host 探针额外设置 `WORKDSH_ENTERPRISE_COLLABORATION_TARBALL` 和 `WORKDSH_PROBE_COLLABORATION=1`，会在两份隔离的正式 DSH Web Host 的原生会话里调用交接工具，验证发送、接收、完成和回读；还会检查接收者 DSH 侧栏待办数、交接页面内容，并从 DSH 页面发起交接、在另一成员 DSH 页面完成回执，最后读取服务端保存的结果。该探针使用固定模型测试桩，尚未验证真实模型自行选择工具的行为。

该探针还在临时测试插件中注册一个计算工具：A 的同一原生会话先计算 `8 × 120 = 960`，再把工具结果通过正式协作插件交接给 B；B 回执后 A 读取结果。计算工具不会进入正式协作插件或订单功能。这验证 DSH 工具可以组合成“计算 → @ 成员”的执行链，不代表真实模型的自主决策能力已验收。

本地受控入口探针在独立测试管理服务中设置 `WORKDSH_DSH_GATEWAY_ORIGIN`、长度至少 32 字符的 `WORKDSH_DSH_GATEWAY_SECRET`，并令 `WORKDSH_PROBE_WEB_SESSION=1`、`WORKDSH_PROBE_GATEWAY=1`。配合 `WORKDSH_PROBE_COLLABORATION=1` 时，无需订单插件：探针会生成仅所有者可读的成员实例映射，启动 `scripts/enterprise-dsh-gateway.mjs`，让两名成员分别从管理网页打开自己的 DSH；接收方在 DSH 侧栏和交接页看到待办，随后验证回执、票据不可重放、Cookie 隔离、后台退出与实例凭据撤销。订单样例仍可单独追加，不是协作验收的前提。网关按当前 DSH `/api/remote.mux` WebSocket 路径实现，仅供 loopback 开发验证；版本升级时必须重跑探针。

若要在本机手动体验，在上述双成员协作与网关探针环境变量之外设置 `WORKDSH_PROBE_DEMO=1`，保持管理服务和网页开发服务器运行，并在交互式终端执行同一探针。自动验证完成后，终端会显示两个**临时测试成员**的登录信息及管理网页地址；分别登录、点击“打开我的 DSH”，即可在各自 DSH 的“协作交接”页发送和处理事项。此模式不安装订单插件，模型仍是固定测试桩；手动体验应使用交接页面，不把测试模型的普通对话当成真实 AI 功能。体验结束后在探针终端输入 `q` 并回车，脚本会撤销凭据、停用测试成员和关闭实例。直接中断整个终端进程可能跳过清理，应使用 `q` 退出。

接口及 DSH 身份契约见 [docs/runtime-identity-v1.md](docs/runtime-identity-v1.md)。服务端是组织、成员、订单和审计的权威来源；官方 DeepSeek Harness 代码不在本仓库内。
