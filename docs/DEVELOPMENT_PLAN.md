# TokenX 单功能迭代计划（唯一实施顺序）

> 本计划采用纵向功能切片：一个任务必须能从业务输入走到预期输出，并具有自动化验收或明确的手工验收步骤。**只执行当前任务；通过后停下等待用户确认下一步。**
>
> 完成状态只按本文件中的验证记录认定；本地已安装基础设施本身不代表功能完成。

## 任务状态

- `[ ]` 未开始，`[x]` 完成（只有实际验证通过才勾选）。
- 允许状态注释 `BLOCKED` / `IN PROGRESS`，并记录真实阻塞；不允许假完成。
- 每个 Feature 可以再拆为 1–3 个小任务；**任何时候只认一个正在实施的任务**。

## Phase 0：代码仓库与基础兼容（此为 Qoder 第一次任务）

### [x] F0-1 建立最小可运行后端仓库

> 完成记录（2026-10-20，分支 `feature/F0-1-bootstrap`）：骨架、健康检查、测试、README 与
> `docs/REFERENCE_MAPPING.md` 已提交；`./mvnw clean verify` 3 个测试通过（含真实端口 HTTP 冒烟）。

**范围**：研究黑马点评的结构并写“参考映射”，建立 Java21、Spring Boot4.1.1、Maven Wrapper 的**单模块**最小后端，Spring MVC 一个 `/api/v1/health` 接口，规范 README、`.gitignore`、`.gitattributes`、`.editorconfig`，并依 `docs/GIT_WORKFLOW.md` 建立最小本地 Git 基线和 `feature/F0-1-bootstrap` 分支。不创建秒杀表、不接入 Kafka、不搭建管理后台。

**验收**：`./mvnw clean verify` 成功；`curl http://localhost:8080/api/v1/health` 返回预期；`mvn -version` 指向 Java21；明确列出实际参考的黑马点评仓库文件和适配差异；`git diff --check` 通过、仅提交本任务文件并报告本地 Commit SHA；不自动 push/merge。**做完停下**。

### [x] F0-2 最小依赖连通与迁移验证

> 完成记录（2026-10-20，分支 `feature/F0-2-db-migration`）：
> MySQL 9.7.2 + MyBatis-Plus 3.5.17 + Flyway 12.4.0 真实连通，`tokenx_test` 空库迁移至 v1 成功；
> 重跑 0 迁移、`flyway_schema_history` 仅 1 条记录；Redis 8.10.2 PING 通过；
> 版本锁定见 `docs/VERSION_LOCK.md`（Kafka 仅登记未接入）；`./mvnw clean verify` 9 个测试通过。

**范围**：只加 MySQL/MyBatis-Plus、Redis 必需依赖；用独立 test profile 连接真实组件。验证 MySQL9.7 和 Flyway 实际兼容，写最小迁移及回归测试；Redis 做 `PING` 级连接验证。Kafka 仅列入版本锁定文档，暂不接入代码。

**验收**：数据库迁移真正执行并重跑不会重复创建；最小 Mapper 集成测试 PASS；Redis 连接 PASS；运行时不要求 Redis/DB 密码暴露在 Git。若 Flyway 不兼容，**停止并报告**，不得自动换版本或删除迁移验证。

### [x] F0-3 数据库与高并发设计基线

> 完成记录（2026-10-20，分支 `feature/F0-3-db-design`）：Q1–Q8 与 C-1–C-12 全部定案；
> `PROJECT_SPEC.md`、`DATABASE_DESIGN.md` 与本计划统一；高并发目标采用 MySQL 同步基线 → Redis Lua 准入 → Kafka 唯一异步订单链路，详见 `HIGH_CONCURRENCY_DESIGN.md`。

**范围**：只收敛业务规则、表/字段/索引、事务锁序、接口标识、高并发演进、故障补偿、性能口径和迁移顺序；不建业务表、不写业务代码、不宣称压测成绩。

