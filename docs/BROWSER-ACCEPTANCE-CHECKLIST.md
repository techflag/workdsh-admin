# 当前浏览器验收清单

验收使用当前 `workdsh/deploy/member-process` 与独立组织库，不以旧双 Host/共享 Host 测试记录替代。当前源码的 headless 检查不代表下表已通过。

| 要求 | 当前验证入口 | 剩余实际验收 |
| --- | --- | --- |
| 统一完整官方 Web 与共同功能插件 | 主仓库 member-process Profile 与共享功能 manifest | 当前 Linux 配方完整页面/插件操作 |
| 成员登录、管理权限与撤权 | Admin Java 测试；main identity process/backend-authentication 与 gateway 测试 | 真浏览器退出/停用、活动 HTTP/WS 撤销、另一成员不受影响 |
| 账户切换后的浏览器存储隔离 | `scripts/test-account-storage-fence.mjs` | 同浏览器旧页/刷新/重登录、官方实际缓存与授权 |
| 成员文件、会话和运行进程边界 | canonical start/session-bridge 与成员数据库 API | 实际不同 UID 的文件/网络拒绝、上传、含消息会话恢复 |
| 资料与生成成果显式协作 | Admin CollaborationFlowTest/PersistentOrganizationRestartTest | 当前原生 UI 真实生成、共享、接收下载、非参与者拒绝 |
| 内部模型 API 手填 | ModelProviders/ModelGateway/SpringAiModelClient 测试 | 官方完整对话、流式/错误/取消兼容 |
| 部署与恢复 | SchemaDeliveryTest、packaged smoke、封闭安装校验 | 真数据库/TLS、备份、ECS 冷启动与正式域名 |

验收账号和实际地址在包外受控管理，文档不固定旧简单账号。企业身份/协作为显式外置组合；个人与 Desktop 不默认安装。未提交/发布，详见 [当前状态](STATUS.md)。
