# TokenX 高并发架构基线

> **状态**：已决设计基线；按 `docs/DEVELOPMENT_PLAN.md` 分阶段实现，不能把设计目标写成已实测成绩。
> **范围**：限时秒杀、普通购买、订单、模拟支付与钱包入账。MySQL 始终是交易事实源；Redis 和 Kafka 分别承担准入保护与异步削峰。
> **唯一异步主链路**：V1.5 采用 **Redis Lua + Kafka + MySQL Worker**。不再采用 Redis Stream 作为订单队列，避免两套消息链路并存。

## 1. 目标与非目标

高并发目标不是让所有请求都落成订单，而是在突发流量下同时做到：

1. **正确**：不超卖、每活动每用户最多一单、订单只入账一次、支付与关单只有一个赢家。
2. **快**：无效请求尽量在 Redis 或应用入口拒绝，不把瞬时洪峰直接传给 MySQL。
3. **稳**：线程、数据库连接、Kafka Producer 缓冲区和消费者积压全部有界；过载时明确返回 `429` 或 `503`，不无限排队。
4. **可恢复**：允许消息重复，不允许重复业务效果；Redis、Kafka、Worker 任一故障都有明确降级或恢复步骤。
5. **可证明**：入口 QPS、受理 QPS、订单 TPS、支付 TPS 与端到端延迟分别测量，压测后运行数据库对账。

本项目不以微服务、分库分表、库存分桶或更多中间件来“证明高并发”。它们只有在单体横向扩容、Redis 准入、Kafka 削峰和数据库索引优化均有实测瓶颈后才进入独立 ADR。

## 2. 性能口径与目标

### 2.1 四个不能混用的指标

| 指标 | 定义 | 主要瓶颈 |
|---|---|---|
| 入口 QPS | API 每秒收到的请求数，包含重复、售罄和被限流请求 | HTTP、鉴权、限流、Redis |
| 准入 QPS | Redis Lua 每秒原子判定并成功写入准入状态的请求数 | Redis 单线程与脚本耗时 |
| 订单 TPS | MySQL 每秒成功提交的订单数 | 库存热点行、索引、事务提交 |
| 交易 TPS | 每秒完成支付、库存结转、余额和流水提交的订单数 | MySQL 多表事务与账户行锁 |

异步方案还必须报告两个延迟：HTTP 返回 `QUEUED` 的受理延迟，以及从受理到订单进入终态的端到端延迟。任何报告都不得用入口 QPS 代替订单 TPS。

### 2.2 设计目标（待 F10 实测）

以下是验收靶点，不是当前成绩；若固定参考机达不到，必须保留原始报告并据瓶颈优化，不能改口径：

| 场景 | 目标 |
|---|---|
| 20,000 用户突发争抢 1,000 库存 | 0 超卖、0 重复资格、0 重复入账 |
| V1 MySQL 同步基线 | 记录真实订单 TPS 与 P95/P99，不预设虚假结论 |
| V1.5 Redis + Kafka | 固定硬件和相同请求分布下，入口 QPS 至少达到同步基线 5 倍；初始绝对靶点为持续 5,000 QPS |
| 异步受理 | 非业务拒绝错误率 < 0.1%，受理 P99 ≤ 100 ms |
| 异步成单 | 无故障且无持续超载时，排队到订单确认 P99 ≤ 3 s；突发结束后 60 s 内清空积压 |
| 支付/关单竞争 | 最终只有一个合法状态，失败可重试但不得产生第二个业务效果 |

目标必须同时记录机器规格、JVM 参数、实例数、连接池、Kafka 分区、Redis 部署、库存、并发用户、到达率、持续时间、预热区间和正确性 SQL 结果。

## 3. 分阶段架构

### 3.1 阶段 A：MySQL 同步强一致基线（V1）

```text
Client → Spring MVC → MySQL transaction
                       ├─ validate activity
                       ├─ conditional inventory reservation
                       └─ insert purchase_order
```