**验收**：不存在待拍板的 Q/C 项；Redis Stream 不再是候选订单主链路；入口 QPS、订单 TPS 和交易 TPS 口径分离；Q4/Q7/Q8 已给出可直接实施的 F1/F5 契约；文档全文交叉检查通过并完成本地原子提交。

## Phase 1：身份与套餐

### [x] F1 注册与登录

> 完成记录（2026-10-20，分支 `feature/F1-auth`）：
> 迁移 `V2__drop_schema_probe` / `V3__create_app_user` / `V4__create_token_account` 已应用；注册在同一事务内写
> `app_user` + 零余额 `token_account`，失败整体回滚；登录签发 HS256 JWT，鉴权全本地验签，`/users/me` 只读 claims。
> `./mvnw clean verify` 45 个测试通过（真实 MySQL `tokenx_test`）；前端 `pnpm lint` / `pnpm test`(6) / `pnpm build` 通过。
> 空库 bootstrap 用独立临时 MySQL 9.7.2 实例（端口 3307，验证后已销毁）实测：V1→V4 全部应用、二次启动
> `No migration necessary`、历史保持 4 条；本机应用账号无 `CREATE/DROP DATABASE` 权限，故该场景不进自动化套件，
> 复现命令：`mysqld --initialize-insecure --datadir=<tmp>/data` 启动实例后
> `TOKENX_JWT_SECRET=... TOKENX_DB_USERNAME=root TOKENX_DB_PASSWORD= ./mvnw spring-boot:run -Dspring-boot.run.arguments="--spring.datasource.url=jdbc:mysql://127.0.0.1:3307/<db>?...&createDatabaseIfNotExist=true"`。

**范围**：注册、密码哈希、登录、JWT 鉴权、`/users/me`；用户名大小写不敏感；用户角色与状态枚举。注册事务同时创建 `app_user` 与零余额 `token_account`，但钱包接口仍留到 F5。在 React 增加最小登录页面（允许样式极简）。

**验收**：有效登录可访问 `me`，未授权返回 401；`Alice`/`alice` 不能重复注册；禁用用户不能新登录；密码不明文入库；每个成功注册用户恰有一个零余额账户且任一插入失败整体回滚；JWT 热路径不逐请求查询用户表；前后端跑通；测试通过。

### [x] F2 Token 套餐

> 完成记录（2026-10-21，分支 `feature/F2-catalog`）：
> 迁移 `V5__create_token_package` 已应用（`status`/`credit_amount`/`list_price_cent` 三条 CHECK + `idx_package_status`）；
> 管理员创建套餐默认 `OFF_SHELF`，客户端传 `status` 被忽略；上下架只改状态、不删记录，重复同一状态幂等返回 200；
> 公共列表 `WHERE status=1 AND id<? ORDER BY id DESC LIMIT ?`（游标分页、`limit` 默认 20 上限 100、
> `limit+1` 判断下一页、不执行 `COUNT(*)`），详情只读 `ON_SALE`，不存在与已下架同为 `404 PRODUCT_NOT_FOUND`；
> `GET /products/**` 匿名可读、`/admin/**` 需 `ROLE_ADMIN`（未登录 401、普通用户 403，角色只取自 JWT claim）。
> `./mvnw clean verify` 74 个测试通过（真实 MySQL `tokenx_test`，含 2 个真实端口 HTTP 冒烟）；
> 前端 `pnpm lint` / `pnpm test`(10) / `pnpm build` / `pnpm format:check` 通过。
>
> 复审修复（2026-10-21 同日，同一分支）：上下架改为事务内 `SELECT ... FOR UPDATE` 锁定单个主键行后再判断，
> 修掉“条件 UPDATE 影响 0 行后仍读到 REPEATABLE-READ 旧快照”的缺陷（新增真实 MySQL 并发测试，旧实现会返回
> `OFF_SHELF` 而库里是 `ON_SALE`）；套餐额度/标价加上限 `1e9` / `1e11` 分并由 `V5.1` 追加 CHECK，使数值始终在
> JavaScript 安全整数范围内；管理端状态接口补 `{id}` 正整数校验；README 的游标分页表述改为“弱一致，不保证跨页快照”。
> 复测：`./mvnw clean verify` 82 个测试通过，前端 `pnpm lint`/`test`/`build`/`format:check` 通过。

