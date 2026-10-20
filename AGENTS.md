# AGENTS.md — TokenX 开发代理总规则

> 适用于 Qoder 及其他能读取 AGENTS.md 的 AI 编程代理。此文件放在仓库**根目录**。
> 目标：以黑马点评的工程实践为参考，**一次完成一个可运行、可验证的纵向功能**，而不是一次生成整个平台。

## 0. 每次任务开始时必须做

1. 读取 `AGENTS.md`、`docs/PROJECT_SPEC.md`、`docs/ENGINEERING_STANDARDS.md`、`docs/DEVELOPMENT_PLAN.md`、`docs/GIT_WORKFLOW.md`；涉及数据模型或性能链路时还必须读取 `docs/DATABASE_DESIGN.md`、`docs/HIGH_CONCURRENCY_DESIGN.md`。
2. 查看现有项目结构、最近变更和测试；**先阅读现有代码，再决定改哪里**。
3. 对照 `docs/DEVELOPMENT_PLAN.md`，锁定**唯一当前任务**；若任务未明确，先选首个未完成任务并说明选择依据，不擅自开始下一项。
4. 先给出简短实施计划：目标、涉及文件、验收标准、测试、风险。然后只实现该项。

### Git 起手检查（任何代码修改前）

- 必须检查 `git status --short`、`git branch --show-current`、`git log -1 --oneline`，确认已有仓库、分支和未提交改动；仓库未初始化时按 `docs/GIT_WORKFLOW.md` 新建。
- **一个功能一条本地功能分支**，测试通过后只提交当前任务相关文件，使用 Conventional Commits；不得把用户未提交的其他工作混入提交。
- 不得擅自 `git push`、`merge`、`reset --hard`、`clean`、`stash`、`amend`、重写历史或删除分支；详见 `docs/GIT_WORKFLOW.md`。
## 1. 项目固定决策

- 后端：Java 21、Spring Boot 4.1.1、Maven 3.9.x、MyBatis-Plus Boot 4 Starter 3.5.17。
- 基础设施：MySQL 9.7.x、Redis 8.10.x、Kafka Broker 4.3.1；Spring Kafka / Kafka Client 由 Spring Boot BOM 管理。
- 前端：React 19、TypeScript、Vite、Node.js 26.9、pnpm 12.x；精确前端依赖首次安装后通过 lockfile 固定。
- 初期架构：**一个 Spring Boot 应用 + 一个 React 前端**，按业务模块分包；不预设微服务或多 Maven 模块。
- MySQL 本地 root 通过密码登录；Redis 本地可不设密码但必须只监听本机；任何密码不得写入仓库。
- 禁止未经用户同意升级/降级核心版本、换技术栈或引入额外中间件。

## 2. 黑马点评的正确使用方式

- 参考：https://github.com/cs001020/hmdp （关注 `master` 完整实现），以及 `docs/REFERENCES.md`。
- 参考 Controller→Service→Mapper 的职责划分、Redis 缓存、Lua 秒杀、异步下单的业务处理思想。
- **不得直接复制旧项目完整代码、旧 `pom.xml`、认证拦截器或 Redis 事务代码。** 参考项目采用 Spring Boot 2/Java 8，必须按 Boot 4 官方 API 重新适配和测试。
- 参考仓库若未确认代码许可证，只研究思想并独立实现，不粘贴不可确认许可的大段源码；保留引用说明。
- 如果参考代码和本项目规范冲突，以本项目规范与官方框架文档为准。

## 3. 绝对不能擅自做的事

- 一次新增多个功能点；未完成当前功能测试就开始下一个。
- 为了“加分”主动加入微服务、Nacos、Seata、Redisson、ShardingSphere、Elasticsearch、分库分表、复杂通用框架。
- 先实现 Kafka/Redis 异步秒杀再补基础订单：**必须先有 MySQL 同步基线和完整测试**。
- 破坏既有 API/表结构、覆盖用户数据、删除表、清空 Redis、重置 Kafka Topic，未经明确批准不能执行。
- 偷改测试让其通过、跳过失败的测试、把 Mock 结果当作真实集成测试、编造压测结果。
- 在 Controller 写 SQL/Redis Lua/复杂业务规则；把所有逻辑塞进一个 Service；随意抽象接口/工厂/工具类。
- 明文写入密钥、数据库密码、JWT Secret 或提交 `.env`；不得在日志输出 Token、密码及敏感头。

