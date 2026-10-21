# TokenX

TokenX 是一个 **AI Token 套餐限时秒杀交易平台**的 Java 重构工程：管理员配置套餐与限时活动，用户在活动时间内抢购，订单支持模拟支付，支付成功后 Token 额度进入钱包并生成不可变流水。

> **当前状态**：`F0-1`～`F0-3`、`F1`、`F2`、`F3`、`F4`、`F4.5`、`F5`、`F6` 完成——最小可运行骨架、MySQL 迁移链路、
> Redis 连通性、数据库/高并发设计基线，注册、登录、JWT 鉴权、`/users/me`，Token 套餐目录
> （管理员创建/上下架、游客游标分页列表与详情、公开列表页），秒杀活动配置与库存初始化
> （管理员创建/发布/下架、游客活动详情页、服务端时间驱动的运行状态），**同步秒杀下单**
> （MySQL 条件扣库存 + 唯一键一人一单 + 幂等键重放、订单详情页），**普通购买下单**
> （按套餐标价直接下单，不碰活动库存、无一人一单限制，与秒杀共用订单表、查询与后续状态机），
> **模拟支付与钱包**（同一事务内订单转 `PAID` + 秒杀预占结转 + 余额入账 + 不可变流水，
> 重复/并发支付只入账一次，余额与流水分页查询，订单页支付按钮与钱包页），以及**订单超时关闭**
> （`FOR UPDATE SKIP LOCKED` 有界领取、每单一个短事务、秒杀预占归还可用库存、支付与关单只能有一个赢家）。
> Redis/Kafka 优化在 F7 及以后。
> 交易过程中的“支付”是**本地模拟**，不接入任何真实资金渠道，不可用于生产支付场景。

## 技术栈

| 层 | 选型 |
|---|---|
| JDK | Temurin 21 (LTS) |
| 后端 | Spring Boot 4.1.1 / Spring Framework 7，Spring MVC |
| 构建 | Maven 3.9.x + Maven Wrapper（仓库内置 `mvnw`） |
| 持久化 | MyBatis-Plus 3.5.17（boot4 starter）+ MySQL 9.7 + Flyway 12.4 |
| 认证 | Spring Security 7 + Nimbus JWT（HS256，本地验签） |
| 缓存 / 消息 | Redis 8.10.x / Kafka 4.3.1（V1 关键链路不依赖，V1.5 才接入） |
| 前端 | React 19 + TypeScript 5.x + Vite + pnpm（`frontend/`） |

各依赖的实际解析版本见 [docs/VERSION_LOCK.md](docs/VERSION_LOCK.md)。

## 环境要求

- JDK 21（`java -version` 必须为 21）
- MySQL 9.7.x、Redis 8.10.x 本机运行
- 无需预装 Maven，仓库自带 Wrapper
- 前端：Node.js 26.9.x + pnpm 12.x（仅 `frontend/` 需要）

## 本地数据库准备（一次性）

应用使用**专用账号**连接，不用 root。先用 root 建库建账号（密码自己设定，不要用示例值）：

```sql
CREATE DATABASE IF NOT EXISTS tokenx      DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE DATABASE IF NOT EXISTS tokenx_test DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS 'tokenx'@'localhost' IDENTIFIED BY '<your-local-password>';
CREATE USER IF NOT EXISTS 'tokenx'@'127.0.0.1' IDENTIFIED BY '<your-local-password>';
GRANT ALL PRIVILEGES ON tokenx.*      TO 'tokenx'@'localhost';
GRANT ALL PRIVILEGES ON tokenx_test.* TO 'tokenx'@'localhost';
GRANT ALL PRIVILEGES ON tokenx.*      TO 'tokenx'@'127.0.0.1';
GRANT ALL PRIVILEGES ON tokenx_test.* TO 'tokenx'@'127.0.0.1';
```

再把密码写入本地覆盖配置（**该文件已被 `.gitignore` 忽略**）：

```bash
cp src/main/resources/application-local.yml.example src/main/resources/application-local.yml
# 编辑 application-local.yml，只填 spring.datasource.password
```

`application-local.yml` 只允许放密码：URL 与用户名分别在 `application.yml`（开发库 `tokenx`）和
`application-test.yml`（测试库 `tokenx_test`）中声明，避免测试误连开发库。

## 运行

```bash
# 编译 + 全部测试（需要本机 MySQL 与 Redis 已启动）
./mvnw clean verify

# 启动应用：JWT 签名密钥必须提供，否则应用拒绝启动（fail fast）
export TOKENX_JWT_SECRET="$(openssl rand -base64 48)"
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

健康检查（公开端点）：

```bash
curl -s http://localhost:8080/api/v1/health
# {"status":"UP","service":"tokenx","time":"2026-10-20T11:55:12.345Z"}
```

Actuator 健康端点（运维用）：`http://localhost:8080/actuator/health`

> 说明：当前所有 Spring 测试都会加载真实数据源，因此 **不启动 MySQL 时 `./mvnw verify` 会失败**。
> 这是 F0-2 的既定要求（真实组件集成验证），不是 Mock。测试只操作 `tokenx_test`，不会写开发库。

## 认证接口（F1）

前缀 `/api/v1`。匿名可访问：健康检查、`/auth/**`、以及 `GET /products/**`（见下一节）；其余接口都需要
`Authorization: Bearer <JWT>`。

