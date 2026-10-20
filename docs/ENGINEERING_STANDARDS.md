# TokenX 工程研发规范（面向开发者与 AI 代理）

## 1. 执行原则（优先级从高到低）

1. **业务行为正确** > 可读性/可维护性 > 性能优化 > 技术炫技。
2. **一次只做一个功能**：开发范围要能在一个小任务内完成实现、测试、必要的前后端联调。
3. **先验证现状**：在阅读已有代码、数据库脚本和测试后再改；禁止“猜测存在某文件/方法”。
4. **小步提交**：任何阶段应能启动和回退；最小差异；不在新功能中顺便全局重构。
5. **测试是交付的一部分**：没运行不得声称通过；不能因测试失败而删测试或降低断言。
6. **保持一个真相源**：MySQL 是交易事实来源；缓存与消息是加速或投递工具。
7. **规格随代码走**：功能变动同时更新 API 契约、迁移脚本、测试和必要的 README。

## 2. 参考黑马点评，但不照抄

- 开发代理必须先检查黑马点评对应的业务链路：例如活动抢购看 `VoucherOrderServiceImpl` 一类处理、Lua 脚本、缓存逻辑；**具体文件存在与否须实际确认**。
- 借鉴分层处理：Controller 做请求映射/校验，Service 放业务、Mapper 做持久化，DTO 不直出敏感实体。
- 绝不搬运原 `pom.xml`（旧 Spring Boot2 / Java8）、旧认证拦截器、旧 Redis 客户端配置、自调用事务的“技巧”。按 Spring Boot 4 官方规范适配。
- 第一次发现旧项目做法与本项目需求冲突，记录 `来源→目的→改造原因→验证方式`；不用旧项目的教程实验特性来代替交易可靠性。
- 若原仓库无明确适用的 LICENSE，仅**阅读思想、独立实现**，不得复制大段源码或直接把原仓库提交到本项目。

## 3. Java / Spring 代码规范

- 遵循 Google Java Style Guide；统一使用 `google-java-format` 或已确认与其一致的自动格式化方案。通过工具执行格式化，不手动争论空格。
- 命名：包小写；类型 UpperCamelCase；方法/变量 lowerCamelCase；常量 UPPER_SNAKE_CASE；业务含义优先于缩写。
- 业务分包，包内常见层：`controller`、`service`、`mapper`、`model/entity`、`dto`、`config`（只有实际需要时才建）。
- 依赖注入优先构造器；禁止字段 `@Autowired`；不滥用静态工具类；避免 `ThreadLocal` 无清理的上下文管理。
- 不把 DB Entity 暴露成 HTTP 返回，也不让入参直接绑定可写 Entity；创建对应请求/响应 DTO。
- Controller 不含交易算法；Service 管业务规则和事务；Mapper 不写跨业务编排；SQL 明确检查影响行数。
- 参数使用 Jakarta Validation；统一 `@RestControllerAdvice` 处理业务异常，不大量 `catch(Exception)` 吞错；错误码语义稳定。
- 配置从 `application.yml` + `application-local.yml` / 环境变量读取；默认配置不可包含真实密码。
- 日志使用参数化日志和结构化字段；不打印密码、JWT、API Key、完整支付敏感报文。
- Java `record` 适用于不可变 DTO / 事件；没有必要不额外引入 MapStruct、Lombok 复杂注解体系。

## 4. React / TypeScript 规范

- 采用函数组件 + Hooks，TypeScript `strict`；禁止用 `any` 逃避类型设计。
- 组件只负责 UI 与交互；网络请求集中在 `api/`，业务数据获取在 `hooks/`（确有需求才建立）。
- 业务路由与页面：`pages/`；小的可复用组件：`components/`；页面内组件不要过早提成全局组件库。
- 统一 Axios 或 fetch 之一（默认 Axios），HTTP 错误/认证头统一处理，不在每个组件重复。
- React Router 做路由；远程服务端状态优先 TanStack Query（接入时再添加），本地表单用组件状态；无复杂跨页交互不引入 Zustand。
- ESLint + Prettier 固定格式；pnpm lockfile 必须提交；`pnpm lint`、`pnpm build` 作为交付门槛。
- 只按需求使用 Ant Design 组件，避免先做漂亮但无业务功能的大屏。

## 5. 数据库规范