- `activity_inventory.available_stock > 0` 的条件更新防超卖。
- `UNIQUE(activity_id, user_id)` 防一人多单。
- `UNIQUE(user_id, request_id)` 防同一用户同一请求重复建单。
- HTTP 仅在事务提交后返回 `201`；MySQL 是唯一成功判据。
- 这是后续所有优化的正确性与性能对照组，不能跳过。

### 3.2 阶段 B：Redis 读缓存与准入保护（V1.5）

```text
Client → bounded ingress → Redis Lua gate → Stage A MySQL transaction
                              └─ reject sold-out/duplicate/over-limit early
```

Redis Lua 以活动为维度原子完成：检查活动准入状态、检查用户是否已准入、扣减“准入额度”、记录 `requestId`。这份额度只是 **gate quota**，不是数据库库存事实。

- Redis 放行后，MySQL 仍执行完整条件更新与唯一约束。
- MySQL 拒绝或事务回滚时，使用带 `requestId` 比对的补偿 Lua 归还 gate quota；补偿可重复执行。
- 应用在 Redis 故障时不得把全部流量直接灌入 MySQL：阶段 B 只允许受连接池/并发闸门约束的小流量回退到同步链路，其余返回 `503`；阶段 C 的异步接口不静默改变契约，默认直接 `503`。售罄洪峰下优先保护数据库。
- Redis 重启或活动 key 丢失时，该活动先进入 `REBUILDING` 并暂停新准入；用 MySQL 库存、已有订单和未完成请求重建后再开放，不能在未知状态下直接初始化满库存。
- 活动发布后价格、额度与总库存不可变；发布/下架时主动刷新或删除缓存，TTL 只做兜底并带随机抖动。

阶段 B 提升入口承载并减少无效 DB 往返，但成功订单仍同步落库，因此不会突破单活动 MySQL 库存行的成单 TPS 上限。

### 3.3 阶段 C：Redis Lua + Kafka 异步削峰（V1.5 最终形态）

```text
Client
  → Redis Lua admission (RESERVED)
  → Kafka produce, wait for broker ack
  → Redis mark QUEUED
  → 202 {requestId,status:"QUEUED"}

Kafka topic (key = activityId)
  → consumer group
  → MySQL transaction: conditional inventory update + order insert + request result
  → commit DB
  → commit Kafka offset
```

#### API 与消息契约

- `POST /api/v1/activities/{activityId}/order-requests` 接收规范 UUID 格式的 `Idempotency-Key`。
- 部署配置 `tokenx.seckill.mode=SYNC|ASYNC` 决定唯一公开写入口：`SYNC` 只开放 V1 的 `/activities/{id}/orders`，`ASYNC` 只开放上述受理接口。禁止同一环境同时开放两条秒杀写入口绕过 Redis gate；切换模式是显式运维动作，不在依赖故障时自动切换。
- 只有 Kafka Broker 确认写入后才返回 `202`；Redis 准入成功但 Kafka 发送失败时执行补偿并返回 `503`。
- `GET /api/v1/order-requests/{requestId}` 只能查询当前用户自己的 `QUEUED/PROCESSING/ORDERED/REJECTED/FAILED`，`ORDERED` 返回 `orderNo`。
- Kafka Topic：`tokenx.seckill-order-commands.v1`；消息 key 为 `activityId`，保证同一活动进入同一分区、不同活动可并行。
- 消息最小字段：`schemaVersion`、`requestId`、`activityId`、`userId`、`admittedAt`。价格、额度、权限等最终值仍从服务端事实源取得，不信任消息中的客户端业务值。

#### Kafka 可靠性边界

- Producer 显式启用 `acks=all`、`enable.idempotence=true`，并等待发送结果；生产环境至少 3 Broker、复制因子 3、`min.insync.replicas=2`。本机单 Broker 只验证功能，不能宣称高可用。
- Consumer 关闭自动提交，在 MySQL 事务成功后再提交 offset，形成 **at-least-once** 消费；崩溃重放由数据库唯一键和条件状态迁移吸收。
- Producer 幂等只处理 Kafka 内的重试重复，不等于 Redis、Kafka、MySQL 跨系统恰好一次；本项目不宣称分布式 Exactly Once。
- 单活动按 key 有序且由一个分区内的一个消费者处理，避免多个 Worker 对同一库存行制造无收益的锁竞争；消费者并发用于不同活动。
- 非瞬态坏消息有限重试后进入 `tokenx.seckill-order-commands.dlt.v1`，记录告警和 `FAILED` 状态；不得无限阻塞分区。