| 方法 | 路径 | 说明 | 成功 | 主要失败 |
|---|---|---|---|---|
| POST | `/auth/register` | 注册并创建零余额账户 | `201` `{id, username, role}` | `400 VALIDATION_FAILED`、`409 USERNAME_ALREADY_EXISTS` |
| POST | `/auth/login` | 登录 | `200` `{accessToken, tokenType, expiresInSeconds}` | `401 INVALID_CREDENTIALS`、`403 ACCOUNT_DISABLED` |
| GET | `/users/me` | 当前用户（取自 JWT claims） | `200` `{id, username, role}` | `401 UNAUTHORIZED` |

```bash
curl -s -X POST http://localhost:8080/api/v1/auth/register \
  -H 'Content-Type: application/json' -d '{"username":"alice_01","password":"Passw0rd!23"}'

TOKEN=$(curl -s -X POST http://localhost:8080/api/v1/auth/login \
  -H 'Content-Type: application/json' -d '{"username":"alice_01","password":"Passw0rd!23"}' \
  | sed -E 's/.*"accessToken":"([^"]+)".*/\1/')

curl -s http://localhost:8080/api/v1/users/me -H "Authorization: Bearer $TOKEN"
```

要点：

- 用户名 3–64 位字母/数字/下划线，`utf8mb4_0900_ai_ci` 唯一索引保证 `Alice` 与 `alice` 是同一账号。
- 密码至少 8 个字符，且**UTF-8 编码后不超过 72 字节**（BCrypt 的上限是字节数，不是字符数；25 个汉字即 75 字节，
  会被校验直接拒绝并返回 `400 VALIDATION_FAILED`，而不是让编码器抛出 500）。注册与登录使用同一规则。
- 密码 BCrypt 哈希入库，接口与日志都不出现明文；注册**不接受**客户端指定 `role`/`status`。
- 注册在同一个事务里写 `app_user` + `token_account`（余额 0），任一步失败整体回滚。
- JWT 在本地验签，**每个已认证请求都不查 `app_user`**；因此禁用用户的存量 Token 最迟在过期时失效。
- 所有错误统一为 `{code, message, requestId}`，响应头带 `X-Request-Id`。

## 套餐接口（F2）

公开读取允许游客访问，写入只允许管理员。列表用**游标分页**（`id < cursor`，`id DESC`），不用 `OFFSET`：
翻到深处不会越来越慢，也避免了“头部新增数据把整页往后推”造成的重复读取。代价是它**不提供跨页快照一致性**：
翻页期间新上架、且 id 大于当前 cursor 的套餐不会出现在本次遍历的后续页面，刷新首屏即可看到。这是公开目录
已接受的弱一致语义，不是“绝不漏项”。

| 方法 | 路径 | 说明 | 成功 | 主要失败 |
|---|---|---|---|---|
| GET | `/products?cursor=&limit=` | 在售套餐列表，`limit` 默认 20、上限 100 | `200` `{items, nextCursor, hasMore}` | `400 VALIDATION_FAILED` |
| GET | `/products/{id}` | 在售套餐详情 | `200` `{id, name, creditAmount, listPriceCent, status}` | `404 PRODUCT_NOT_FOUND`（不存在**或**已下架，响应完全一致） |
| POST | `/admin/products` | 创建套餐（管理员），请求体只接受 `name`/`creditAmount`/`listPriceCent` | `201`，状态固定为 `OFF_SHELF` | `400`、`401`、`403` |
| PATCH | `/admin/products/{id}/status` | 上架/下架（管理员），`{"status":"ON_SALE"\|"OFF_SHELF"}` | `200`，重复同一状态也返回 `200` | `400`、`401`、`403`、`404 PRODUCT_NOT_FOUND` |

```bash
# 游客：列表与详情（无需 Token）
curl -s 'http://localhost:8080/api/v1/products?limit=20'
curl -s 'http://localhost:8080/api/v1/products?limit=20&cursor=42'
curl -s http://localhost:8080/api/v1/products/1

# 管理员：创建 → 上架 → 下架
curl -s -X POST http://localhost:8080/api/v1/admin/products \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"name":"入门包","creditAmount":1000,"listPriceCent":990}'

curl -s -X PATCH http://localhost:8080/api/v1/admin/products/1/status \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"status":"ON_SALE"}'
```

要点：

- 新建套餐默认 **下架**；`status` 由服务端决定，客户端传 `status` 会被忽略。上架是独立的一步。
- 下架只改状态、**不删除记录**（后续活动与历史订单仍指向它），并且立即从公共列表与详情中消失。
- 名称去首尾空格后必须非空且不超过 96 个字符；`creditAmount` 与 `listPriceCent` 必须是正整数，
  金额只用整数“分”，数据库另有 `CHECK` 兜底（`> 0`、`status IN (0,1)`）。
- 数值上限：`creditAmount ≤ 1_000_000_000`、`listPriceCent ≤ 100_000_000_000`（= 人民币 10 亿元）。
  上限集中在 `PackageLimits`，DTO 校验与数据库 `CHECK` 共用同一组数字，超出即 `400 VALIDATION_FAILED`。
- **整数精度契约**：`id`、`nextCursor`、`creditAmount`、`listPriceCent` 都以 JSON number 返回，浏览器按
  IEEE-754 双精度解析。因此套餐自增 ID 必须保持在 `2^53-1`（`Number.MAX_SAFE_INTEGER`）以内；未来若引入
  外部 ID（如订单号、第三方单号），必须用字符串传输，**不得**塞进 JSON number。
