# 当前成员进程隔离验收边界

当前选择单 ECS、一个成员服务器容器、按认证账号运行官方 DSH 原生进程。启动、UID 分配、私有目录、端口规则与会话桥接的唯一源码是主仓库 `deploy/member-process`。Admin 不拥有第二套成员运行镜像。

当前环境白名单已由启动器实际使用，测试验证 root 凭据/执行钩子/代理变量不继承、固定 UID 和绝对成员 Home。旧 Runtime 环境、按成员容器 flags、Profile 生成和专属 ingress/preflight 已退役；它们从未执行当前按 UID 进程的实际安全策略。

真实 Linux UID 文件权限、端口访问、官方子进程/PTY、会话数据库恢复与撤权须针对唯一当前配方实测。旧 member-test 审计与原生探针已迁入主仓库唯一 member-process：审计从当前 immutable Profile 读取精确版本，混装/逃逸测试 2 项通过；原生探针要求显式非 root UID，保留子进程/PTY/原生库与子进程密钥 scrub 检查，仅做语法检查，未在 Linux 执行。旧 member-test recipe/JSONL/pnpm 与重复审计/探针源码已全部删除。授权/TLS/账户存储检查保留，详见 [当前状态](STATUS.md)。
