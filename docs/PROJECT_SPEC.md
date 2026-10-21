# TokenX — 项目需求与技术设计（V1 设计基线）

**状态**：已决设计基线；不是已经实现的功能清单。数据库细节以 `DATABASE_DESIGN.md` 为准，高并发演进以 `HIGH_CONCURRENCY_DESIGN.md` 为准。\
**定位**：Java 21 + Spring Boot 4 重构 AI Token 套餐限时交易平台；以黑马点评的秒杀实现思想为基线，逐步建立能演示和验证的交易闭环。\
**主要目标**：做出完整、正确、可测试的后端系统；在有同步性能基线后，再进行 Redis/Kafka 优化。\
**原则**：单体优先、交易正确性优先、需求驱动技术、先跑通再优化。

## 1. 产品边界

### 1.1 V1 必须实现（端到端）

1. 用户：注册/登录、当前用户查询、基本权限控制（用户/管理员）。
2. Token 套餐：商品列表、详情、管理员管理套餐。
3. 秒杀活动：活动配置、开始/结束、库存、每人限购一份；活动状态由服务端时间决定。
4. 秒杀下单：资格检查、库存约束、一人一单、订单查询。
5. 模拟支付：订单待支付→支付成功/超时关闭；**明确标注模拟**，不接第三方真实支付。
6. Token 钱包：支付完成后入账、查询余额和账本流水；同一订单绝不重复入账。
7. 基础前端：登录、套餐/秒杀页面、订单页、钱包页、基础管理员页面。
8. 文档与测试：接口契约、运行说明、单元/集成测试和业务正确性验证。
9. 普通购买：与秒杀使用不同接口和 Service，但共用订单、支付、关单与钱包模型。

### 1.2 V1 稳定后再做（V1.5）

- Redis 热点读取缓存与 Lua 抢购准入；Redis 是快路径，MySQL 是最终库存与资格权威。
- Kafka 作为唯一异步下单队列；Transactional Outbox 只用于数据库事务后的下游事件。**不使用 Redis Stream 作为第二条订单队列。**
- 简单限流、指标采集、压测和同步/异步方案对比。
- Redis Stream 只作为黑马点评参考实现的研究对象，不进入 TokenX 订单链路。

### 1.3 非 V1 目标（明确禁止提前开发）

真实支付、真实模型 API 代理、精确 LLM 用量计费、多租户、社区/点赞/关注/签到、微服务、分布式事务框架、分库分表、搜索引擎、Kubernetes。它们可写入 Backlog，但不能阻挡 V1 交付。特别说明：V1 中“Token”是平台虚拟额度，不等同于上游模型的输入/输出 Token 数量；后续接入 API 时另设计定价映射。

## 2. 技术栈与版本约束

| 层 | 决策 | 依赖管理/约束 |
|---|---|---|
| JDK | Temurin Java 21 LTS | 现有本机环境已安装；`mvn -version` 必须使用 21 |
| 后端 | Spring Boot **4.1.1**、Spring Framework 7 | Spring Boot BOM/Parent 管理 |
| 构建 | Maven 3.9.x + Maven Wrapper | 仓库提交 `.mvn/wrapper`、`mvnw` |
| Web | Spring MVC（Boot 4 的 WebMVC Starter） | 不因为流式调用预先切换 WebFlux |
| ORM | MyBatis-Plus `mybatis-plus-spring-boot4-starter` **3.5.17** | MyBatis 相关依赖避免重复引入；复杂库存 SQL 使用显式 SQL |
| 数据库 | MySQL **9.7.x LTS** | 当前本机为 9.7.2；MySQL JDBC Driver 随 BOM 管理 |
| 数据库迁移 | Flyway **12.4.0** | F0-2 已在本机 MySQL 9.7.2 完成首次迁移和幂等重跑；后续每个业务迁移仍需真实集成测试 |
| Redis | **8.10.x**，Spring Data Redis/Lettuce | 本地 127.0.0.1，无密码；后续部署要求独立 ACL/认证 |
| Kafka Broker | **4.3.1** KRaft | V1.5 接入；Spring Kafka 与 kafka-clients 交由 Boot BOM 管理，不要求客户端同 Broker 小版本 |
| 认证 | Spring Security + JWT | Token 生命周期从简单可靠开始；无第三方 OAuth V1 |
| 前端 | React 19 + TypeScript 5.x + Vite | 首次安装通过 package.json + pnpm-lock.yaml 固定精确版本 |
| 运行环境 | Node.js **26.9.0**、pnpm **12.x** | 用户本机已用 Node26；不擅自切换 LTS |
| UI | Ant Design（按页面需求引入） | Router 使用 React Router；数据加载可用 TanStack Query，先不强制 Zustand |
| 测试 | JUnit、Spring Boot Test、Mockito、Testcontainers、Vitest | 集成测试须用真实依赖的隔离实例；Mock 不能替代关键 DB 并发测试 |
| 观测 | Spring Boot Actuator；Micrometer 后加 | 只有真实需要时再增加 Prometheus/Grafana |

