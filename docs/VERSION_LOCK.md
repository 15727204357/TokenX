# 依赖版本锁定表

> 每次新增依赖时更新，并与 `./mvnw dependency:tree` 的实际输出核对。原则：能由 Spring Boot BOM 管理的版本一律不手写版本号。

## 版本来源规则

1. **BOM 优先**：只要 `spring-boot-dependencies:4.1.1` 管理，`pom.xml` 中就不写 `<version>`。
2. **唯一例外**：MyBatis-Plus 不在 Boot BOM 内，因此 `mybatis-plus.version` 是本项目唯一显式固定的第三方版本。
3. **禁止 `latest`/版本区间**：核心组件必须是精确版本，升级需单独评审（`PROJECT_SPEC.md` §2）。

## 实际解析结果（2026-10-20，`./mvnw dependency:tree` 实测）

| 组件 | 引入方式 | 实际解析版本 |
|---|---|---|
| Spring Boot | parent `4.1.1` | 4.1.1 |
| Spring Framework | BOM | 7.0.9 |
| 内嵌 Tomcat | BOM | 11.0.24 |
| Jackson | BOM | 3.1.5 |
| HikariCP | BOM | 7.0.2 |
| MyBatis-Plus（boot4 starter 及其模块） | **显式 3.5.17** | 3.5.17 |
| MyBatis / MyBatis-Spring | MyBatis-Plus 传递 | 3.5.19 / 4.0.0 |
| mysql-connector-j | BOM | 9.7.0 |
| Flyway（core + mysql） | BOM | 12.4.0 |
| Spring Data Redis | BOM | 4.1.1 |
| Lettuce | BOM | 7.5.2.RELEASE |
| JUnit Jupiter | BOM | 6.0.3 |
| Mockito | BOM | 5.23.0 |
| Logback | BOM | 1.5.38 |
| Micrometer | BOM | 1.17.1 |

## 本机基础设施（实测）

| 组件 | 本机版本 | 说明 |
|---|---|---|
| JDK | Temurin 21.0.12.1 LTS | `mvn -version` 已确认 21 |
| Maven | Wrapper 3.9.16 | 仓库内 `./mvnw` |
| MySQL Server | 9.7.2 | 应用走专用账号，`root` 只用于建库 |
| Redis Server | 8.10.2 | 本机无密码，仅回环地址 |
| Kafka Broker | 4.3.1（KRaft） | **V1.5 才接入**，当前不在 classpath |

## Kafka 客户端版本（仅登记，未接入代码）

- `kafka-clients` 由 BOM 固定为 **4.2.1**，`spring-kafka` 为 **4.1.1**。
- Broker 为 4.3.1，客户端 4.2.1：`PROJECT_SPEC.md` §2 明确“不要求客户端同 Broker 小版本”，故不手工对齐，也不手写 Kafka 版本号。
- 接入前（F8）须单独做连通性验证并补一次本表记录。

## MySQL 9.7 与 Flyway 兼容性结论（F0-2 实测）

- Flyway 官方 verified versions 未明确覆盖 MySQL 9.7，因此按要求做了真实迁移验证：
  在空库 `tokenx_test`（MySQL 9.7.2）上执行 `V1__create_schema_probe.sql` → **成功**，
  `flyway_schema_history` 记录 `version=1, success=1`；再次启动时输出
  `Schema tokenx_test is up to date. No migration necessary.`，未重复建表。
- 结论：**Flyway 12.4.0 + MySQL 9.7.2 可用**，无需降级或关闭迁移。
- 该结论仅覆盖 DDL/DML 基础路径；F1 引入真实业务表时仍需在本表追加验证记录。