- 套餐名**不唯一**：同名展示套餐是合法的，不构成数据冲突。
- `GET /products/**` 匿名可读；`/admin/**` 需要 `ROLE_ADMIN`。未登录 `401`，普通用户 `403`。
- 同一套餐的并发上架/下架由数据库单行锁（`SELECT … FOR UPDATE` 后更新）串行化，重复同一状态幂等返回当前状态。
- `nextCursor` 只在还有下一页时返回，客户端不需要（也不应该）自己推算游标。

### 本地管理员（仅本地演示，人工执行）

代码里**没有**默认管理员、默认密码或任何提权接口。需要管理员时，先用 `/auth/register` 注册一个
普通账号，然后在本机数据库里手工提升角色，再**重新登录**（角色在签发的 JWT 里，旧 Token 不会变）：

```sql
-- 只允许在本地开发库执行；生产环境禁止
UPDATE app_user SET role = 1 WHERE username = '<你的用户名>';
```

## 活动接口（F3）

活动把“某个套餐在某个时间窗、以某个价格、限量售卖”表达为独立对象，因此同一个套餐可以办多场活动。
本阶段只做**配置与展示**，不提供任何下单或扣库存接口。

| 方法 | 路径 | 说明 | 成功 | 主要失败 |
|---|---|---|---|---|
| POST | `/admin/activities` | 创建活动（管理员），请求体只接受 `packageId`/`activityPriceCent`/`totalStock`/`startAt`/`endAt` | `201`，配置态固定 `DRAFT`，同事务写入库存 `N/N/0` | `400 VALIDATION_FAILED`、`401`、`403`、`404 PRODUCT_NOT_FOUND`、`409 PACKAGE_NOT_AVAILABLE` |
| PATCH | `/admin/activities/{id}/status` | 发布/下架（管理员），`{"status":"PUBLISHED"\|"OFFLINE"}` | `200`，重复同一状态也返回 `200` | `400`、`401`、`403`、`404 ACTIVITY_NOT_FOUND`、`409 ACTIVITY_STATE_CONFLICT` |
| GET | `/activities/{id}` | 公开活动详情，**只返回 `PUBLISHED`** | `200` | `400 VALIDATION_FAILED`、`404 ACTIVITY_NOT_FOUND`（不存在/草稿/已下架完全一致） |

```bash
# 管理员：创建 → 发布（$ADMIN_TOKEN 见上一节的本地管理员说明）
curl -s -X POST http://localhost:8080/api/v1/admin/activities \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"packageId":1,"activityPriceCent":799,"totalStock":100,
       "startAt":"2026-10-22T02:00:00Z","endAt":"2026-10-22T03:00:00Z"}'

curl -s -X PATCH http://localhost:8080/api/v1/admin/activities/1/status \
  -H "Authorization: Bearer $ADMIN_TOKEN" -H 'Content-Type: application/json' \
  -d '{"status":"PUBLISHED"}'

# 游客：活动详情（无需 Token）
curl -s http://localhost:8080/api/v1/activities/1
```

要点：

- **两个状态必须分开理解**：`status` 是配置态（`DRAFT`/`PUBLISHED`/`OFFLINE`），
  `runtimeStatus` 是运行态（`UPCOMING`/`ACTIVE`/`ENDED`），后者由服务端 `Clock` 按 `[startAt, endAt)`
  实时计算，**不落库**；`soldOut` 另由 `availableStock == 0` 得出，不是时间状态。
- 状态机只有 `DRAFT→PUBLISHED`、`DRAFT→OFFLINE`、`PUBLISHED→OFFLINE` 三条边；`OFFLINE` 是终态，
  不能重新发布，也不能回到 `DRAFT`。重复请求当前状态幂等 `200`，其余非法迁移 `409`。
- 创建时 `endAt` 必须在未来，`startAt` 允许在过去（立即开始型）；`startAt < endAt` 为硬约束。
- 到账额度由服务端从套餐读取并快照，客户端传 `creditAmountSnapshot`/`status`/`availableStock`/
  `reservedStock`/`purchaseLimit`/`userId` 一律忽略。发布时会再次读取套餐：套餐必须仍 `ON_SALE`，
  且用套餐**当前**额度刷新快照。
- 发布后活动价、额度快照、起止时间、总库存都没有修改入口；下架只改配置态，不删除活动、不删除库存、
  不重置库存。
- 同一活动的并发发布/下架由数据库单行锁（`SELECT … FOR UPDATE`）串行化，只锁该主键行。
- 金额与额度上限沿用 F2 的 JavaScript 安全整数契约，常量集中在
  `com.tokenx.infrastructure.money.AmountLimits`（`activityPriceCent ≤ 100_000_000_000` 分、
  `creditAmountSnapshot ≤ 1_000_000_000`），数据库 `CHECK` 兜底。
- 公开详情用一次主键 JOIN 读取活动与库存，且 `WHERE a.status = 1` 写在 SQL 里；`reservedStock`、
  数字状态码与 `createdAt`/`updatedAt` 都不出现在公开响应中。
- 每人每活动最多一单是项目固定规则（F4 用唯一键落实），响应里固定返回 `purchaseLimit = 1`，
  不为此增加一个只能取 1 的数据库列。

## 下单接口（F4 秒杀 / F4.5 普通购买）

**这一步是 MySQL 强一致基线**：判断谁抢到的只有数据库语句本身。正确性路径上**没有** Redis 预扣、
Kafka/消息队列、本地内存队列或分布式锁——下单结果与并发量无关，只与库存行和唯一键有关。