**不预装**：Spring Cloud、Nacos、Seata、Sentinel、Redisson、Elasticsearch、ShardingSphere、RabbitMQ、Canal、Kubernetes、Quartz。出现明确需求后需有简要技术决策记录（ADR）和用户确认。

**版本核验门禁**：F0-2 已验证 Java21+Boot4.1.1+MyBatisPlus3.5.17 对 MySQL9.7、Redis8.10 的真实连接及 Flyway 12.4.0 迁移；F8 开始前仍须验证 BOM 管理的 Kafka Client 与 Broker 4.3.1 的真实连接。版本不兼容只能形成 issue/ADR，不能自行强制覆盖 BOM 依赖。

## 3. 模块职责（单体，按业务分包）

- **auth**：身份验证、登录、鉴权、获取当前用户。
- **catalog**：套餐定义、展示、管理员上下架。
- **activity**：秒杀活动配置、活动时段、可用库存、限购规则。
- **order**：订单创建与查询、待支付状态、关闭订单。
- **payment**：模拟支付确认；对接订单状态机，**不产生真实资金流**。
- **wallet**：Token 余额、充值流水、查询。
- **messaging（V1.5）**：Outbox、Kafka Producer/Consumer、幂等与重试。
- **infrastructure**：通用的安全、异常转换、MyBatis 配置、外部资源适配；不能成为所有业务逻辑的垃圾桶。

依赖关系：Controller → Application/Service → Mapper；Service 之间按业务能力调用；不允许 Controller 直接访问 Mapper；不同模块不能直接修改对方实体。基础版允许必要的 Service 调用，不为了松耦合过早抽象内部事件总线。

## 4. 用户角色与主路径

| 角色 | 能力 | V1 页面 |
|---|---|---|
| 游客 | 查看可见套餐和活动 | 列表、详情 |
| 已登录用户 | 秒杀、查单、模拟支付、看余额流水 | 登录、活动、订单、钱包 |
| 管理员 | 创建套餐、活动、库存、上下架 | 简易管理后台 |

### 核心用户旅程

1. 管理员新建 Token 套餐（售价和赠送额度使用整数最小单位）并发布限时活动；配置活动时间、库存和限购量。
2. 游客查看活动；登录用户在服务端活动时间内发起秒杀请求。
3. 系统创建唯一的待支付订单；售罄、活动未开始、已抢过均返回明确业务错误。
4. 用户执行模拟支付；系统将订单合法改为 PAID，给钱包追加购买额度及独立流水。
5. 用户在订单页能看到最终状态、钱包页能看到累计额度和来源流水。
6. 未支付订单到期由服务端关闭并释放预留库存（仅释放一次）；已支付订单不能再关单。

### 业务规则（V1 固定，变更需讨论）