#### `seckill_request` 的职责

F9 必须新增 `seckill_request`，保存最终受理结果而不是替代订单：

- `(user_id, request_id)` 唯一；`(activity_id, user_id, status)` 只建普通查询索引，最终一人一单仍由 `purchase_order` 的唯一键兜底；
- `ORDERED` 关联内部 `order_id`；`REJECTED/FAILED` 保存稳定错误码；
- API 到 Kafka 期间的 `RESERVED/QUEUED` 快状态在 Redis，Consumer 处理后把终态落 MySQL并回写 Redis；
- Redis 丢失时不返回伪造的“失败”。查询端在终态尚未落库时返回 `PROCESSING`，运维先暂停该活动准入并恢复状态。
- Consumer 先按 `(user_id,request_id)` 识别重复，再在同一 MySQL 事务写库存、订单与请求终态；重复投递返回既有终态，不再次扣库存。业务拒绝时按拒绝原因幂等修正 Redis 用户标记和 gate quota。

### 3.4 阶段 D：事务后事件（Kafka Outbox）

支付完成后的通知、统计、对账等事件走另一类 Topic，例如 `tokenx.order-events.v1`。业务事务同时写 `outbox_event`，Publisher 再投递 Kafka，消费者用 `processed_message` 幂等。

这是“数据库事实向下游发布”的事件链路；阶段 C 是“准入命令进入订单 Worker”的命令链路。两者职责不同，不构成两个互相竞争的订单队列。订单创建主链路始终只用 Kafka，不用 Redis Stream。

## 4. 并发控制与背压

### 4.1 应用层

- Spring MVC 实例保持无状态，可横向启动多个相同实例；这不是拆微服务。
- JWT 在本地验证，热路径不按请求查询用户表；禁用用户对已签发 Token 的影响以 Token 过期时间为上界。
- 所有池和队列必须有界：HTTP 工作线程、数据库连接池、Kafka Producer 缓冲区、Consumer 并发数。
- 数据库连接池大小通过压测调整，不能用“连接越多越快”代替测量；获取连接、SQL、Kafka 发送都设置超时。
- 过载时先拒绝，不在应用内建立无限等待队列。业务售罄/重复返回稳定业务错误，容量过载返回 `429`，依赖不可用且无法安全回退返回 `503`。

### 4.2 Redis Key 设计

同一活动涉及的 Lua key 必须使用同一 hash tag，以便未来 Redis Cluster 中仍位于同一槽：

```text
tokenx:seckill:{activityId}:gate
tokenx:seckill:{activityId}:users
tokenx:seckill:{activityId}:requests
```

脚本必须 O(1)，禁止在热路径使用 `KEYS`、大集合全量遍历或长循环。参与记录的 TTL 至少覆盖活动结束、最大支付期和补偿窗口；活动结束后异步清理，不能在请求线程删除大 key。

### 4.3 MySQL 热路径

- 库存按 `activity_id` 主键点更新；订单使用顺序自增内部主键，降低聚簇页分裂。
- 下单事务在获得库存锁后只做库存更新与订单插入，禁止远程调用和日志大对象序列化。
- 新建秒杀订单的锁序是“活动配置共享锁 → 库存行 → 插入新订单”；支付与关单处理既有订单时统一“订单行 → 库存行 → 账户行 → 流水插入”。
- 超时关闭使用 `(status, expires_at)` 索引和 `FOR UPDATE SKIP LOCKED`，每个短事务只关闭一笔订单；多个 Worker 可并行且不会重复释放库存。
- 死锁可对整个短事务做少量带抖动重试，但必须记录指标；不能只重放事务中间一条 SQL。