| 方法 | 路径 | 说明 | 成功 | 主要失败 |
|---|---|---|---|---|
| POST | `/activities/{id}/orders` | 秒杀下单（需登录）。**无请求体**；必须带 `Idempotency-Key`（规范 36 位 UUID） | `201`（本次创建）或 `200`（同一 key 重放，不扣库存） | `400 VALIDATION_FAILED`、`401 UNAUTHORIZED`、`404 ACTIVITY_NOT_FOUND`、`409 ACTIVITY_NOT_ACTIVE` / `ACTIVITY_SOLD_OUT` / `ALREADY_PURCHASED` / `IDEMPOTENCY_KEY_REUSED` |
| POST | `/orders` | 普通购买（需登录）。请求体只有 `{"packageId":123}`；必须带 `Idempotency-Key` | `201`（本次创建）或 `200`（同一 key 同套餐重放） | `400 VALIDATION_FAILED`、`401 UNAUTHORIZED`、`404 PRODUCT_NOT_FOUND`、`409 PACKAGE_NOT_AVAILABLE` / `IDEMPOTENCY_KEY_REUSED` |
| GET | `/orders/{orderNo}` | 查询**自己的**订单（需登录），两种渠道共用 | `200` 订单快照 | `400 VALIDATION_FAILED`、`401 UNAUTHORIZED`、`404 ORDER_NOT_FOUND`（不存在与他人订单完全一致） |

```bash
# 秒杀下单：单号由服务端生成，客户端只提供幂等键
curl -s -X POST "http://localhost:8080/api/v1/activities/1/orders" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $(uuidgen | tr 'A-Z' 'a-z')"
# 201 -> {"orderNo":"...","orderType":"SECKILL","status":"UNPAID","payAmountCent":799,...}

# 普通购买：只传套餐 id，价格/额度/名称/截止时间都由服务端写入快照
curl -s -X POST "http://localhost:8080/api/v1/orders" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $(uuidgen | tr 'A-Z' 'a-z')" \
  -H "Content-Type: application/json" \
  -d '{"packageId":1}'
# 201 -> {"orderNo":"...","orderType":"NORMAL","activityId":null,...}

# 查询自己的订单（两种渠道同一个接口）
curl -s http://localhost:8080/api/v1/orders/<orderNo> -H "Authorization: Bearer $TOKEN"
```

要点：

- **事务与加锁顺序固定**：① 按 `(user_id, request_id)` 查已有订单 → ② `seckill_activity` 主键
  `FOR SHARE` 读出活动快照 → ③ 校验 `PUBLISHED` 且 `now ∈ [start_at, end_at)` → ④ 在库存锁**之前**
  备好订单号、支付截止与全部快照 → ⑤ 条件 `UPDATE activity_inventory … WHERE available_stock > 0`
  → ⑥ `INSERT purchase_order` → ⑦ `COMMIT` → ⑧ 提交成功后才返回 `201`。
- **扣库存靠影响行数，不靠先查后改**：`available_stock > 0` 写在 `UPDATE` 条件里，影响 0 行即
  `409 ACTIVITY_SOLD_OUT`，此时不插订单、不开后续写；`UNSIGNED` 列与 `CHECK` 是最后防线，库存不会为负。
- **一人一活动一单由唯一键永久保证**：`uk_activity_user (activity_id, user_id)`。服务端不做
  `(activity_id, user_id)` 预查——它不可能权威，只会给热路径多加一次读，真正的判定仍然是同一个唯一键。
- **唯一键冲突必须整体回滚**：订单 `INSERT` 是事务最后一句，冲突意味着 ⑤ 的预占也必须消失。
  吞掉 `DuplicateKeyException` 继续提交会留下“库存被扣但没有订单”的脏数据。因此事务完整回滚，
  再在**事务边界之外**（独立的只读事务）判定原因：命中同一幂等键 → 同活动返回原订单 `200`、
  异活动 `409 IDEMPOTENCY_KEY_REUSED`；否则命中 `(activity_id, user_id)` → `409 ALREADY_PURCHASED`；
  两者都没有则只可能是订单号撞号，换号重试且有次数上限，超限如实报错。
- **`201` 与 `200` 语义不同**：`201` 表示本次请求真的创建了订单并预占了库存；`200` 表示同一幂等键
  早先已创建，本次只是重放，**没有再次扣库存**。因此“网络超时后重试”是安全的：复用同一个键即可。
- **已有订单优先于活动状态**：重放判定在活动校验之前，所以活动结束后、下架后，原先的订单仍然能按同一
  幂等键取回，不会因为活动不可售而丢掉一张已存在的单。
- **`order_no` 不可枚举**：应用侧生成 26 位 Crockford Base32（前 10 位是毫秒时间、后 16 位是 80 位
  密码学随机数），不带业务含义、相邻单号无法推算；**不使用**数据库序列表（那会变成新的全局热点）。
  所有 HTTP 路径只接受 `order_no`，自增 `id`、`user_id`、`request_id`、数字状态码都不出现在响应里。
- **金额与额度是快照**：`pay_amount_cent`、`credit_amount`、`package_name_snapshot` 全部由服务端在
  创建时写入，之后活动改价或套餐改价都不影响既有订单；请求体（即使客户端强行带上）不会被采纳。
- **支付截止时间**由 `tokenx.order.payment-timeout`（默认 `15m`）在创建时固化：
  `expires_at = created_at + 配置时长`，之后修改配置只影响新订单。全过程只用注入的服务端 `Clock`
  （截断到毫秒），客户端时间不参与任何判断。
