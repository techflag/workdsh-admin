# 当前成员网关与退出边界

唯一网关源码为 WorkDSH 主仓库 `deploy/member-process/gateway.mjs`，使用现行成员 Cookie/Bearer 与后台 `/api/auth/me`。成员必须处于激活状态、组织与固定进程主体一致且完成首次改密；Runtime 专属凭据与启动票据已删除。官方 DSH 继续拥有 HTTP/Remote mux、会话与 Agent 执行。

对应当前测试位于主仓库 `deploy/member-process/tests/gateway.test.mjs`、`packages/providers/identity-enterprise/tests/process.test.mjs`、`process-supervisor.test.mjs` 和 `backend-authentication.test.mjs`。Admin 的 `RetiredRoutesTest` 同时要求旧接口 404、旧 Runtime header 401，现行成员协作成功。

浏览器账户隔离资产为 `deploy/docker/account-storage-fence.mjs`，单元检查为 `scripts/test-account-storage-fence.mjs`。授权检查与实际存储所有者检查共同构成边界；Profile 选择不是用户数据安全边界。

当前 Linux 配方的真实退出/停用、活跃 HTTP/WS、同浏览器旧页与不同 UID 文件/网络隔离须独立验收。旧门户网关与共享 Host 的历史结果不作为新路线证明。见 [当前状态](STATUS.md)。