**范围**：管理员创建/上下架套餐、游客读取列表与详情；React 简单列表。不要引入缓存。

**验收**：套餐字段校验、非管理员创建返回 403、停售套餐规则明确、测试通过。

### [x] F3 秒杀活动与库存初始化

> 完成记录（2026-10-21，分支 `feature/F3-seckill-activity`）：
> 迁移 `V6__create_seckill_activity` / `V7__create_activity_inventory` 已应用（活动表 6 条 CHECK +
> `idx_activity_list(status,start_at)`；库存表仅主键 + `available+reserved<=total`），无物理外键、无 `version`、
> 无 `sold_stock`、无 Redis/Kafka/订单字段。
> 管理端 `POST /admin/activities` 固定创建 `DRAFT` 并在同一事务写库存 `N/N/0`（额度快照取自套餐、客户端字段忽略）；
> `PATCH /admin/activities/{id}/status` 只允许 `DRAFT→PUBLISHED`、`DRAFT→OFFLINE`、`PUBLISHED→OFFLINE`，
> 重复同状态幂等 200，`OFFLINE` 为终态，其余 409 `ACTIVITY_STATE_CONFLICT`；发布时用 `FOR SHARE` 重读套餐
> （须仍 `ON_SALE`，否则 409 `PACKAGE_NOT_AVAILABLE`）、要求 `endAt > now` 并刷新额度快照；
> 状态迁移用事务内 `SELECT … FOR UPDATE` 锁单个活动主键行。
> 公开 `GET /activities/{id}` 匿名可读，SQL 内 `WHERE a.status=1` 一次主键 JOIN 活动与库存，不存在/草稿/已下架
> 统一 404 `ACTIVITY_NOT_FOUND`；安全规则按 `GET /api/v1/activities/**` 放行，`POST` 同前缀仍要求认证。
> 运行态由注入的 UTC `Clock`（统一截断到毫秒）按 `[startAt,endAt)` 计算，`soldOut` 独立由 `availableStock==0` 得出。
> `./mvnw clean verify` 128 个测试通过（新增 46 个，真实 MySQL `tokenx_test`，含并发发布/下架、强制第二次 INSERT
> 失败的整体回滚、`FOR UPDATE` 阻塞与 Clock 边界断言）；前端 `pnpm lint` / `test`(17) / `build` / `format:check` 通过。

**范围**：管理员为套餐配置开始/结束时间、活动价、库存、每人限购；活动详情页。**这一步不提供秒杀下单。**

**验收**：`end_at` 必填且 `start_at < end_at`；负库存被拒绝；活动在未来/进行中/已结束状态正确；活动初始库存准确；发布后的价格、额度和总库存不可修改；测试通过。

## Phase 2：完整同步交易闭环（最重要）

### [x] F4 同步秒杀下单（MySQL 基线）