- **已下架套餐不影响已发布活动**：下单只读套餐名做快照，不重新校验 `ON_SALE`——套餐下架是“不再新开活动”，
  不是“已发布的活动立刻失效”。
- 本阶段订单只有 `UNPAID`：没有支付、没有超时关闭、没有库存释放（F5 / F6）。
- 并发相关的集成测试（真实 MySQL、真实并发请求）验证的是**正确性**：成功数等于初始库存、
  `available_stock` 不为负、`reserved_stock` 等于成功数、失败只返回稳定业务码、不出现 500。
  这些数字**不是** QPS/TPS 压测结果，不得当作性能指标引用。

### 普通购买（F4.5）

普通购买与秒杀是**两个接口、两个 Service、同一张订单表和同一套查询/后续状态机**。分开的只有入口与
事务内容（是否预占库存），共享的是订单生命周期——支付、关单、查单将来都只写一份。

- **`NormalOrderService` 单独一个事务入口**，顺序是：① 按 `(user_id, request_id)` 查已有订单 →
  ② 一次 `Clock` 读取 → ③ `token_package` 主键 `FOR SHARE` 读出并校验 `ON_SALE`，同时取得名称、
  额度与标价快照 → ④ `INSERT purchase_order` → ⑤ `COMMIT`。**没有活动、没有库存行、没有任何扣减**，
  普通购买前后 `activity_inventory` 完全不变。
- **不碰 `activity_inventory`**，也不读它：普通购买属于另一个渠道，凭空检查库存只会让两个渠道互相牵连。
- **可重复购买**：同一用户用不同幂等键可以买同一套餐任意多次，每次都是独立订单。
  `uk_activity_user(activity_id,user_id)` 在 `activity_id=NULL` 时天然不限制普通订单（MySQL 唯一索引
  不比较 NULL），因此**没有**“一人一普通套餐一单”的约束，也不需要加。
- **幂等键比较包含订单类型与目标**：同键同套餐 → `200` 重放原订单；同键换套餐 → `409
  IDEMPOTENCY_KEY_REUSED`；把秒杀用过的键拿来普通购买（或反过来）也是 `409`，即使两者指向同一个套餐。
  重放判定在套餐校验之前，所以订单创建后套餐被下架，仍能用同一个键取回原订单。
- **套餐校验与管理员下架协调**：下单事务对套餐行持 `FOR SHARE`，管理员下架走同一主键行的
  `FOR UPDATE`，因此二者不可能交错读到过期状态——要么下单先提交（订单成立），要么下架先提交
  （下单拿到 `409 PACKAGE_NOT_AVAILABLE`）。这条竞争有真实 MySQL 并发测试覆盖。
- **没有一人一单、没有售罄**：普通购买的错误只有 `404 PRODUCT_NOT_FOUND`（套餐不存在）、
  `409 PACKAGE_NOT_AVAILABLE`（已下架）、`409 IDEMPOTENCY_KEY_REUSED`。
- **F4.5 不新增迁移**：`V8__create_purchase_order.sql` 里的 `order_type`、可空 `activity_id` 与
  `ck_order_activity` 已经为 `NORMAL` 准备好，Flyway 仍停在 V8。

## 模拟支付与钱包（F5）

**这是本地模拟支付**：没有第三方渠道、没有回调、不建 `payment_record`。一次支付就是一次 MySQL
本地事务，"成功"意味着这笔提交真的落库。

| 方法 | 路径 | 说明 | 成功 | 主要失败 |
|---|---|---|---|---|
| POST | `/orders/{orderNo}/mock-pay` | 模拟支付（需登录）。**无请求体**，身份只取自 JWT | `200` + 更新后的订单（`status=PAID`、`paidAt` 非空）；已支付重放同样 `200` | `400 VALIDATION_FAILED`、`401 UNAUTHORIZED`、`404 ORDER_NOT_FOUND`、`409 ORDER_STATE_CONFLICT` |
| GET | `/wallet` | 查询**自己的**余额（需登录） | `200` `{"availableBalance":1000,"updatedAt":"..."}` | `401 UNAUTHORIZED` |
| GET | `/wallet/ledger` | 查询**自己的**流水分页（需登录） | `200` `{"items":[...],"nextCursor":null,"hasMore":false}` | `400 VALIDATION_FAILED`、`401 UNAUTHORIZED` |

```bash
# 支付（无请求体：金额、额度、支付时间全部来自订单快照）
curl -s -X POST "http://localhost:8080/api/v1/orders/<orderNo>/mock-pay" \
  -H "Authorization: Bearer $TOKEN"
# 200 -> {"orderNo":"...","status":"PAID","paidAt":"2026-10-21T12:00:00Z",...}

# 钱包余额与流水（默认 20 条，翻页带 cursor=<上一页最后一条 entryId>）
curl -s http://localhost:8080/api/v1/wallet -H "Authorization: Bearer $TOKEN"
curl -s "http://localhost:8080/api/v1/wallet/ledger?limit=20" -H "Authorization: Bearer $TOKEN"
```

要点：

- **一次支付 = 一个事务，四步固定顺序**：① 条件 `UPDATE purchase_order … WHERE order_no=?
  AND user_id=? AND status=0 AND expires_at>?`（**状态闸门**，影响 1 行即赢得支付并持有订单行锁）
  → ② `SELECT … FOR SHARE` **当前读**取最新已提交状态与可信快照 → ③ 仅 `SECKILL` 执行
  `reserved_stock-1` → ④ `available_balance + credit_amount` → ⑤ 同事务读回余额 →
  ⑥ 写入一条 `CREDIT` 流水。任一步失败，订单状态、库存、余额、流水**全部回滚**。
