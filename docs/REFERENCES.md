# 项目参考资料与采用边界

**所有链接用于学习思路、核对工具版本与框架 API，不等于可以任意复制第三方代码。** AI 在首次实现某模块时须先审查对应源代码、依赖及许可情况。

## 业务参考项目

1. 黑马点评（课堂实战参考）: https://github.com/cs001020/hmdp
   - 仓库 `master` 完整业务，`init` 初始项目；`pom.xml` 使用 Spring Boot 2.7.4 / Java8。
   - 优先学习 Controller–Service–Mapper、Redis 缓存与 Lua 原子准入思想；Redis Stream 仅用于理解原方案，不进入 TokenX 订单链路。
   - 不搬运旧框架代码；公开仓库不必然表示允许整库复制，需检查实际许可证。
2. 黑马点评另一参考说明: https://github.com/zhuxuchen/hm-dianping
   - 包含秒杀/缓存/签到等教程功能的介绍；仅用于交叉理解。
3. 原 TokenMarket: https://github.com/fuhuonvshen/TokenMarket
   - 理解 Token 套餐交易、签到、钱包、社区的产品表达；**不是 Java 工程结构模板**。

## 权威工程与兼容性参考

4. Spring Boot 4 官方参考文档: https://docs.spring.io/spring-boot/reference/
5. Spring Boot 4 系统要求: https://docs.spring.io/spring-boot/system-requirements.html
6. MyBatis-Plus Boot4 Starter 安装说明: https://baomidou.com/getting-started/install/
7. Spring Kafka 客户端与 Boot 兼容表: https://spring.io/projects/spring-kafka/
8. Spring Boot Testcontainers: https://docs.spring.io/spring-boot/reference/testing/testcontainers.html
9. Spring Boot 测试文档: https://docs.spring.io/spring-boot/reference/testing/spring-boot-applications.html
10. Flyway MySQL 验证版本： https://documentation.red-gate.com/fd/mysql-277579322.html
11. Apache Kafka 4.3 Producer 配置（acks、幂等、批处理）：https://kafka.apache.org/43/configuration/producer-configs/
12. Apache Kafka 4.3 消息交付语义：https://kafka.apache.org/43/design/design/#message-delivery-semantics
13. Redis Lua 原子执行与阻塞边界：https://redis.io/docs/latest/develop/programmability/eval-intro/
14. MySQL 9.7 InnoDB SQL 加锁行为：https://dev.mysql.com/doc/refman/9.7/en/innodb-locks-set.html

## 工程实践参考

15. Google Java Style Guide: https://google.github.io/styleguide/javaguide.html
16. Practical Test Pyramid: https://martinfowler.com/articles/practical-test-pyramid.html
17. Qoder 项目规则/AGENTS.md: https://docs.qoder.com/zh/user-guide/rules
18. Qoder Agent Mode: https://docs.qoder.com/user-guide/chat/agent
19. AGENTS.md 项目约定（背景）: https://github.com/agentpatterns-ai/website/blob/main/standards/agents-md.md

## Git 工程实践参考

20. Git 官方文档：https://git-scm.com/docs
21. Conventional Commits 1.0.0：https://www.conventionalcommits.org/en/v1.0.0/
22. GitHub 关于 `.gitignore` 的帮助：https://docs.github.com/en/get-started/git-basics/ignoring-files

对应本项目可执行规则：`docs/GIT_WORKFLOW.md`。

## 有意作出的选择

- 本项目使用 **Spring Boot 4**；不要搜到一个旧 Spring Boot 2 的教程就复制旧 API。
- Maven 使用 Spring Boot BOM，依赖要通过 `dependency:tree` 验证版本，不手填 Kafka client/Redis driver 版本。
- MySQL9.7 与 Flyway 的兼容性须在本机实测；Redgate MySQL 页面当前列出的 verified versions 不包括 9.7。
- React 前端重新写，不使用黑马点评的 Vue 老前端；只学习功能流程和接口契约。