- 每个用户每场秒杀活动**最多获得一次抢购资格**；订单关闭或超时后也不可重抢，由数据库唯一键兜底。
- 商品套餐与活动是不同对象：活动可指向一个套餐，活动价格和库存是活动快照，不随着套餐变更回溯改变历史订单。
- 金额全部用整数最小货币单位（示例：分），Token 额度全部用整数单位，禁止 double/float 表达余额和价格。
- 套餐额度与标价另有服务端上限（`credit_amount ≤ 1_000_000_000`、`list_price_cent ≤ 100_000_000_000` 分），使 HTTP 响应中的数值始终落在 JavaScript 安全整数范围内；JSON number 字段的精度约定见 `DATABASE_DESIGN.md §4.2`。活动价与活动额度快照复用同一组上限（`infrastructure.money.AmountLimits`）。
- 一次下单对应一个活动和一个套餐；不支持购物车、组合优惠。
- 秒杀活动必须有结束时间，时间窗为 `[start_at,end_at)`；长期常规售卖走普通购买，不用空结束时间模拟。
- 待支付订单默认 15 分钟超时，时长配置化，订单创建时把最终截止时间快照到 `expires_at`。
- 订单超时由服务端时间决定；数据库统一以 `DATETIME(3)` 存 UTC，HTTP 日期序列化为 ISO8601。
- 用户名大小写不敏感；`Alice` 与 `alice` 是同一账号。`app_user.status` 保留，V1 登录时拒绝禁用用户；热请求不逐次查用户表，已签发 JWT 最迟在过期时失效。
- V1 模拟支付仅供本地演示，不允许宣传为生产级支付平台。
- 抢购成功和支付成功是两个不同结果，文案与 API 必须区分。

## 5. 状态与业务不变量

### 5.1 订单状态

`UNPAID` → `PAID`；`UNPAID` → `CLOSED`。其他状态转移一律拒绝。V1 不做复杂退款。支付与超时关单都必须有 `WHERE status='UNPAID'` 条件更新并检查影响行数。

### 5.2 库存语义

活动库存只持久化 `available_stock`（可再预占）与 `reserved_stock`（待支付订单占用）；已售量由 `total_stock - available_stock - reserved_stock` 计算，不落 `sold_stock`。

不变量：`available_stock >= 0`，`reserved_stock >= 0`，`available_stock + reserved_stock <= total_stock`；对应的已支付秒杀订单数必须等于 `total_stock - available_stock - reserved_stock`。库存状态更新和订单变动必须在同一数据库事务中完成，且利用条件 UPDATE。

### 5.3 钱包语义

- 每个用户一个账户，余额不能为负。
- 每笔入账对应唯一业务标识 `source_type + source_id + action`。
- 更新余额和写入不可变流水必须在一个 DB 事务里。
- 同一订单重复支付回调、重复业务调用不允许导致多次入账。

### 5.4 活动状态（配置态与运行态分离）

配置态 `status` 只有三个值：`DRAFT(0)`、`PUBLISHED(1)`、`OFFLINE(2)`，只有三条合法迁移：

```text
DRAFT ──发布──> PUBLISHED ──下架──> OFFLINE
  └──────────取消草稿──────────────> OFFLINE
```

- 新建活动固定为 `DRAFT`；重复请求当前状态幂等返回 `200`，不执行无意义 UPDATE。
- `OFFLINE` 是终态，不可重新发布；任何状态都不能回到 `DRAFT`。非法迁移返回 `409 ACTIVITY_STATE_CONFLICT`。
- 发布时重新读取套餐：套餐必须仍为 `ON_SALE`（否则 `409 PACKAGE_NOT_AVAILABLE`），且 `endAt > 当前时间`；同时用套餐当前额度刷新 `credit_amount_snapshot`。
- 发布后活动价、额度快照、起止时间与总库存不可修改；下架只改配置态，不删除活动、不删除库存、不重置库存。
- 运行态 `UPCOMING / ACTIVE / ENDED` **不落库**，由注入的服务端 `Clock` 按半开区间 `[startAt, endAt)` 计算；`soldOut` 单独由 `availableStock == 0` 得出，不是时间状态。
- 时间一律以 UTC 存储（`DATETIME(3)`）和传输（ISO-8601 带 `Z`），比较前统一截断到毫秒，避免 Java 纳秒与数据库毫秒之间的漂移。
- 每人每活动最多一单是项目固定规则（F4 用唯一键落实），活动响应返回固定 `purchaseLimit = 1`，不为此增加只能取 1 的数据库列。

