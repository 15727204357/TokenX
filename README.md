# TokenX

TokenX 是一个 **AI Token 套餐限时秒杀交易平台**的 Java 重构工程：管理员配置套餐与限时活动，用户在活动时间内抢购，订单支持模拟支付，支付成功后 Token 额度进入钱包并生成不可变流水。

> **当前状态**：`F0-1`～`F0-3`、`F1`、`F2`、`F3`、`F4` 完成——最小可运行骨架、MySQL 迁移链路、
> Redis 连通性、数据库/高并发设计基线，注册、登录、JWT 鉴权、`/users/me`，Token 套餐目录
> （管理员创建/上下架、游客游标分页列表与详情、公开列表页），秒杀活动配置与库存初始化
> （管理员创建/发布/下架、游客活动详情页、服务端时间驱动的运行状态），以及**同步秒杀下单**
> （MySQL 条件扣库存 + 唯一键一人一单 + 幂等键重放、订单详情页）。
> 支付与钱包接口尚未实现（F5 起），订单超时关闭在 F6。
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

## 下单接口（F4）

**这一步是 MySQL 强一致基线**：判断谁抢到的只有数据库语句本身。正确性路径上**没有** Redis 预扣、
Kafka/消息队列、本地内存队列或分布式锁——下单结果与并发量无关，只与库存行和唯一键有关。

| 方法 | 路径 | 说明 | 成功 | 主要失败 |
|---|---|---|---|---|
| POST | `/activities/{id}/orders` | 秒杀下单（需登录）。**无请求体**；必须带 `Idempotency-Key`（规范 36 位 UUID） | `201`（本次创建）或 `200`（同一 key 重放，不扣库存） | `400 VALIDATION_FAILED`、`401 UNAUTHORIZED`、`404 ACTIVITY_NOT_FOUND`、`409 ACTIVITY_NOT_ACTIVE` / `ACTIVITY_SOLD_OUT` / `ALREADY_PURCHASED` / `IDEMPOTENCY_KEY_REUSED` |
| GET | `/orders/{orderNo}` | 查询**自己的**订单（需登录） | `200` 订单快照 | `400 VALIDATION_FAILED`、`401 UNAUTHORIZED`、`404 ORDER_NOT_FOUND`（不存在与他人订单完全一致） |

```bash
# 下单：单号由服务端生成，客户端只提供幂等键
curl -s -X POST "http://localhost:8080/api/v1/activities/1/orders" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Idempotency-Key: $(uuidgen | tr 'A-Z' 'a-z')"
# 201 -> {"orderNo":"...","orderType":"SECKILL","status":"UNPAID","payAmountCent":799,...}

# 查询自己的订单
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
额度与格式化后的价格，支持“加载更多”并按服务端 `nextCursor` 续页）、公开的活动详情
`/activities/:id`（展示活动价、Token 额度、起止时间、可用库存与 `UPCOMING`/`ACTIVE`/`ENDED`；
库存为 0 时显示“已售罄”），以及订单详情 `/orders/:orderNo`（展示服务端快照：单号、套餐、额度、
应付金额与支付截止）。Token 存在浏览器 `localStorage`，由统一的 HTTP 拦截器附加到请求头。

活动页的抢购按钮只在服务端返回 `ACTIVE` 且未售罄时可点，其余状态按钮禁用并写明原因（即将开始 /
活动已结束 / 已售罄）；未登录点击跳转登录页，登录后点击才真正下单。每次下单用浏览器原生
`crypto.randomUUID()` 生成幂等键；**网络中断或 5xx 之后重试复用同一个键**（服务端可能已经建单），
而业务拒绝（如已购买、已售罄）视为明确答复，下一次尝试会换新键。

活动页只展示服务端返回的 `runtimeStatus`，不在浏览器里比较时间来判断活动是否开始——否则前端与
后端会在边界时刻给出不同答案。时间按 `Asia/Shanghai`（UTC+8）格式化，仅用于展示。订单页同样
不做倒计时：支付截止时间是服务端固化的事实，浏览器再算一遍只会制造第二个「真相」。

## 数据库迁移

- 迁移脚本位于 `src/main/resources/db/migration/`，命名 `V<版本>__<说明>.sql`。
- 应用启动时由 Flyway 自动执行；**禁止**手工改已发布的迁移文件，也禁止用 ORM 自动建表覆盖。
- 已应用：`V1` 探针表、`V2` 删除探针表、`V3` `app_user`、`V4` `token_account`（F1）、
  `V5` `token_package`、`V5.1` 套餐金额上限 CHECK（F2）、`V6` `seckill_activity`、`V7`
  `activity_inventory`（F3）、`V8` `purchase_order`（F4）。
- **从零空库验证**需要空 schema：应用账号没有 `CREATE/DROP DATABASE` 权限，因此该验证由人工执行
  （`docs/DEVELOPMENT_PLAN.md` F1 记录中给出了确切命令）；自动化测试覆盖的是「升级到 V8 +
  重复 migrate 不重复建对象 + 索引/约束/排序规则真实生效」。

## 配置与密钥

- 默认配置在 `src/main/resources/application.yml`，**不包含任何真实密码或密钥**。
- 本地覆盖配置放在 `application-local.yml`（已忽略），或通过 `TOKENX_DB_USERNAME` /
  `TOKENX_DB_PASSWORD` / `TOKENX_JWT_SECRET` / `TOKENX_JWT_TTL` /
  `TOKENX_ORDER_PAYMENT_TIMEOUT` 环境变量注入。
- `tokenx.auth.jwt.secret` 少于 32 字符时应用启动失败：这是有意的，避免用弱密钥或空密钥上线。
- `tokenx.order.payment-timeout`（默认 `15m`）必须为正且不超过 `24h`，否则应用启动失败：
  “永不过期”的订单比启动失败更糟。该值在创建订单时固化进 `expires_at`。
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
├── order/                          F4 订单模块：同步秒杀下单、幂等键、订单查询
│   ├── controller/  service/  mapper/  model/  dto/  support/  config/
└── infrastructure/                 通用基础设施（安全、异常转换、时间源、外部适配）
    ├── web/                        健康检查、统一错误、requestId
    ├── security/                   Spring Security 配置、JWT 签发与本地验签
    ├── money/                      金额与额度上限常量（跨模块共享的唯一数值契约）
    ├── time/                       唯一的 Clock Bean（生产为 UTC）
    └── error/                      业务异常类型
src/main/resources/
├── application.yml                 默认配置（无密码、无密钥）
├── db/migration/                   Flyway 版本化迁移
src/test/java/com/tokenx/           与主代码镜像的测试（auth/catalog/activity/order 为真实 MySQL 集成测试）
frontend/                           React 19 + TS + Vite 前端
docs/                               需求、工程规范、实施计划、Git 工作流、版本锁定
```

业务模块（`payment`、`wallet`）按 `docs/DEVELOPMENT_PLAN.md` 的顺序逐个引入，**不预先创建空包**。

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
