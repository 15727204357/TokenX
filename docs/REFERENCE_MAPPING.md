# 黑马点评参考映射（F0-1 建立，随 F 任务补全）

> 目的：记录**实际读过**的参考仓库文件、借鉴的业务思想、本项目对应的实现位置，以及必须改造的地方。
> 只研究思路，不复制源码：参考仓库未见明确 LICENSE 声明，本项目按 `docs/REFERENCES.md` 与 `AGENTS.md` 的要求独立实现。

## 参考基线

| 项 | 值 |
|---|---|
| 仓库 | https://github.com/cs001020/hmdp （`master`） |
| 审阅提交 | `b9026b6da227`，2022-10-19 |
| 技术栈 | Spring Boot 2.7.4 parent、MyBatis-Plus 3.5.2、Redisson 3.17.7、hutool-all 5.8.8、`mysql-connector-java`、`spring-boot-starter-data-redis` |
| 包结构 | `controller` / `service` + `service.impl` / `mapper` / `entity` / `dto` / `interceptor` / `config` / `utils`，Mapper XML 在 `resources/mapper/` |

## 已审阅文件与借鉴点

| 参考文件 | 已确认的做法 | 借鉴的思想 | 本项目落点 |
|---|---|---|---|
| `pom.xml` | Boot 2.7.4 / Java 8 时代的依赖组合 | 分层依赖：Web + MyBatis-Plus + Redis | `pom.xml` 全部按 Boot **4.1.1** BOM 重写，不保留任何旧版本号 |
| `src/main/java/com/hmdp/controller/VoucherOrderController.java` 等 | Controller 只做参数接收与结果封装 | Controller → Service → Mapper 单向调用 | `*/controller` → `*/service` → `*/mapper`，Controller 不写业务与 SQL（见 `AGENTS.md` §5） |
| `src/main/resources/seckill.lua` | 用 Lua 原子完成：`get stock` 判库存 → `sismember` 判重复下单 → `incrby -1` → `sadd` → `xadd stream.orders` | 「资格/库存判断必须原子」这一核心不变量 | F4 先用 **MySQL 条件 UPDATE + 唯一约束**建立同步基线；Lua 只在 F9 且有 ADR 后引入 |
| `src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java` | `@PostConstruct` 启动单线程 `ExecutorService` 从 Redis Stream（`stream.orders`，组 `g1`/`c1`）阻塞读取并落单；`@Resource @Lazy` 自注入拿代理；Redisson `RLock` | 异步落单要区分「请求受理」与「订单创建成功」 | 本项目**不在 V1 使用 Redis Stream**；V1.5 用 Kafka + Transactional Outbox，并与同步基线对比后再定（F8/F9） |
| `src/main/java/com/hmdp/interceptor/`（`LoginInterceptor`、`RefreshTokenInterceptor`）+ `utils/UserHolder` | 双拦截器 + `ThreadLocal` 保存当前用户 | 「用户身份来自服务端上下文，不信任客户端 userId」 | F1 改用 **Spring Security + JWT**，身份从 `SecurityContext` 取得；不迁移 `ThreadLocal` 自管理 |
| `src/main/java/com/hmdp/dto/Result.java` | 统一返回包装 `Result{success,errorMsg,data}` | 统一响应/错误结构 | V1 使用 `code`/`message`/`requestId` + 恰当 HTTP 状态码（F1 建立统一异常处理，见 `PROJECT_SPEC.md` §7） |
| `hmdp.sql`（11 张表） | `tb_voucher.type` 区分普通券/秒杀券；`tb_seckill_voucher` 以 `voucher_id` 为主键 1:1 扩展库存与时间窗；`tb_voucher_order` 只有 `voucher_id`，**无二级索引、无 `unique(voucher,user)`、无金额快照、无超时字段** | 「普通购买与秒杀可以共用一张订单表」这一结论；同时看清**不能**照搬的约束缺失 | F0-3 决策见 `docs/DATABASE_DESIGN.md` §14：共用 `purchase_order`，但必须补 `UNIQUE(activity_id,user_id)`、`request_id` 唯一键、价格/额度快照、`expires_at`、`user_id` 索引 |

## 明确不采纳的旧做法

| 旧做法 | 不采纳原因 | 本项目替代 |
|---|---|---|
| 自注入 `@Lazy` 自身以获取事务代理 | 隐式、易错，事务边界不可见 | 事务方法放在独立 Service 上，由外部调用方调用（`ENGINEERING_STANDARDS.md` §5） |
| `javax.annotation.*`、`WebMvcConfigurer` 旧拦截器链 | Boot 4 / Spring 7 使用 Jakarta 命名空间与 Security FilterChain | `jakarta.*`；鉴权由 Spring Security 配置 |
| Redisson 分布式锁 | 项目明确禁止引入 Redisson；单库条件下 DB 条件更新已能保证不变量 | MySQL 条件 UPDATE + 唯一索引 |
| Redis Stream 作为下单主链路 | 与后续 Kafka 形成两条竞争链路 | 同步基线优先；V1.5 再评审 |
| Lua 脚本里直接 `xadd` 发消息 | 把「缓存扣减」当成「订单已创建」 | 任何链路都不以 Redis 结果宣告订单创建成功（`PROJECT_SPEC.md` §9） |
| hutool 全家桶 | 单一工具需求不值得引入大依赖 | JDK 标准库 / Spring 自带能力 |
| 原 `application.yaml`、`hmdp.sql` | 业务模型不同（券/店铺/探店），且属于旧版本配置 | 按 `PROJECT_SPEC.md` §6 分阶段建表，配置从零写 |

## F0-1 实际落地情况

本轮只做骨架，因此上述映射中**仅 `pom.xml` 与包结构约定**已经生效：

- 已建立：`com.tokenx.infrastructure.web`（健康检查），业务模块包在其 Feature 开始前不创建。
- 待建设：`auth`(F1)、`catalog`(F2)、`activity`(F3)、`order`(F4)、`payment`/`wallet`(F5)、超时关单(F6)。

后续每个 F 任务开始时，须先补全与本任务相关的参考文件审阅记录，再改代码。
