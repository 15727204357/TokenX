# TokenX — 项目需求与技术设计（V1 设计基线）

**状态**：设计文档；不是已经实现的功能清单。\
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

### 1.2 V1 稳定后再做（V1.5）

- Redis 热点套餐缓存（以真实查询路径为依据），Redis Lua 抢购准入。
- Kafka 异步订单链路、Transactional Outbox、幂等消费、补偿及对账。
- 简单限流、指标采集、压测和同步/异步方案对比。
- 只有确有需求时才做 Redis Stream；不要因为黑马点评有 Stream 就与 Kafka 叠加两条主消息链路。

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
| 数据库迁移 | Flyway（候选，须先做兼容验证） | Flyway 官方已验证 MySQL 版本未明确覆盖 9.7，先做迁移 Smoke Test；失败则记录阻塞并报用户选择，不悄悄替换版本/关闭校验 |
| Redis | **8.10.x**，Spring Data Redis/Lettuce | 本地 127.0.0.1，无密码；后续部署要求独立 ACL/认证 |
| Kafka Broker | **4.3.1** KRaft | V1.5 接入；Spring Kafka 与 kafka-clients 交由 Boot BOM 管理，不要求客户端同 Broker 小版本 |
| 认证 | Spring Security + JWT | Token 生命周期从简单可靠开始；无第三方 OAuth V1 |
| 前端 | React 19 + TypeScript 5.x + Vite | 首次安装通过 package.json + pnpm-lock.yaml 固定精确版本 |
| 运行环境 | Node.js **26.9.0**、pnpm **12.x** | 用户本机已用 Node26；不擅自切换 LTS |
| UI | Ant Design（按页面需求引入） | Router 使用 React Router；数据加载可用 TanStack Query，先不强制 Zustand |
| 测试 | JUnit、Spring Boot Test、Mockito、Testcontainers、Vitest | 集成测试须用真实依赖的隔离实例；Mock 不能替代关键 DB 并发测试 |
| 观测 | Spring Boot Actuator；Micrometer 后加 | 只有真实需要时再增加 Prometheus/Grafana |

**不预装**：Spring Cloud、Nacos、Seata、Sentinel、Redisson、Elasticsearch、ShardingSphere、RabbitMQ、Canal、Kubernetes、Quartz。出现明确需求后需有简要技术决策记录（ADR）和用户确认。

**版本核验门禁**：初建工程先验证 Java21+Boot4.1.1+MyBatisPlus3.5.17 对 MySQL9.7、Redis8.10 的真实连接，再验证 Flyway；在相关模块开始前验证 Kafka4.3 客户端连接。版本不兼容只能形成 issue/ADR，不能自行强制覆盖 BOM 依赖。

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

- 每个用户每场秒杀活动**最多成功获得一次抢购资格**；已抢/取消后默认不可重抢，直到需求改变。
- 商品套餐与活动是不同对象：活动可指向一个套餐，活动价格和库存是活动快照，不随着套餐变更回溯改变历史订单。
- 金额全部用整数最小货币单位（示例：分），Token 额度全部用整数单位，禁止 double/float 表达余额和价格。
- 一次下单对应一个活动和一个套餐；不支持购物车、组合优惠。
- 订单超时由服务端时间决定；一开始使用明确约定的单时区与 UTC 存储规范，日期序列化为 ISO8601。
- V1 模拟支付仅供本地演示，不允许宣传为生产级支付平台。
- 抢购成功和支付成功是两个不同结果，文案与 API 必须区分。

## 5. 状态与业务不变量

### 5.1 订单状态

`UNPAID` → `PAID`；`UNPAID` → `CLOSED`。其他状态转移一律拒绝。V1 不做复杂退款。支付与超时关单都必须有 `WHERE status='UNPAID'` 条件更新并检查影响行数。

### 5.2 库存语义

活动库存分为 `available_stock`（可再预占）与 `reserved_stock`（待支付订单占用），必要时保留 `sold_stock`（已支付）。

不变量示例：`available_stock >= 0`，`reserved_stock >= 0`，`sold_stock >= 0`，`available + reserved + sold = total_stock`。库存状态更新和订单变动必须在同一数据库事务中完成，且利用条件 UPDATE。

### 5.3 钱包语义

- 每个用户一个账户，余额不能为负。
- 每笔入账对应唯一业务标识 `source_type + source_id + action`。
- 更新余额和写入不可变流水必须在一个 DB 事务里。
- 同一订单重复支付回调、重复业务调用不允许导致多次入账。

## 6. 预期数据库模型（按阶段创建，不要一次全建）

| 表（建议名） | 最重要字段 / 唯一性 | 创建阶段 |
|---|---|---|
| `users` | `id`, `username` UNIQUE, `password_hash`, `role`, `status` | 用户与登录 |
| `token_product` | `id`, `name`, `token_amount`, `list_price_cent`, `status` | 套餐管理 |
| `seckill_activity` | `id`, `product_id`, `start_at`, `end_at`, `sale_price_cent`, `status`, `version` | 活动管理 |
| `activity_inventory` | `activity_id` PK, `total_stock`, `available_stock`, `reserved_stock`, `sold_stock` | 同步秒杀 |
| `orders` | `id`, `order_no` UNIQUE, `activity_id`, `user_id`, `status`, `amount_cent`, `token_amount`, `expire_at`, `request_id` UNIQUE | 同步秒杀 |
| `purchase_qualification` | `(activity_id,user_id)` UNIQUE, `order_id`, `status` | 同步秒杀（若订单 UNIQUE 已足够，可简化而不重复建表） |
| `payment_record` | `id`, `order_id`, `payment_ref` UNIQUE, `state`, `paid_at` | 模拟支付 |
| `token_account` | `user_id` PK, `available_balance`, `updated_at` | 钱包 |
| `token_ledger` | `id`, `user_id`, `biz_type`, `biz_id`, `action`, `amount`; UNIQUE `(biz_type,biz_id,action)` | 钱包 |
| `outbox_event` | `id`, `aggregate_id`, `event_type`, `payload`, `publish_status`, `created_at` | V1.5 |
| `processed_message` | PK `(consumer_name,event_id)` | V1.5 |

