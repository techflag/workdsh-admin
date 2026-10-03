# Docker 源码职责

`Admin.Dockerfile` 构建 Java 管理服务及管理前端，须显式固定 `JAVA_IMAGE`。`AdminFront.Dockerfile` 与 `admin-loopback-front.mjs` 是可选本机 HTTP 前门；后台保持 HTTPS 证书验证，不能作为公网 TLS 模板。

Agent、工具、文件和本机会话在员工 Desktop 运行。后台部署参数与验证见 [后台交付](../ADMIN-SERVER-CANDIDATE.md)。
