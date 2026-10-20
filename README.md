# TokenX

TokenX 是一个 **AI Token 套餐限时秒杀交易平台**的 Java 重构工程：管理员配置套餐与限时活动，用户在活动时间内抢购，订单支持模拟支付，支付成功后 Token 额度进入钱包并生成不可变流水。

> **当前状态**：`F0-1` 最小可运行后端骨架。只有健康检查接口，**没有**业务表、认证、Redis、Kafka 或前端页面。
> 交易过程中的“支付”是**本地模拟**，不接入任何真实资金渠道，不可用于生产支付场景。

## 技术栈

| 层 | 选型 |
|---|---|
| JDK | Temurin 21 (LTS) |
| 后端 | Spring Boot 4.1.1 / Spring Framework 7，Spring MVC |
| 构建 | Maven 3.9.x + Maven Wrapper（仓库内置 `mvnw`） |
| 前端 | React 19 + TypeScript + Vite（F1 起引入，见 `docs/DEVELOPMENT_PLAN.md`） |
| 数据库 | MySQL 9.7.x（F0-2 接入） |
| 缓存 / 消息 | Redis 8.10.x / Kafka 4.3.1（V1 关键链路不依赖，见规划） |

## 环境要求

- JDK 21（`java -version` 必须为 21）
- 无需预装 Maven，仓库自带 Wrapper

## 运行

```bash
# 编译 + 单元测试 + 集成测试
./mvnw clean verify

# 启动应用（默认 http://localhost:8080）
./mvnw spring-boot:run
```

健康检查：

```bash
curl -s http://localhost:8080/api/v1/health
# {"status":"UP","service":"tokenx","time":"2026-10-20T11:55:12.345Z"}
```

Actuator 健康端点（运维用）：`http://localhost:8080/actuator/health`

## 配置与密钥

- 默认配置在 `src/main/resources/application.yml`，**不包含任何真实密码**。
- 本地覆盖配置放在 `application-local.yml`（已被 `.gitignore` 忽略），或用环境变量注入。
- JWT Secret、数据库账号密码等只能通过本地配置/环境变量提供，禁止提交到 Git。

## 目录结构

```text
src/main/java/com/tokenx/
├── TokenXApplication.java          启动类
└── infrastructure/                 通用基础设施（安全、异常转换、外部适配）
    └── web/                        HTTP 层公共接口，如健康检查
src/test/java/com/tokenx/           与主代码镜像的测试
docs/                               需求、工程规范、实施计划、Git 工作流
```

业务模块（`auth`、`catalog`、`activity`、`order`、`payment`、`wallet`）按 `docs/DEVELOPMENT_PLAN.md` 的顺序逐个引入，**不预先创建空包**。

## 项目文档

| 文档 | 内容 |
|---|---|
| [docs/PROJECT_SPEC.md](docs/PROJECT_SPEC.md) | 业务边界、技术选型、数据模型、接口初稿 |
| [docs/ENGINEERING_STANDARDS.md](docs/ENGINEERING_STANDARDS.md) | 编码、测试、设计模式与评审要求 |
| [docs/DEVELOPMENT_PLAN.md](docs/DEVELOPMENT_PLAN.md) | 唯一的任务实施顺序与验收门槛 |
| [docs/GIT_WORKFLOW.md](docs/GIT_WORKFLOW.md) | 分支、提交、危险操作规范 |
| [docs/REFERENCES.md](docs/REFERENCES.md) | 参考资料与采用边界 |
| [docs/REFERENCE_MAPPING.md](docs/REFERENCE_MAPPING.md) | 黑马点评已审阅文件与改造映射 |
| [AGENTS.md](AGENTS.md) | 开发代理总规则 |

## 许可

见 [LICENSE](LICENSE)。