## 5. 故障矩阵

| 故障点 | 对外结果 | 恢复与正确性 |
|---|---|---|
| Redis 在准入前不可用 | 阶段 B 可限量回退；阶段 C 异步接口返回 `503` | MySQL 仍正确；不静默改变 API 契约，并发闸门保护 DB |
| Lua 已预留，Kafka 明确发送失败 | `503` | 比对 `requestId` 后补偿 Redis；补偿可重试 |
| Lua 已预留，Kafka 结果不确定或进程崩溃 | 客户端未获得成功承诺 | 标记 `UNKNOWN`，由租约与 Kafka/MySQL 终态对账后再补偿；不能在结果不确定时立即释放额度 |
| Kafka 已 ACK，HTTP 响应丢失 | 客户端重试得到同一请求状态 | 同一用户同一 `requestId` 幂等；可能重复消息但不重复业务效果 |
| Consumer 在 DB 提交前崩溃 | 仍为 `QUEUED` | 消息重投，事务重新执行 |
| Consumer 在 DB 提交后、offset 提交前崩溃 | 仍可查到终态 | 消息重投，唯一键/状态条件返回既有结果后提交 offset |
| Redis 全量丢失 | 暂停活动新准入 | MySQL/Kafka 事实不丢；重建 gate 后恢复，不直接以总库存初始化 |
| Kafka 不可用 | 不返回 `202` | Redis 预留被补偿；同步链路是否开放由受控降级开关决定，默认保护 DB |
| MySQL 不可用 | 已 ACK 请求继续积压 | Consumer 不提交 offset；恢复后重放，积压超过阈值时入口停止准入 |

## 6. 可观测性与容量门禁

必须至少暴露以下指标并按 `activityId` 控制标签基数：

- API：请求数、业务拒绝、`429/503`、P50/P95/P99；
- Redis：Lua 结果码、耗时、补偿数、gate 剩余量、重建状态；
- Kafka：发送失败、topic/partition lag、最老消息年龄、重试与 DLT 数；
- MySQL：订单提交 TPS、事务耗时、连接池等待、行锁等待、死锁、慢 SQL；
- 业务：准入数、最终成单数、支付数、关闭数、库存与账本对账差异。

自动保护阈值在 F9/F10 用实测确定并配置化。至少包括：连接池占用持续过高、Kafka 最老消息年龄超过 SLO、Redis Lua P99 超标、MySQL 锁等待超标。触发时暂停或限速新准入，不能继续扩大积压。

## 7. 压测与故障注入顺序

1. 阶段 A：低库存争抢、高有效写入、多活动并行、支付/关单竞争。
2. 阶段 B：相同数据集复测，比较 DB 请求下降和入口 QPS；注入 Redis 超时与 key 丢失。
3. 阶段 C：分别测受理和成单；停止/重启 Consumer、制造重复消息、断开 Kafka/MySQL、恢复积压。
4. 每轮结束运行 `docs/DATABASE_DESIGN.md` §6 的对账 SQL；有一条差异即本轮失败。
5. 只有瓶颈证据指向单库存行锁且 Redis/Kafka 已正确工作，才评审库存分桶；只有单机资源确实耗尽才评审分库分表。

## 8. 设计依据

- Redis Lua/Functions 在服务端原子执行，但脚本执行期间会阻塞其他活动，因此脚本必须短小、O(1)：<https://redis.io/docs/latest/develop/programmability/eval-intro/>
- Kafka 4.3 Producer 的 `acks=all`、幂等与 in-flight 约束：<https://kafka.apache.org/43/configuration/producer-configs/>
- Kafka 对外部数据库默认采用至少一次处理，DB 业务效果仍需幂等：<https://kafka.apache.org/43/design/design/#message-delivery-semantics>
- InnoDB 对唯一索引点更新只锁目标记录；范围扫描与缺索引会扩大锁范围：<https://dev.mysql.com/doc/refman/9.7/en/innodb-locks-set.html>