- 所有交易关键约束至少有数据库层保证：唯一索引、非空、状态字段、可用库存条件更新。
- 所有价格用 `BIGINT` 的最小货币单位（cent）；额度使用 `BIGINT`；禁止 float/double 计算钱和积分。
- 一个业务事务必须写清事务边界及失败行为；只在 Service 层标 `@Transactional`，不能假定同类内部自调用事务会生效。
- 事务内不能做 HTTP 远程调用或等待 Kafka Broker；尽量缩短锁持有时间。
- MyBatis-Plus 适用于简单 CRUD；库存扣减、状态条件迁移和账本幂等使用显式可测试 SQL。
- 数据迁移按顺序版本化；禁止业务启动时自动 `DROP/CREATE` 生产表；禁用 ORM schema 自动覆盖。
- Flyway/MySQL 9.7 兼容性须 Smoke Test（MySQL 官方版本不等于 Flyway 已认证版本）。失败先汇报，不悄悄关闭迁移机制。
- N+1 查询、无索引 WHERE、全表更新需要在 code review 标注；优化必须有 EXPLAIN 或指标。

## 6. Redis 与 Kafka 规范（在各自阶段生效）

**Redis：** Key 使用命名空间 `tokenx:模块:场景:标识`；同一活动的 Lua key 使用 `{activityId}` hash tag；设置合理 TTL；不随便使用 `KEYS *` 清理全库；Lua 必须 O(1) 并测试并发、补偿、重启和 key 丢失路径。Redis 只是缓存和准入快路径，不能单独宣称防超卖最终正确性。Redis 故障时只能在有界并发下回退 MySQL，禁止无保护的 fail-open。

**Kafka：** 订单异步化时 Kafka 是唯一消息主链路，禁止再用 Redis Stream 建第二条订单队列。选定 Topic 后明确 Key、版本化 Schema、消费者组、有限重试、DLT、幂等与 Offset 提交时机。Producer 使用 `acks=all` 和幂等发送；Consumer 在 MySQL 事务提交后提交 Offset，按至少一次消费设计。DB 与 Kafka 双写须说明故障窗口；采用 Outbox 时 outbox 记录与业务写入同一 MySQL 事务。重复业务效果由 DB 唯一约束/状态转移阻止，不能用 Kafka Exactly Once 代替数据库幂等。V1.5 之前不在主链路引入 Kafka。

**背压：** HTTP 工作线程、数据库连接池、Kafka Producer 缓冲和 Consumer 并发必须有界且有超时。容量不足时返回明确的 `429/503`，禁止用无限队列掩盖过载。入口 QPS、准入 QPS、订单 TPS 和端到端延迟按 `HIGH_CONCURRENCY_DESIGN.md` 分开测量。

## 7. 测试策略：务实的 TDD + 测试金字塔

### 单次功能的先后顺序

1. 写出成功场景、异常场景、并发/幂等场景的可执行断言。
2. 对**纯业务规则**尽量先写失败单测（Red），仅实现通过测试的代码（Green），然后清理重复（Refactor）。
3. 对 MySQL、Redis、Kafka 等外部边界使用真实组件的**隔离集成测试**；必要时先写最小接口测试确定契约。
4. 再做 React 最小交互验证；不把 Mock HTTP 响应当作系统端到端连通证明。
5. **禁止为追求 100% 覆盖率写无意义 getter/setter 单测**。优先覆盖关键业务分支和不变量。

### 推荐的最小测试矩阵

| 层 | 工具 | 必须覆盖 |
|---|---|---|
| 业务 Service 单测 | JUnit 5 + Mockito | 活动时段/权限/状态条件与金额规则 |
| Controller 合约测试 | Spring MVC Test + MockMvc | 参数校验、HTTP 状态、统一错误结构、鉴权 |
| MySQL 集成 | Testcontainers MySQL / 独立测试库 | 事务回滚、唯一索引、条件库存更新、并发 |
| Redis 集成（接入后） | Testcontainers Redis | Lua 原子操作、失效、重试与补偿 |
| Kafka 集成（接入后） | Testcontainers Kafka | 消费幂等、消息重复、失败重试、积压 |
| React | Vitest + Testing Library | 表单验证、API 错误展示、关键页面流 |
| 少量 E2E | Playwright（流程稳定后） | 登录→下单→支付→钱包可见 |

**隔离要求**：测试不能修改开发者真实的 `tokenmarket` 业务库、不能清空本地默认 Redis，也不能删除已存在 Kafka Topic。Testcontainers 不可用时测试应明确标记阻塞，不能假装已经验证；如获用户批准可使用单独的 `tokenx_test` 库。

### 测试命名和断言

- 建议 `shouldRejectDuplicatePurchaseForSameActivity` 等具有可读性的行为命名。
- 可用 `given/when/then` 结构，但不强行引入 BDD 框架。
- 测试时间使用可控 Clock（如果功能依赖当前时间）；并发测试断言最终库存、订单、余额，不只断言 HTTP 200。
- 性能测试必须固定到达率并报告业务拒绝与系统错误；每轮结束执行库存、资格、订单和账本对账，不能只看吞吐图。

## 8. 必须做 / 可做 / 不该做的设计模式

