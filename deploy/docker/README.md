# 当前 Docker 源码职责

`Admin.Dockerfile` 从当前后台JAR和管理前端构建独立Java服务；需要显式固定 `JAVA_IMAGE`。`AdminFront.Dockerfile` / `admin-loopback-front.mjs` 是可选本机HTTP前门，后台连接保持HTTPS证书验证，不能作为公网TLS模板。

`user-entry.html`、`shared-entry-client.js`、`account-storage-fence.mjs` 是成员登录与官方页面启动前的浏览器账户隔离资产。文件名shared仅表示共同使用的登录资产；官方DSH运行进程按账号独立。主仓库 `workdsh/workdsh-web/deploy/member-process/prepare-context.mjs` 组装这些文件及唯一成员启动器、网关、会话桥接和Docker配方。

旧静态成员绑定网关、多成员共享Host或一成员一容器演示不再是发布入口。详细构建、配置及未完成验收见 [后台交付](../ADMIN-SERVER-CANDIDATE.md) 和根README。