- **为什么失败后要用当前读**：REPEATABLE READ 下普通 `SELECT` 读事务快照，可能早于刚提交的赢家，
  会把已支付订单判成"仍待支付"从而尝试第二次入账。`FOR SHARE` 是当前读，要么读到已提交的
  `PAID`（→ 幂等 `200`），要么在赢家的行锁上等待后再读到 `PAID`。
- **重复支付与并发支付只产生一次效果**：`status=0` 写在 `UPDATE` 条件里，任意多个并发者中只有
  一个影响 1 行；其余等待后按幂等 `200` 返回，**不出现 500**，也不会二次结转库存或二次加余额。
  `uk_ledger_biz(biz_type,biz_id,effect_type)` 是第二道防线，不替代状态闸门：若它真的命中，说明
  订单状态与流水已矛盾，此时整笔回滚并报错，绝不把冲突当作成功。
- **普通订单与秒杀订单的唯一差别是库存**：`SECKILL` 只把 `reserved_stock` 减 1，`available_stock`
  不动（已售量仍由 `total-available-reserved` 派生）；`NORMAL` 完全不读也不写 `activity_inventory`。
- **过期与关单**：`UNPAID` 且 `expires_at <= now` 返回 `409 ORDER_STATE_CONFLICT`，**不入账、不释放
  库存**；迁移为 `CLOSED` 并归还预占的是 F6 的超时扫描，与支付共用同一条订单行锁，因此两者只会有一个
  赢家（见下一节）。已 `CLOSED` 同样 `409` 且零副作用。
- **客户端说了不算**：`credit_amount`、`pay_amount_cent` 来自下单时的快照，`userId` 来自已验签 JWT；
  请求体即使带 `userId`/`creditAmount`/`status`/`paidAt` 也不会被采纳（该接口根本没有请求体参数）。
- **账户不懒创建**：注册时与用户同事务建账户（Q8）。账户缺失属于不变量破坏，整笔回滚并报错，
  绝不在支付路径上补建。
- **余额与流水可对账**：`token_account.available_balance` 必须等于 `SUM(token_ledger.delta)`；
  每张已支付订单恰好一条 `ORDER/CREDIT` 流水；秒杀活动满足
  `available + reserved + 已支付订单数 = total_stock`。这三条都有集成测试断言。
- **流水分页**：按 `id` 降序游标（`cursor` 为上一页最后一条 `entryId`），`limit` 默认 20、上限 100，
  用 `limit+1` 判断是否还有下一页，不执行 `COUNT(*)`；条目只暴露 `entryId`、`bizType=ORDER`、
  `orderNo`、`effectType=CREDIT`、`delta`、`balanceAfter`、`createdAt`——内部订单 `id`、`user_id`
  与他人流水都不会出现。
- F5 的并发/回滚测试同样只验证**正确性**（只入账一次、只结转一次、失败四张表原样），不是压测数据。

## 订单超时关闭（F6）

超时关闭**没有公开 API**：没有“用户取消订单”、没有“管理员强制关单”。它只能由服务端后台触发，
客户端能做的只有重新查询订单（`GET /orders/{orderNo}`）。

| 配置 | 默认 | 含义 |
|---|---|---|
| `tokenx.order.timeout.enabled` | `true` | 是否启用后台触发；测试环境为 `false` |
| `tokenx.order.timeout.interval` | `30s` | 两次触发的间隔（`fixedDelay`，从上次结束算起）；必须为正 |
| `tokenx.order.timeout.max-orders-per-run` | `100` | 单次触发最多关闭多少单；必须为 `1..1000` |

非法配置**启动失败**（间隔非正、批量非正或超过上限），不静默修正。

要点：

- **边界唯一**：支付要求 `expires_at > now`，关单要求 `expires_at <= now`。两个谓词在任何时刻最多
  只有一个成立，所以“已支付被关闭”“已关闭又入账”在状态层面就不可能发生。
- **每单一个短事务**：领取 → 关单 → 释放库存 → 提交，循环处理下一单。没有任何“一次锁一批”的做法，
  失败时只会丢一笔，已提交的订单不会因为后面的失败而回滚。
- **`FOR UPDATE SKIP LOCKED` 领取**：`WHERE status=0 AND expires_at<=? ORDER BY expires_at,id LIMIT 1
  FOR UPDATE SKIP LOCKED`。别的 Worker 正在处理的订单会被**跳过**而不是排队等待，因此多实例可以
  并行分摊积压；`idx_order_timeout(status, expires_at)` 提供有序访问，不产生 filesort。
- **条件更新是闸门**：`UPDATE purchase_order SET status=2, closed_at=?, updated_at=? WHERE id=?
  AND status=0 AND expires_at<=?`，影响 0 行即视为不变量破坏并整笔回滚；`paid_at` 保持 `NULL`。
- **普通订单只改订单**：`NORMAL` 完全不读不写 `activity_inventory`。
- **秒杀订单同事务归还预占**：`reserved_stock-1, available_stock+1 WHERE reserved_stock>0`，影响 0 行
  说明订单声称持有并不存在的预占，抛错回滚——绝不允许“关了单但库存没还”。
