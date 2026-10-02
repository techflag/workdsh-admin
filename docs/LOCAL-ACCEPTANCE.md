# 本地验收

当前后台开发入口与构建步骤见 [README](../README.md)。默认管理前端为 `http://127.0.0.1:18891/`、Java 为 `127.0.0.1:18890`，实际后台账号通过包外 bootstrap 配置初始化。

```sh
mvn -f server/pom.xml clean test package
npm --prefix web run build
python3 scripts/test-admin-packaged.py --java /absolute/path/to/java
node --test scripts/test-account-storage-fence.mjs scripts/test-admin-loopback-front.mjs
```

packaged smoke 自动创建临时 H2、虚构账号与随机回环端口，结束关闭服务，不访问供应商。管理 API、账户隔离和转接测试不能代替成员服务器实际 Web/上传/会话/UID 验收。

成员服务器从主仓库 `deploy/member-process/prepare-context.mjs` 构建。生产地址、私钥、数据库和成员存储在包外管理，旧双容器恢复命令已删除。完整检查范围见 [浏览器验收清单](BROWSER-ACCEPTANCE-CHECKLIST.md) 与 [当前状态](STATUS.md)。