## 6. 预期数据库模型（按阶段创建，不要一次全建）

| 表（建议名） | 最重要字段 / 唯一性 | 创建阶段 |
|---|---|---|
| `app_user` | `id`, `username` UNIQUE（大小写不敏感）, `password_hash`, `role`, `status` | 用户与登录 |
| `token_package` | `id`, `name`, `credit_amount`, `list_price_cent`, `status` | 套餐管理 |
| `seckill_activity` | `id`, `package_id`, `start_at`, `end_at` NOT NULL, `activity_price_cent`, `credit_amount_snapshot`, `status` | 活动管理 |
| `activity_inventory` | `activity_id` PK, `total_stock`, `available_stock`, `reserved_stock`；已售量由前三者计算 | 同步秒杀 |
| `purchase_order` | 内部 `id`, 对外 `order_no` UNIQUE, `(user_id,request_id)` UNIQUE, `(activity_id,user_id)` UNIQUE，价格/额度快照与 `expires_at` | 同步秒杀与普通购买 |
| `token_account` | `user_id` PK, `available_balance`, `updated_at`；与用户同事务创建 | 用户与登录（F1 建表，F5 开放钱包能力） |
| `token_ledger` | `id`, `user_id`, `biz_type`, `biz_id`, `effect_type`, `delta`; UNIQUE `(biz_type,biz_id,effect_type)` | 钱包 |
| `outbox_event` / `processed_message` | 事务后事件可靠发布与消费幂等 | F8（V1.5） |
| `seckill_request` | 异步准入的最终结果、`request_id` 与 `order_id` | F9（V1.5） |

V1 明确不建 `purchase_qualification` 和模拟 `payment_record`：前者由订单唯一键表达，后者没有外部支付事实可记录。`token_account` 在 F1 与 `app_user` 一同引入并由同一注册事务创建，避免 F5 回填竞态和支付时懒创建。SQL 类型、索引与迁移顺序见 `DATABASE_DESIGN.md`。

## 7. REST API 初稿（不要一次实现全部）

前缀：`/api/v1`；返回稳定 JSON 格式，错误包含 `code`、`message`、`requestId`。字段以代码中的 OpenAPI 契约为准。

| HTTP | URI | 用途 | 阶段 |
|---|---|---|---|
| POST | `/auth/register` | 注册 | F1 |
| POST | `/auth/login` | 登录 | F1 |
| GET | `/users/me` | 当前用户 | F1 |
| GET | `/products` | 套餐分页列表（仅 `ON_SALE`，游标 `cursor` + `limit`） | F2 |
| GET | `/products/{id}` | 套餐详情（仅 `ON_SALE`；不存在与已下架同为 `404 PRODUCT_NOT_FOUND`） | F2 |
| POST | `/admin/products` | 新增套餐（管理员；默认 `OFF_SHELF`） | F2 |
| PATCH | `/admin/products/{id}/status` | 套餐上架/下架（管理员；重复同一状态幂等成功） | F2 |
| POST | `/admin/activities` | 新增秒杀活动（管理员；固定 `DRAFT`，同事务初始化库存） | F3 |
| PATCH | `/admin/activities/{id}/status` | 活动发布/下架（管理员；重复同一状态幂等成功） | F3 |
| GET | `/activities/{id}` | 活动详情、运行状态和库存展示（仅 `PUBLISHED`；不存在/草稿/已下架同为 `404 ACTIVITY_NOT_FOUND`） | F3 |
| POST | `/activities/{id}/orders` | 秒杀下单。**无请求体**，Header 必须带规范 UUID 的 `Idempotency-Key`；本次创建 `201`，同一键重放 `200`（不再次扣库存）。失败：`400 VALIDATION_FAILED`、`404 ACTIVITY_NOT_FOUND`、`409 ACTIVITY_NOT_ACTIVE`/`ACTIVITY_SOLD_OUT`/`ALREADY_PURCHASED`/`IDEMPOTENCY_KEY_REUSED` | F4 |
| GET | `/orders/{orderNo}` | 按不可枚举对外单号查询**自己的**订单；不存在与他人订单同为 `404 ORDER_NOT_FOUND`，格式非法 `400 VALIDATION_FAILED` | F4 |
| POST | `/orders` | 普通购买；Header `Idempotency-Key` | F4.5 |
| POST | `/orders/{orderNo}/mock-pay` | 模拟支付 | F5 |
| GET | `/wallet` | 查询余额 | F5 |
| GET | `/wallet/ledger` | 流水分页 | F5 |
| POST | `/activities/{id}/order-requests` | V1.5 异步秒杀受理，成功返回 202 | F9 |
| GET | `/order-requests/{requestId}` | 查询异步请求最终结果 | F9 |