- **重复执行安全**：已 `CLOSED` 的订单不再满足 `status=0`，重复扫描不会二次释放库存。
- **锁序与支付一致**：`purchase_order → activity_inventory`，所以支付与关单不会形成等待环。
- **单笔失败结束本次运行**：失败的订单会原样留在 `UNPAID`，若在同一轮里立刻重试就会反复领取同一行；
  因此本次运行停止并记录 ERROR，下一次触发再试（由间隔兜底，不会形成紧循环）。
- F6 **零 DDL**：没有 V10，schema 仍为 V9，复用 `V8` 的 `idx_order_timeout(status, expires_at)`。
- F6 的测试同样只证明**正确性**（只关一次、只还一次、失败无半完成状态），不是压测数据。

## 前端（`frontend/`）

```bash
cd frontend
pnpm install
pnpm dev        # http://localhost:5173 ，/api 由 Vite 代理到 127.0.0.1:8080
pnpm lint
pnpm test
pnpm build
```

页面：登录 / 注册并登录、当前用户、公开的套餐列表 `/products`（未登录可访问，展示名称、Token
额度与格式化后的价格，支持“加载更多”并按服务端 `nextCursor` 续页，每个在售套餐带“立即购买”入口）、
公开的活动详情 `/activities/:id`（展示活动价、Token 额度、起止时间、可用库存与
`UPCOMING`/`ACTIVE`/`ENDED`；库存为 0 时显示“已售罄”）、订单详情 `/orders/:orderNo`
（展示服务端快照：单号、套餐、额度、应付金额、支付截止与支付时间），以及钱包 `/wallet`
（可用额度与流水分页）。Token 存在浏览器 `localStorage`，由统一的 HTTP 拦截器附加到请求头。

订单页只在状态为 `UNPAID` 时显示“模拟支付”，请求进行中按钮禁用（`aria-busy`）以防重复点击；
支付成功后**用服务端返回的订单**替换本地状态，因此立即变成“已支付”并出现支付时间。`PAID` 与
`CLOSED` 没有支付按钮。`404`（订单不存在或属于他人）、`409`（已过期/已关闭）与网络失败各给出一句
明确提示；页面不显示倒计时，也不拿浏览器时间判断是否到期——那是服务端的判断。

订单页还有“刷新订单状态”：重新请求 `GET /orders/{orderNo}`，用返回的订单替换页面状态，因此超时
扫描把订单改为 `CLOSED` 后，点一下就能看到“已关闭”和关闭时间。刷新期间按钮禁用，重复点击只发一次
请求；**刷新失败只显示错误，不会把本地状态改成关闭**。没有自动轮询，也没有倒计时——为一个展示
功能制造持续 QPS 不值得。

钱包页需要登录，未登录直接跳 `/login`；余额与流水都来自服务端，页面不做本地累加。流水按服务端
`nextCursor` 续页，失败时可重试；“加载更多”失败只影响追加，不会清掉已经显示的记录。

活动页的抢购按钮只在服务端返回 `ACTIVE` 且未售罄时可点，其余状态按钮禁用并写明原因（即将开始 /
活动已结束 / 已售罄）；未登录点击跳转登录页，登录后点击才真正下单。每次下单用浏览器原生
`crypto.randomUUID()` 生成幂等键；**网络中断或 5xx 之后重试复用同一个键**（服务端可能已经建单），
而业务拒绝（如已购买、已售罄）视为明确答复，下一次尝试会换新键。

套餐列表的“立即购买”是普通购买渠道：未登录点击只跳 `/login`、不发请求；登录后只发送 `packageId`
与幂等键，成功即跳转订单详情页，请求进行中所有购买按钮禁用。幂等键**按套餐保存**——同一套餐网络失败
后重试复用原键，切到另一个套餐则用新键，避免把 A 的未决请求键拿去买 B。

活动页只展示服务端返回的 `runtimeStatus`，不在浏览器里比较时间来判断活动是否开始——否则前端与
后端会在边界时刻给出不同答案。时间按 `Asia/Shanghai`（UTC+8）格式化，仅用于展示。订单页同样
不做倒计时：支付截止时间是服务端固化的事实，浏览器再算一遍只会制造第二个「真相」。

## 数据库迁移

- 迁移脚本位于 `src/main/resources/db/migration/`，命名 `V<版本>__<说明>.sql`。
- 应用启动时由 Flyway 自动执行；**禁止**手工改已发布的迁移文件，也禁止用 ORM 自动建表覆盖。
- 已应用：`V1` 探针表、`V2` 删除探针表、`V3` `app_user`、`V4` `token_account`（F1）、
  `V5` `token_package`、`V5.1` 套餐金额上限 CHECK（F2）、`V6` `seckill_activity`、`V7`
  `activity_inventory`（F3）、`V8` `purchase_order`（F4）、`V9` `token_ledger`（F5，当前版本）。
- **F4.5 没有新增迁移**：普通购买复用 `V8` 已建好的 `order_type` / 可空 `activity_id` /
  `ck_order_activity`。**F6 同样没有新增迁移**：超时关闭只复用 `V8` 的
  `idx_order_timeout(status, expires_at)`，schema 版本仍是 `V9`。
- **V9 只建 `token_ledger`**：`uk_ledger_biz(biz_type,biz_id,effect_type)` 是重复入账的第二道防线，
  `idx_ledger_user(user_id)` 服务按用户降序游标翻页；3 条 CHECK 固定业务/效果枚举并要求
  `delta` 的符号与 `effect_type` 一致（0 变动不接受）。V1–V8/V5.1 一个字都没改。
