# TokenX / Qoder 初始交接包（历史说明）

> 本文件只记录仓库来源，不再是任务入口。最初交接用于建立 F0-1；当前 F0-1～F0-3 已完成。

后续开发统一按以下文档执行：

1. `AGENTS.md`：代理总规则；
2. `docs/DEVELOPMENT_PLAN.md`：唯一实施顺序，当前首个未完成任务为 F1；
3. `docs/PROJECT_SPEC.md`：已决产品与 API 基线；
4. `docs/DATABASE_DESIGN.md`：已决表结构、事务与迁移基线；
5. `docs/HIGH_CONCURRENCY_DESIGN.md`：Redis/Kafka 高并发、补偿与压测基线；
6. `docs/GIT_WORKFLOW.md`：分支、提交与危险操作限制。

当前源码、依赖、运行方法和真实完成状态见根目录 `README.md`。不要再复制或执行 `QODER_START_PROMPT.md` 的 F0-1 历史任务；每轮只做 `DEVELOPMENT_PLAN.md` 中一个未完成任务，验证、提交后停止。