认证：`Authorization: Bearer <JWT>`；用户身份从认证上下文取，不允许从请求体传 `userId` 来决定交易归属。活动时间、库存、价格等由服务端决定。

匿名可访问的只有：健康检查、`/auth/**`、`GET /products/**`、`GET /activities/**`。`/admin/**` 一律要求 `ROLE_ADMIN`（未登录 401，普通用户 403），角色只来自已验签的 JWT claim。活动详情的放行规则必须绑定 HTTP Method：F4 的 `POST /activities/{id}/orders` 在同一路径前缀下，绝不能继承匿名访问。

### 状态码约定

- 200：读或幂等重试返回已有结果；F4 的同一 `Idempotency-Key` 重放也返回 200（表示“没有再次扣库存”）。
- 201：本次请求真的创建了订单；F9 的独立异步入口返回 202 + `requestId`，不改变 V1 同步接口语义。
- 400：非法参数；401：未登录；403：无权限；404：资源不存在；409：重复资格/状态冲突；429：限流；500：预期外故障。
- 同一 `Idempotency-Key` 携带不同参数必须拒绝，不允许静默复用（F4 返回 `409 IDEMPOTENCY_KEY_REUSED`，且不消耗另一活动库存）。

### 7.1 下单语义（F4，MySQL 强一致基线）

- **正确性只由数据库决定**：条件 `UPDATE activity_inventory … WHERE available_stock > 0` 的影响行数是唯一的售罄判据；`uk_user_request` 保证请求幂等，`uk_activity_user` 保证一人一活动一单。Redis、Kafka、消息队列与内存队列都不在 F4 的正确性路径上。
- **事务顺序固定**：查 `(user_id, request_id)` → 活动主键 `FOR SHARE` → 校验 `PUBLISHED` 且 `now ∈ [start_at, end_at)` → 准备单号/截止时间/快照 → 条件扣库存 → `INSERT` 订单 → 提交 → 返回。
- **唯一键冲突必须整体回滚**，然后在事务之外判定原因：同键同活动返回原订单 `200`，同键异活动 `409 IDEMPOTENCY_KEY_REUSED`，`(activity_id, user_id)` 命中 `409 ALREADY_PURCHASED`，都未命中则换订单号有限次重试。绝不允许吞掉冲突后继续提交（那会留下“扣了库存但没有订单”）。
- **已有订单优先于活动状态**：重放判定在活动校验之前，活动结束或下架后仍能取回原订单。
- **订单只有 `UNPAID`**：支付（F5）与超时关闭/释放库存（F6）不在本阶段；`expires_at` 由 `expires_at = created_at + tokenx.order.payment-timeout` 在创建时固化。
- 并发集成测试测的是正确性（成功数 = 初始库存、库存不为负、失败只返回稳定业务码），**不是**压测数据。