## 4. 单任务研发闭环（必须遵守）

**Git Check → Read → Specify → Test → Implement → Refactor → Verify → Git Commit → Report → Stop**

0. **Git Check**：检查当前分支、工作区和已有未提交变更，按 Git 工作流开始本次功能分支。
1. **Read**：检查相关实现；搜索项目里是否已有同类代码。
2. **Specify**：写清本次成功场景、边界场景、失败场景及数据契约。
3. **Test**：业务规则先写失败测试（可行时遵循红—绿—重构）；DB、Redis、Kafka 边界写集成测试。
4. **Implement**：只写使本次测试通过的最小代码；优先 Spring / Java 标准能力。
5. **Refactor**：测试保持通过，清理重复、命名与结构；不扩范围。
6. **Verify**：执行受影响模块测试，必要时全量 `./mvnw verify`、前端 `pnpm lint && pnpm build`；真实组件使用隔离测试环境。
7. **Git Commit**：通过测试并审核 `git diff --cached` 后做当前功能的**本地原子提交**；提交不了要说明原因，禁止包含无关改动。
8. **Report**：逐项列出变更文件、测试命令与实际结果、分支和 Commit SHA、未完成事项、下一步建议。**失败要如实报告**。
9. **Stop**：当前任务完成后停下，等待用户明确要求继续。

## 5. 设计与风格

- Java 遵循 Google Java Style（提交格式化后的代码）；React/TS 使用 ESLint + Prettier。
- 按业务模块组织，如 `auth/`、`catalog/`、`seckill/`、`order/`、`wallet/`；模块内按 `controller/service/mapper/model` 分层。
- API 入参/出参 DTO 与数据库实体分开；Java `record` 可用于不可变 DTO；HTTP API 不返回 Entity。
- Service 管业务规则与事务边界；Mapper 管数据读写；Controller 只做校验、适配与返回。
- 首选**简单清晰的代码**；设计模式只在确有变化点时使用，详见 `docs/ENGINEERING_STANDARDS.md`。
- 禁止创建只有一个实现、无明确变化点的 Strategy/Factory/Facade/AbstractManager 等“模式装饰”。
- 不在事务内做耗时远程调用；不会凭空宣称 Kafka Exactly Once 能保证 MySQL 业务写入唯一。

## 6. 合并门槛

任务完成意味着：需求验收条件满足、相关测试通过、错误处理到位、日志无敏感信息、README/接口文档随代码更新、没有无关重构，且已按 `docs/GIT_WORKFLOW.md` 完成安全的本地提交（或注明未提交原因）。若工具或环境不能运行测试，**不能宣称通过**，需给出阻塞原因和手工验证指令。

## 7. 重要文档

- `docs/PROJECT_SPEC.md` — 业务、技术栈、接口、数据模型与总体边界。
- `docs/ENGINEERING_STANDARDS.md` — 编码、测试、设计模式与代码审查要求。
- `docs/DEVELOPMENT_PLAN.md` — 唯一合法的功能实施顺序与验收门槛。
- `docs/DATABASE_DESIGN.md` — 已决的数据模型、事务、索引与迁移基线。
- `docs/HIGH_CONCURRENCY_DESIGN.md` — Redis/Kafka 唯一高并发演进路线、故障恢复与性能口径。
- `docs/GIT_WORKFLOW.md` — Git 初始化、功能分支、提交、审查和危险操作禁止清单。
- `docs/REFERENCES.md` — 黑马点评、TokenMarket、Spring 官方资料。
- `QODER_START_PROMPT.md` — 初次交给 Qoder 的任务提示。
