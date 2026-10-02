# 当前服务器交付验收

交付包含两部分：本仓库后台候选和主仓库唯一 `deploy/member-process` 成员服务器配方。构建、封闭 ZIP、严格安装与 Docker 参数见 [后台交付](../deploy/ADMIN-SERVER-CANDIDATE.md) 和主仓库 member-process README。

当前后台通过 Java 测试、Node22 Web 构建、账户存储/HTTPS 转接、封闭打包/安装，以及 Java17 制品 smoke。PostgreSQL 新库 SQL 交付一致性在 H2 模式验证，不能代替实际引擎。

目标 Linux/ECS 仍需检查固定官方版本、完整 Web/共同插件、不同 UID 文件与端口访问、退出与撤权、上传/含消息会话恢复、数据库 TLS/角色/备份与冷启动。真实模型对话和正式浏览器 CA 也须独立实测。旧双 Host/共享 Host 归档与镜像不再是现行交付来源，源码不得从临时容器导出。

详见 [当前状态](STATUS.md) 与 [浏览器验收清单](BROWSER-ACCEPTANCE-CHECKLIST.md)。未提交/发布。
