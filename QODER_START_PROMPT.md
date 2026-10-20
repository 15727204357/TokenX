# 交给 Qoder 的首轮任务提示（直接复制进 Qoder 聊天）

你现在负责 **TokenX** 项目。先读取仓库根目录 `AGENTS.md`、`docs/PROJECT_SPEC.md`、`docs/ENGINEERING_STANDARDS.md`、`docs/DEVELOPMENT_PLAN.md`、`docs/GIT_WORKFLOW.md`、`docs/REFERENCES.md`，并严格遵守约束。

本项目是 Java21 + Spring Boot 4.1.1 + MySQL9.7 + Redis8.10 + Kafka4.3 + React19/TypeScript 的 Token 秒杀与交易系统。**本轮只实施 `F0-1`，不得提前开发其它功能**。

工作顺序：

1. 先执行 `git status --short`、`git branch --show-current`、`git log -1 --oneline`（未初始化时按 Git 规范建仓并选择 F0-1 功能分支）；尊重所有用户已有改动，不擅自清理/覆盖。然后阅读当前仓库，不猜测文件结构；查看黑马点评 https://github.com/cs001020/hmdp 的 `master` 结构、`pom.xml` 和典型 Controller–Service–Mapper 写法，说明哪些可借鉴、哪些需要从 Boot2/Java8 升级，确认许可证再决定是否能复用具体代码。只做参考，不整库复制。
2. 列一个不超过 10 行的 F0-1 实施计划（涉及文件、验收测试、不会修改的部分）。
3. 以**单 Spring Boot Maven 模块**建立最小 Java21 项目，使用 Maven Wrapper 和 Spring Boot 4.1.1；一个 `/api/v1/health` 接口，配套最小测试。添加可理解的 README、`.gitignore`、`.gitattributes`、`.editorconfig`，优先复用交接包已有的 Git 配置文件。本轮不写业务表、不接 Kafka、不写秒杀、不创建多模块或微服务。
4. 先实现测试/验收，再写最少代码；运行 `./mvnw clean verify` 和健康检查，记录真实结果。不能运行就说明真实阻塞与重现命令，绝不编造 PASS。
5. 测试全部通过后，按 `docs/GIT_WORKFLOW.md` 审查暂存区，仅对 F0-1 的变更做本地原子提交，报告分支、Commit SHA、`git status --short`、变更文件、与黑马点评对应关系、测试命令和输出、遗留问题；未经授权不得 push/merge，也不得使用破坏性 Git 命令。**完成后立刻停止，等待我指定下一任务。**

如发现已存在工程，请优先维护原有代码和测试，不得推倒重写；遇到核心版本/依赖/迁移工具兼容性问题，应解释并停止，不擅自降级 JDK21/SpringBoot4/MySQL9.7。