只有确实有不同职责时才拆 `purchase_qualification`；不要同时制造三个表示相同限购事实的表。SQL 类型、索引在具体 Feature 开发时根据查询/更新链路确定；所有 schema 改动必须有迁移版本与测试。

## 7. REST API 初稿（不要一次实现全部）

前缀：`/api/v1`；返回稳定 JSON 格式，错误包含 `code`、`message`、`requestId`。字段以代码中的 OpenAPI 契约为准。

| HTTP | URI | 用途 | 阶段 |
|---|---|---|---|
| POST | `/auth/register` | 注册 | F1 |
| POST | `/auth/login` | 登录 | F1 |
| GET | `/users/me` | 当前用户 | F1 |
| GET | `/products` | 套餐分页列表 | F2 |
| GET | `/products/{id}` | 套餐详情 | F2 |
| POST | `/admin/products` | 新增套餐（管理员） | F2 |
| POST | `/admin/activities` | 创建秒杀活动 | F3 |
| GET | `/activities/{id}` | 活动详情、状态和库存展示 | F3 |
| POST | `/activities/{id}/orders` | 下单；Header `Idempotency-Key` | F4 |
| GET | `/orders/{id}` | 查询自己的订单 | F4 |
| POST | `/orders/{id}/mock-pay` | 模拟支付 | F5 |
| GET | `/wallet` | 查询余额 | F5 |
| GET | `/wallet/ledger` | 流水分页 | F5 |

认证：`Authorization: Bearer <JWT>`；用户身份从认证上下文取，不允许从请求体传 `userId` 来决定交易归属。活动时间、库存、价格等由服务端决定。

### 状态码约定

- 200：读或幂等重试返回已有结果。
- 201：同步下单并创建订单成功；当 V1.5 真正异步受理时可改用 202 + 查询凭证（**需进行接口版本/契约评审**）。
- 400：非法参数；401：未登录；403：无权限；404：资源不存在；409：重复资格/状态冲突；429：限流；500：预期外故障。
- 同一 `Idempotency-Key` 携带不同参数必须拒绝，不允许静默复用。

## 8. 运行时拓扑（V1）

```text
React (Vite dev server :5173)
    └── HTTP /api → Spring Boot (:8080)
                          ├── MySQL (:3306) 交易真相源
                          └── Redis (:6379) V1 只在必要时使用
Kafka (:9092) 本地已安装，但在 V1.5 前不参与核心链路
```

开发环境约束：本机 MySQL root 有密码（业务应用应使用本地专用账号）；Redis 当前无密码，只能监听回环地址；Kafka 开发默认本机连接。密码只来自用户本地环境变量或未提交的 `application-local.yml`，绝不放到 Git。

## 9. 运行时拓扑（V1.5 备选设计，非立即实现）

```text
Client → API → Redis Lua 资格/库存准入
                   ↓
       MySQL 持久化购买资格 + Outbox（同一事务）
                   ↓
             Kafka 异步落单
                   ↓
             订单状态机 / 钱包
```

技术说明：如果采用“Redis 预扣 + DB 写入”，跨 Redis/DB 的原子性问题必须明确；不能用 Kafka Exactly Once 冒充数据库恰好一次。真正改造前要求先有同步基线的正确性和压测数据，再提交 ADR 比较同步、Outbox、Redis Stream 等方案。**任何一条链路都不能仅凭 Redis 返回扣减成功就宣告订单创建成功。**

## 10. 基础非功能要求

- 正确性：并发库存不为负、同一用户不重复获得资格、模拟支付不重复入账、关单不与支付同时成功。
- 安全性：权限检查、密码哈希、JWT 验证、校验入参、脱敏日志、不信任客户端价格与 userId。
- 可维护性：业务包结构稳定、DTO 和实体隔离、唯一异常映射、数据库约束与代码一致。
- 测试：业务规则单测、MySQL 真实集成测试、接口切片测试、最小前端流程测试；压测仅在 V1 功能闭环后做。
- 可观测性：接口 requestId、错误日志、基础健康检查；不在初期引入完整链路追踪堆栈。
- 兼容性：Mac 开发、JDK21、Spring Boot4；Node26 + pnpm12；禁用 `latest` 漂移版本。

## 11. 非目标与避免过度设计

- 不需要 CQRS、Event Sourcing、DDD 全家桶。可借用聚合边界思想，但不要引入多层无意义模型映射。
- 不强制所有 Service 定义一个 Interface；无第二实现且不需要隔离边界时可直接使用具体类。
- 不因为“需要 Kafka”就要求全部方法异步化。
- 不为 1 个活动提前做库存分片、热点 Key 分片、分库分表。
- 不要做完整购物车、优惠叠加、积分商城、第三方 OAuth、后台图表大盘以阻挡交易闭环。
- 设计模式以实际变化点为由；详见 `ENGINEERING_STANDARDS.md`。

## 12. 项目完成定义（V1）

本地可以从零初始化环境，完成：注册登录 → 创建套餐 → 创建活动 → 秒杀下单 → 订单查询 → 模拟支付 → 钱包入账和流水 → 重复支付/超时关闭/超卖测试。启动与测试步骤可复现。此后才能进入性能优化阶段。