## 8. 运行时拓扑（V1）

```text
React (Vite dev server :5173)
    └── HTTP /api → Spring Boot (:8080)
                          ├── MySQL (:3306) 交易真相源
                          └── Redis (:6379) V1 只在必要时使用
Kafka (:9092) 本地已安装，但在 V1.5 前不参与核心链路
```

开发环境约束：本机 MySQL root 有密码（业务应用应使用本地专用账号）；Redis 当前无密码，只能监听回环地址；Kafka 开发默认本机连接。密码只来自用户本地环境变量或未提交的 `application-local.yml`，绝不放到 Git。

## 9. 运行时拓扑（V1.5 已决目标，按计划分阶段实现）

```text
Client → API → Redis Lua 原子准入 → Kafka command topic → Order Worker
                                                        ↓
                                               MySQL 订单/库存事实
                                                        ↓
                          MySQL Outbox → Kafka event topic → 独立下游
```

技术说明：Redis 准入、Kafka 写入和 MySQL 落单不存在跨系统原子事务；系统以 `requestId`、数据库唯一键、至少一次消费、补偿和对账收敛。只有 Kafka Broker 确认后才返回“已受理”，只有 MySQL 提交后才返回“已成单”。Redis Stream 不进入本项目订单主链路。完整故障矩阵、背压与指标见 `HIGH_CONCURRENCY_DESIGN.md`。

V1.5 用配置 `tokenx.seckill.mode=SYNC|ASYNC` 选择唯一秒杀写入口；同一运行环境不得同时开放同步建单和异步受理，防止绕过 Redis 准入。依赖故障不会自动切换模式。

## 10. 基础非功能要求

- 正确性：并发库存不为负、同一用户不重复获得资格、模拟支付不重复入账、关单不与支付同时成功。
- 安全性：权限检查、密码哈希、JWT 验证、校验入参、脱敏日志、不信任客户端价格与 userId。
- 可维护性：业务包结构稳定、DTO 和实体隔离、唯一异常映射、数据库约束与代码一致。
- 测试：业务规则单测、MySQL 真实集成测试、接口切片测试、最小前端流程测试；压测仅在 V1 功能闭环后做。
- 性能与韧性：所有线程池、连接池、Producer 缓冲和队列有界；超载明确返回 429/503；入口 QPS、订单 TPS 与排队延迟分开报告。
- 可观测性：接口 requestId、错误日志、基础健康检查；V1.5 增加 Redis Lua 结果、Kafka lag/最老消息年龄、DB 锁等待与业务对账指标，不在初期引入完整链路追踪堆栈。
- 兼容性：Mac 开发、JDK21、Spring Boot4；Node26 + pnpm12；禁用 `latest` 漂移版本。

## 11. 非目标与避免过度设计

- 不需要 CQRS、Event Sourcing、DDD 全家桶。可借用聚合边界思想，但不要引入多层无意义模型映射。
- 不强制所有 Service 定义一个 Interface；无第二实现且不需要隔离边界时可直接使用具体类。
- 不因为“需要 Kafka”就要求全部方法异步化。
- 不为 1 个活动提前做库存分片、热点 Key 分片、分库分表。
- 不要做完整购物车、优惠叠加、积分商城、第三方 OAuth、后台图表大盘以阻挡交易闭环。
- 设计模式以实际变化点为由；详见 `ENGINEERING_STANDARDS.md`。

## 12. 项目完成定义（V1）

本地可以从零初始化环境，完成：注册登录 → 创建套餐 → 创建活动 → 秒杀或普通购买 → 订单查询 → 模拟支付 → 钱包入账和流水 → 重复支付/超时关闭/超卖测试。启动与测试步骤可复现。此后才能进入 Redis/Kafka 性能优化阶段；V1.5 的高并发完成定义见 `HIGH_CONCURRENCY_DESIGN.md` §2 和 §7。