> 完成记录（2026-10-21，分支 `feature/F4-sync-seckill-order`）：
> 迁移 `V8__create_purchase_order` 已应用：16 列（`order_no CHAR(26) ascii_bin`、`request_id BINARY(16)`、
> 可空 `activity_id`、`order_type`、价格/额度快照、`status`、`expires_at`/`paid_at`/`closed_at`）、
> 5 个二级索引（`uk_order_no`、`uk_user_request(user_id,request_id)`、`uk_activity_user(activity_id,user_id)`、
> `idx_order_user`、`idx_order_timeout(status,expires_at)`）与 8 条 `CHECK`；无物理外键、无 `version`、
> 无 Redis/Kafka 列、无序列表。
> `POST /activities/{id}/orders` 无请求体，`Idempotency-Key` 必须是规范 UUID（否则 400），身份只取自 JWT；
> 本次创建 `201`，同键重放 `200` 且不再扣库存；错误码为 `ACTIVITY_NOT_FOUND`(404)、
> `ACTIVITY_NOT_ACTIVE`/`ACTIVITY_SOLD_OUT`/`ALREADY_PURCHASED`/`IDEMPOTENCY_KEY_REUSED`(409)。
> 事务顺序：查 `(user_id,request_id)` → 活动主键 `FOR SHARE` → 校验 `PUBLISHED` 且 `now∈[start,end)`
> → 备好单号/截止时间/快照 → 条件 `UPDATE … WHERE available_stock>0` → `INSERT` → 提交后返回；
> 唯一键冲突整体回滚，再在事务之外用只读事务判定重放/换键/一人一单，订单号撞号有限次重试。
> `order_no` 为 26 位 Crockford Base32（毫秒时间前缀 + 80 bit 随机尾）；`expires_at = created_at +`
> `tokenx.order.payment-timeout`（默认 `15m`，启动校验正数且 ≤24h），创建时固化。
> `GET /orders/{orderNo}` 只查本人订单，不存在与他人订单同为 404；自增 `id`/`user_id`/`request_id`/
> 数字状态码不出现在任何响应中。React 活动页新增“立即抢购”（仅 `ACTIVE` 且未售罄可点，未登录跳登录页，
> 网络失败重试复用同一幂等键），并新增订单详情页（服务端快照，无支付按钮、无倒计时）。
> `./mvnw clean verify` 179 个测试通过（新增 51 个，真实 MySQL `tokenx_test`，含并发不超卖、并发幂等、
> 强制订单插入失败后库存回滚、`FOR SHARE` 被 `FOR UPDATE` 阻塞后读到最新状态）；前端
> `pnpm lint` / `test`(28) / `build` / `format:check` 通过。

**范围**：用户在活动时间内下单；MySQL 条件扣可用库存 + 建立待支付订单 + 唯一约束，形成可靠同步版本；生成不可枚举 `order_no`，默认 15 分钟超时（配置化）；按 `orderNo` 查询订单。

**验收**：售罄时不能生成有效订单；同一用户不能重复抢；并发请求下总订单数不超过初始库存；输入 `Idempotency-Key` 的重试语义正确；React 活动页可下单、订单页可见；测试通过。

**明确禁止**：此时不得使用 Redis Lua、Kafka 或消息队列作为订单正确性的必要依赖。先记录基础接口吞吐与功能正确性，不需要进行正式高压性能调优。

### [ ] F4.5 普通购买下单

**范围**：新增 `POST /api/v1/orders` 与 `NormalOrderService`；校验在售套餐并把价格、额度快照写入既有 `purchase_order`。不碰活动库存，不另建订单表，不引入策略模式。

**验收**：普通套餐可重复购买；同一 `Idempotency-Key` 不重复建单且参数变化被拒绝；停售套餐不能下单；普通与秒杀订单使用同一查询和后续状态机；测试通过。

### [ ] F5 模拟支付与钱包入账

**范围**：创建 `token_ledger`；复用 F1 已建立的 `token_account`；订单 `UNPAID → PAID`；同一个本地 DB 事务内结转秒杀预占库存、更新账户和写入流水；React 展示模拟支付按钮和余额。

**验收**：同一订单重复触发支付只入账一次；未登录不能支付他人订单；不允许客户端指定订单价格/到账 Token 数；MySQL 流水与余额一致；全链路演示成功。

### [ ] F6 订单超时关闭与释放库存

**范围**：超时订单 `UNPAID → CLOSED`；仅秒杀订单释放预留库存；本地调度器按 `(status,expires_at)` 扫描，使用 `FOR UPDATE SKIP LOCKED` 和单订单短事务支持多 Worker；页面更新状态。