| 场景 | 推荐做法 | 触发条件 / 禁忌 |
|---|---|---|
| 多种折扣/赠送权益规则 | **Strategy 策略模式** | 真正有至少两种会持续变化的算法才引入；V1 一种秒杀价不需要策略接口 |
| 支付渠道多实现 | **Adapter 适配器** | 接入真实第三方支付时，隔离外部 SDK；V1 只有 MockPay 时无需接口工厂体系 |
| 订单状态处理 | **显式状态机 + 条件 UPDATE** | 重要且真实需要；先用 enum 和单一 Service 完成，无需 Spring StateMachine |
| Kafka 事件订阅 | **发布—订阅（Observer 思想）** | V1.5 开始使用，不人为引入自制事件总线 |
| 创建多种异构模型客户端 | **Factory 工厂模式** | AI Gateway 后续有多种 Provider 时再创建；V1 禁止空工厂 |
| 统一鉴权/请求过滤 | **Chain of Responsibility 已由 Spring Security FilterChain 提供** | 配置框架现成过滤链，禁止手搓重复链 |
| 稳定重复处理流程 | **Template Method 或组合** | 至少两个真正共享相同骨架但步骤不同的业务后再考虑；优先组合，慎用抽象基类 |
| 多级缓存 | **Cache-Aside 模式** | 存在明确热点读性能问题才引入，需有缓存一致性/失效测试 |
| 可靠领域事件 | **Transactional Outbox 模式** | DB 提交后的 MQ 可靠投递需求出现才引入 |

**评审问题**：如果删掉这个接口/抽象类/模式，代码是否更短、同样可测试？如果答案是“是”，默认删除；拒绝为了“有设计模式”增加无价值抽象。

## 9. 安全与变更控制

- 开发测试使用模拟支付，不请求真实资金、不伪装真实支付。
- JWT Secret、数据库密码、Redis 密码、API Key 必须通过 `.env`/环境变量/本地配置提供，`.gitignore` 忽略它们；示例配置只能用占位符。
- 禁止为了修权限错误而 `chmod 777`、关掉 Spring Security 或把服务公开到 `0.0.0.0`。
- 高风险命令（`rm -rf`、DB `DROP`/`TRUNCATE`、`FLUSHALL`、删除 Kafka Topic、重写历史）须经用户明确授权。
- 当出现不兼容、许可证不明、测试不通过，优先解释、记录和隔离问题；不为凑交付隐瞒。

## 9A. Git 版本控制与提交门禁

- **每次开始**：检查 `git status --short` 和所在分支；用户已有变更不可擅自覆盖、清理、stash 或混入提交。
- **每个功能点一条本地短期分支**，例如 `feature/F4-seckill-order`；不直接在 `main` 上长期开发，不引入多余 GitFlow 分支。
- 测试通过后执行 `git diff --check`，仅对本任务文件精确 `git add <path>`，再审查 `git diff --cached`，确保无明文密码、`.env`、产物或不相关改动。
- 使用 Conventional Commits：`feat(seckill): ...` / `fix(order): ...` / `test(wallet): ...` 等；一次提交应是可验证的最小逻辑单元。
- **AI 默认只允许本地提交当前功能，禁止自动 push/merge/rebase、强制推送或改写历史**；需要远程、合并、回滚等操作时先征得明确授权。
- 详细命令、初始化和故障处理：**`docs/GIT_WORKFLOW.md`**；仓库提供 `.gitignore`、`.gitattributes` 与轻量 PR 模板。

## 10. 每次任务报告模板（强制）

```text
任务：F?-? / 名称
范围：本次只实现……
参考：已审阅的黑马点评相关路径/官方文档
变更：文件路径与关键说明
测试：实际运行的命令 + PASS/FAIL/NOT RUN + 简短输出
验收：逐条对照本任务的 Done 条件
风险与遗留：……
Git：分支、Commit SHA、工作区最终状态；未提交/未 push 的说明
下一步：仅建议下一个最小任务；当前停止等待确认
```

## 11. Code Review 检查清单

- [ ] 本次是否只做了一个可验收的功能？
- [ ] 是否沿用仓库原有可理解的风格，而非新造个人体系？
- [ ] Controller / Service / Mapper 是否分离？
- [ ] DTO、鉴权、验证、异常是否一致？
- [ ] 幂等、库存、事务等关键不变量在数据库级是否有保障？
- [ ] 测试是否覆盖业务主路径与典型失败路径？有没有真实跑过？
- [ ] 是否存在无用途的设计模式、封装层、依赖？
- [ ] 是否保护敏感配置、避免破坏用户本地数据？
- [ ] 文档、迁移脚本、测试与代码是否一致？
- [ ] Git 分支、commit scope、暂存区内容已审查？无密钥、无无关改动、无未经授权的 push/merge？
