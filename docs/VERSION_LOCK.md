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
| Spring Security（F1 引入） | BOM | 7.1.1 |
| spring-security-oauth2-jose（F1 引入） | BOM | 7.1.1 |
| nimbus-jose-jwt（JWT 编解码） | Spring Security 传递 | 10.9.1 |
| Hibernate Validator（F1 引入） | BOM | 9.1.3.Final |
| Jakarta Validation API | BOM | 3.1.1 |
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

## 前端依赖（F1 引入，`frontend/pnpm-lock.yaml` 固定）

| 组件 | 版本 | 说明 |
|---|---|---|
| React / React DOM | 19.3.1 | `PROJECT_SPEC.md` §2 指定的 React 19 |
| TypeScript | 5.9.3 | 规范要求 TS 5.x，不使用更新的主版本 |
| Vite | 7.3.7 | 开发服务器 + 生产构建 |
| @vitejs/plugin-react | 5.2.0 | 与 Vite 7 匹配 |
| vitest | 3.2.7 | 组件测试（jsdom 26.1.0） |
| axios | 1.20.0 | 统一 HTTP 客户端（规范默认选择） |
| react-router-dom | 7.18.4 | 路由 |
| eslint 9.39.5 / typescript-eslint 8.71.2 | — | `pnpm lint` 门槛 |
| Node.js / pnpm | 26.9.0 / 12.14.0 | 本机实测版本 |

> 所有前端依赖在 `package.json` 中写**精确版本**（无 `^`/`~`），实际解析结果由 lockfile 固定。
> pnpm 12 默认拦截依赖构建脚本，`pnpm-workspace.yaml` 中仅放行 `esbuild`（Vite 需要）。
> `typescript-eslint` 固定 8.71.2 而非 8.72.0：后者发布不足 24 小时，被 pnpm 的 `minimumReleaseAge` 供应链策略拒绝。

## MySQL 9.7 与 Flyway 兼容性结论（F0-2 实测）

- Flyway 官方 verified versions 未明确覆盖 MySQL 9.7，因此按要求做了真实迁移验证：
  在空库 `tokenx_test`（MySQL 9.7.2）上执行 `V1__create_schema_probe.sql` → **成功**，
  `flyway_schema_history` 记录 `version=1, success=1`；再次启动时输出
  `Schema tokenx_test is up to date. No migration necessary.`，未重复建表。
- 结论：**Flyway 12.4.0 + MySQL 9.7.2 可用**，无需降级或关闭迁移。
- 该结论仅覆盖 DDL/DML 基础路径；F1 引入真实业务表时仍需在本表追加验证记录。

## F1 迁移验证追加记录（2026-10-20 实测）

- 升级路径：`tokenx_test`（已有 V1）→ 应用启动 → V2/V3/V4 依次应用成功，`flyway_schema_history` 共 4 条、
  无 failed；`schema_probe` 已被 V2 删除；再次 `flyway.migrate()` 返回 `migrationsExecuted=0`（自动化断言）。
- 空库路径：独立临时 MySQL 9.7.2 实例（端口 3307、独立 datadir、root 空密码），库名 `tokenx_clean` 由 JDBC
  `createDatabaseIfNotExist=true` 新建 → V1→V4 全部应用（`Successfully applied 4 migrations`）→ 终态表为
  `app_user` / `token_account` / `flyway_schema_history`；真实 HTTP 注册+登录成功；二次启动输出
  `Schema tokenx_clean is up to date. No migration necessary.`，历史仍为 4 条。验证后实例与 datadir 已销毁。
- 约束实测：`uk_user_username` 在 `utf8mb4_0900_ai_ci` 下使 `Alice` / `alice` 冲突；`CHECK (role IN (0,1))`
  拒绝非法角色；`token_account.available_balance` 为 `BIGINT UNSIGNED`，负数被拒绝。