- **从零空库验证**需要空 schema：应用账号没有 `CREATE/DROP DATABASE` 权限，因此该验证由人工执行
  （`docs/DEVELOPMENT_PLAN.md` F1 记录中给出了确切命令）；自动化测试覆盖的是「升级到 V9 +
  重复 migrate 不重复建对象 + 索引/约束/排序规则真实生效」。

## 配置与密钥

- 默认配置在 `src/main/resources/application.yml`，**不包含任何真实密码或密钥**。
- 本地覆盖配置放在 `application-local.yml`（已忽略），或通过 `TOKENX_DB_USERNAME` /
  `TOKENX_DB_PASSWORD` / `TOKENX_JWT_SECRET` / `TOKENX_JWT_TTL` /
  `TOKENX_ORDER_PAYMENT_TIMEOUT` 环境变量注入。
- `tokenx.auth.jwt.secret` 少于 32 字符时应用启动失败：这是有意的，避免用弱密钥或空密钥上线。
- `tokenx.order.payment-timeout`（默认 `15m`）必须为正且不超过 `24h`，否则应用启动失败：
  “永不过期”的订单比启动失败更糟。该值在创建订单时固化进 `expires_at`。
- `tokenx.order.timeout.*` 控制超时扫描：`interval` 必须为正、`max-orders-per-run` 必须在
  `1..1000`，否则应用启动失败；测试环境通过 `application-test.yml` 把 `enabled` 设为 `false`，
  避免后台线程改动测试正在断言的订单。
- JWT Secret、数据库账号密码等只能通过本地配置/环境变量提供，禁止提交到 Git。

## 目录结构

```text
src/main/java/com/tokenx/
├── TokenXApplication.java          启动类
├── auth/                           F1 身份模块：注册、登录、当前用户
│   ├── controller/  service/  mapper/  model/  dto/
├── catalog/                        F2 套餐模块：创建、上下架、公共列表与详情
│   ├── controller/  service/  mapper/  model/  dto/
├── activity/                       F3 活动模块：创建、发布/下架、公开详情、活动快照与条件扣库存
│   ├── controller/  service/  mapper/  model/  dto/
├── order/                          F4/F4.5 订单模块：秒杀下单、普通购买、幂等键、订单查询
│   ├── controller/  service/  mapper/  model/  dto/  support/  config/
│   └── timeout/                    F6 超时关闭：有界扫描 + 每单一个短事务 + 调度触发
├── payment/                        F5 模拟支付：状态闸门、库存结转、入账事务
│   ├── controller/  service/
├── wallet/                         F5 钱包：余额查询、不可变流水与游标分页
│   ├── controller/  service/  mapper/  model/  dto/
└── infrastructure/                 通用基础设施（安全、异常转换、时间源、外部适配）
    ├── web/                        健康检查、统一错误、requestId
    ├── security/                   Spring Security 配置、JWT 签发与本地验签
    ├── money/                      金额与额度上限常量（跨模块共享的唯一数值契约）
    ├── time/                       唯一的 Clock Bean（生产为 UTC）
    └── error/                      业务异常类型
src/main/resources/
├── application.yml                 默认配置（无密码、无密钥）
├── db/migration/                   Flyway 版本化迁移
src/test/java/com/tokenx/           与主代码镜像的测试（auth/catalog/activity/order/payment/wallet 为真实 MySQL 集成测试）
frontend/                           React 19 + TS + Vite 前端
docs/                               需求、工程规范、实施计划、Git 工作流、版本锁定
```

账户模型 `auth/model/TokenAccount` 与其 Mapper 仍留在 `auth`（F1 与用户同事务创建）：钱包与支付
复用它读写余额，不重复建第二份账户模型；`token_ledger` 自己的实体与 Mapper 属于 `wallet`。
业务模块（`messaging` 等）按 `docs/DEVELOPMENT_PLAN.md` 的顺序逐个引入，**不预先创建空包**。

## 项目文档

| 文档 | 内容 |
|---|---|
| [docs/PROJECT_SPEC.md](docs/PROJECT_SPEC.md) | 业务边界、技术选型、数据模型、接口初稿 |
| [docs/DATABASE_DESIGN.md](docs/DATABASE_DESIGN.md) | 已决表结构、索引、事务、对账与迁移顺序 |
| [docs/HIGH_CONCURRENCY_DESIGN.md](docs/HIGH_CONCURRENCY_DESIGN.md) | Redis/Kafka 高并发路线、背压、补偿、指标与故障矩阵 |
| [docs/ENGINEERING_STANDARDS.md](docs/ENGINEERING_STANDARDS.md) | 编码、测试、设计模式与评审要求 |
| [docs/DEVELOPMENT_PLAN.md](docs/DEVELOPMENT_PLAN.md) | 唯一的任务实施顺序与验收门槛 |
| [docs/GIT_WORKFLOW.md](docs/GIT_WORKFLOW.md) | 分支、提交、危险操作规范 |
| [docs/REFERENCES.md](docs/REFERENCES.md) | 参考资料与采用边界 |
| [docs/REFERENCE_MAPPING.md](docs/REFERENCE_MAPPING.md) | 黑马点评已审阅文件与改造映射 |
| [docs/VERSION_LOCK.md](docs/VERSION_LOCK.md) | 依赖版本锁定与 MySQL/Flyway 兼容性结论 |
| [AGENTS.md](AGENTS.md) | 开发代理总规则 |

## 许可

见 [LICENSE](LICENSE)。
