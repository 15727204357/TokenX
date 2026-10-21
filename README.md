# TokenX

TokenX 是一个 **AI Token 套餐限时秒杀交易平台**的 Java 重构工程：管理员配置套餐与限时活动，用户在活动时间内抢购，订单支持模拟支付，支付成功后 Token 额度进入钱包并生成不可变流水。

> **当前状态**：`F0-1`～`F0-3` 与 `F1` 完成——最小可运行骨架、MySQL 迁移链路、Redis 连通性、
> 数据库/高并发设计基线，以及注册、登录、JWT 鉴权、`/users/me` 和最小 React 登录页。
> 套餐、活动、订单、支付与钱包接口尚未实现。
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

前缀 `/api/v1`，除健康检查与 `/auth/**` 外全部需要 `Authorization: Bearer <JWT>`。

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

## 前端（`frontend/`）

```bash
cd frontend
pnpm install
pnpm dev        # http://localhost:5173 ，/api 由 Vite 代理到 127.0.0.1:8080
pnpm lint
pnpm test
pnpm build
```

页面目前只有登录 / 注册并登录，进入后展示 `/users/me` 返回的当前用户。Token 存在浏览器
`localStorage`，由统一的 HTTP 拦截器附加到请求头。

## 数据库迁移

- 迁移脚本位于 `src/main/resources/db/migration/`，命名 `V<版本>__<说明>.sql`。
- 应用启动时由 Flyway 自动执行；**禁止**手工改已发布的迁移文件，也禁止用 ORM 自动建表覆盖。
- 已应用：`V1` 探针表、`V2` 删除探针表、`V3` `app_user`、`V4` `token_account`（后三个属于 F1）。
- **从零空库验证**需要空 schema：应用账号没有 `CREATE/DROP DATABASE` 权限，因此该验证由人工执行
  （`docs/DEVELOPMENT_PLAN.md` F1 记录中给出了确切命令）；自动化测试覆盖的是「升级到 V4 +
  重复 migrate 不重复建对象 + 索引/约束/排序规则真实生效」。

## 配置与密钥

- 默认配置在 `src/main/resources/application.yml`，**不包含任何真实密码或密钥**。
- 本地覆盖配置放在 `application-local.yml`（已忽略），或通过 `TOKENX_DB_USERNAME` /
  `TOKENX_DB_PASSWORD` / `TOKENX_JWT_SECRET` / `TOKENX_JWT_TTL` 环境变量注入。
- `tokenx.auth.jwt.secret` 少于 32 字符时应用启动失败：这是有意的，避免用弱密钥或空密钥上线。
- JWT Secret、数据库账号密码等只能通过本地配置/环境变量提供，禁止提交到 Git。

## 目录结构

```text
src/main/java/com/tokenx/
├── TokenXApplication.java          启动类
├── auth/                           F1 身份模块：注册、登录、当前用户
│   ├── controller/  service/  mapper/  model/  dto/
└── infrastructure/                 通用基础设施（安全、异常转换、外部适配）
    ├── web/                        健康检查、统一错误、requestId
    ├── security/                   Spring Security 配置、JWT 签发与本地验签
    └── error/                      业务异常类型
src/main/resources/
├── application.yml                 默认配置（无密码、无密钥）
├── db/migration/                   Flyway 版本化迁移
src/test/java/com/tokenx/           与主代码镜像的测试（auth 包为 F1 集成测试）
frontend/                           React 19 + TS + Vite 前端
docs/                               需求、工程规范、实施计划、Git 工作流、版本锁定
```

业务模块（`catalog`、`activity`、`order`、`payment`、`wallet`）按 `docs/DEVELOPMENT_PLAN.md` 的顺序逐个引入，**不预先创建空包**。

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