**验收**：已支付订单不会被关闭；同一超时任务重复执行只释放一次；关单和支付竞争时最终只接受一个合法状态；有模拟时钟/可控测试方法。

**V1 完成门槛**：能够新建用户、套餐、活动、秒杀或普通购买、支付、查看钱包；并发下不超卖；所有功能有测试，文档能从干净数据库完整重建。

## Phase 3：仅在 V1 完成后优化秒杀

### [ ] F7 热读缓存与同步链路保护

**范围**：选择实测高频的套餐或活动读取路径，实施 Cache-Aside + TTL 抖动 + 主动失效；增加有界并发、超时与 429/503 过载语义，为后续 Redis 准入失败时保护同步 MySQL 链路。

**验收**：缓存命中、穿透与更新一致性测试通过；Redis 故障只能限量回退 MySQL，不能无保护 fail-open；优化前后压测口径相同。

### [ ] F8 事件模型与 Kafka Outbox

**范围**：先在一个低风险事务后事件（如订单支付完成后的异步通知/统计）中加入 Outbox + Kafka；定义版本化消息 Schema、Topic Key、Producer、Consumer、有限重试/DLT、幂等及 offset 提交时机。这是事务后事件链路，不承担秒杀下单命令。

**验收**：断开 Kafka / 消费者重启后，事件不丢失且不会造成重复业务效果；消费者错误有可见重试/积压状态；不影响支付入账的本地事务。

### [ ] F9 Redis Lua 准入与 Kafka 异步下单

**范围**：按 `HIGH_CONCURRENCY_DESIGN.md` 实施已决方案：Redis Lua 原子准入与可重复补偿；Kafka `tokenx.seckill-order-commands.v1` 是唯一订单队列；`seckill_request` 保存最终结果；Worker 以至少一次语义落 MySQL。保留 F4 同步实现作基线，通过 `tokenx.seckill.mode` 与异步入口互斥开放，不做故障时静默切换，不引入 Redis Stream。

**验收**：仅在 Broker ACK 后返回 202；同一活动同分区有序、不同活动可并行；重复消息不重复建单；Kafka/MySQL/Worker 故障恢复后可收敛；Redis 重启时活动先暂停并重建，不按总库存盲目开放；功能与并发正确性不退化；区分“请求受理”和“订单创建成功”。

### [ ] F10 监控、压测与故障注入

**范围**：按 `HIGH_CONCURRENCY_DESIGN.md` 的固定场景执行 k6 与故障注入；分别报告入口/准入 QPS、订单/交易 TPS、HTTP P95/P99、Kafka lag/最老消息年龄、订单排队耗时、数据库写入与锁等待；加入观测而非为了仪表盘引入中间件。

**验收**：报告中写清机器规格、并发用户数、持续时间、库存量、指标含义、优化前后对比，以及正确性核查；禁止编造数字。

## Phase 4：未来可选（每项需单独立项）

- **F11 AI API 网关**：真实调用代理、配额预留与结算（需明确费用来源、可核对用量、失败语义）。
- **F12 签到、社区**：只有核心交易系统稳定且用户确认需要时添加。
- **F13 部署优化**：CI、Docker Compose、可观测性、生产安全配置。

## 每个任务的通用 Done 标准

1. 本任务业务验收条件满足，没做超范围功能。
2. 构建、相关单测、集成测试有实际 PASS 结果，完整命令与输出可查。
3. 至少一个主要失败路径测试、无明显回归。
4. 必要的 schema/接口/README 已更新，敏感值未入 Git。
5. AI 按 `docs/GIT_WORKFLOW.md` 完成本地 Git 提交（或说明未提交原因），报告分支、Commit SHA、最终 `git status`；**停下等待下一条明确任务指令**。

## 初次交给 Qoder 的简短指令（历史归档）

> F0-1～F0-3、F1、F2、F3、F4 已完成，不得再次执行这段初始提示。后续代理从本文件第一个未完成任务开始，当前为 F4.5。
