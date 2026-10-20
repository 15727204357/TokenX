# TokenX

TokenX 是一个 **AI Token 套餐限时秒杀交易平台**的 Java 重构工程：管理员配置套餐与限时活动，用户在活动时间内抢购，订单支持模拟支付，支付成功后 Token 额度进入钱包并生成不可变流水。

> **当前状态**：`F0-1`～`F0-3` 完成——最小可运行骨架、MySQL 迁移链路、Redis 连通性，以及数据库/高并发设计基线已验证或收敛。
> 还没有业务接口、业务表、认证和前端页面。
> 交易过程中的“支付”是**本地模拟**，不接入任何真实资金渠道，不可用于生产支付场景。

## 技术栈

| 层 | 选型 |
|---|---|
| JDK | Temurin 21 (LTS) |
| 后端 | Spring Boot 4.1.1 / Spring Framework 7，Spring MVC |
| 构建 | Maven 3.9.x + Maven Wrapper（仓库内置 `mvnw`） |
| 持久化 | MyBatis-Plus 3.5.17（boot4 starter）+ MySQL 9.7 + Flyway 12.4 |
| 缓存 / 消息 | Redis 8.10.x / Kafka 4.3.1（V1 关键链路不依赖，V1.5 才接入） |
| 前端 | React 19 + TypeScript + Vite（F1 起引入） |

各依赖的实际解析版本见 [docs/VERSION_LOCK.md](docs/VERSION_LOCK.md)。

## 环境要求

- JDK 21（`java -version` 必须为 21）
- MySQL 9.7.x、Redis 8.10.x 本机运行
- 无需预装 Maven，仓库自带 Wrapper

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

# 启动应用（使用 local profile 读取本地密码）
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

健康检查：

```bash
curl -s http://localhost:8080/api/v1/health
# {"status":"UP","service":"tokenx","time":"2026-10-20T11:55:12.345Z"}
```

Actuator 健康端点（运维用）：`http://localhost:8080/actuator/health`

> 说明：当前所有 Spring 测试都会加载真实数据源，因此 **不启动 MySQL 时 `./mvnw verify` 会失败**。
> 这是 F0-2 的既定要求（真实组件集成验证），不是 Mock。测试只操作 `tokenx_test`，不会写开发库。

## 数据库迁移

- 迁移脚本位于 `src/main/resources/db/migration/`，命名 `V<版本>__<说明>.sql`。
- 应用启动时由 Flyway 自动执行；**禁止**手工改已发布的迁移文件，也禁止用 ORM 自动建表覆盖。
- 业务表按 Feature 逐个引入，不在骨架阶段预建。

## 配置与密钥

- 默认配置在 `src/main/resources/application.yml`，**不包含任何真实密码**。
- 本地覆盖配置放在 `application-local.yml`（已忽略），或通过 `TOKENX_DB_USERNAME` / `TOKENX_DB_PASSWORD` 环境变量注入。
- JWT Secret、数据库账号密码等只能通过本地配置/环境变量提供，禁止提交到 Git。

## 目录结构

```text
src/main/java/com/tokenx/
├── TokenXApplication.java          启动类
└── infrastructure/                 通用基础设施（安全、异常转换、外部适配）
    ├── web/                        HTTP 层公共接口，如健康检查
    └── persistence/                F0-2 连通性探针（MyBatis-Plus 映射验证）
src/main/resources/
├── application.yml                 默认配置（无密码）
├── db/migration/                   Flyway 版本化迁移
src/test/java/com/tokenx/           与主代码镜像的测试
docs/                               需求、工程规范、实施计划、Git 工作流、版本锁定
```

业务模块（`auth`、`catalog`、`activity`、`order`、`payment`、`wallet`）按 `docs/DEVELOPMENT_PLAN.md` 的顺序逐个引入，**不预先创建空包**。

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
