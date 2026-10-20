# TokenX Git 与代码交付规范（Qoder 必须遵守）

> 适用范围：TokenX 全部开发任务，包括后端、React 前端、SQL 迁移及文档。本文是 `AGENTS.md` 的执行细则，不用于引入 GitFlow 等不必要的重流程。**Git 是代码版本管理与审查手段，不能代替测试。**

## 1. 基础规则

- Git 为强制使用的版本控制工具；正式项目在首个功能开始前初始化仓库。
- 默认使用 `main` 作为稳定分支；一个独立、可验收功能对应一个短生命周期分支。
- **一轮 Qoder 任务只完成一个功能点**，只改必要文件、只提交该任务代码；测试通过再提交。
- 默认允许 AI 为用户明确交付的当前功能创建**本地功能分支、做局部暂存、创建本地提交**。若用户明确说本次不提交/不建分支，以用户要求为准。
- **未经用户单独明确同意，不允许自动 push、合并到 main、创建/合并 PR、删除分支、改远程配置、打发布 Tag 或发布 Release。**
- 不因失败就强行修改 Git 历史或破坏工作区；Git 操作与功能实现同样必须可审计。

## 2. 首次初始化（仅无 `.git/` 的新项目）

先执行 `git status` 判断是否已在 Git 仓库内（也可能是上级目录仓库）。**若已有仓库，严禁重新 `git init`、更换历史或覆盖原分支设置**。

确认当前目录是预期 TokenX 根目录后，新项目可初始化：

```bash
git init -b main
git status --short
```

检查用户已有文件和 `.gitignore`，特别排除本地密钥、数据库文件、日志和缓存；**不得执行 `git add .` 或 `git add -A` 作为默认操作**。仅明确选择项目规范与骨架文件暂存：

```bash
git add AGENTS.md docs/ QODER_START_PROMPT.md README_HANDOFF.md .gitignore .gitattributes .github/PULL_REQUEST_TEMPLATE.md
git diff --cached --check
git diff --cached --stat
```

只有在确认这批文件都属于本次基线、且不包含用户未授权内容时，创建基线提交：

```bash
git commit -m "docs: establish TokenX development standards"
git switch -c feature/F0-1-bootstrap
```

**如果 Git 作者姓名/邮箱尚未配置，停止提交并提醒用户自行配置 `git config user.name` 与 `git config user.email`，不得替用户编造身份。** 无远程仓库也能在本地使用 Git，不要自动创建 GitHub 仓库。

> 上述暂存命令针对交接包示例。项目已有文件与本例不同，应先检查实际文件，仅 `git add` 已核实存在的路径。

## 3. 分支命名及生命周期

- 稳定分支：`main`。除初始化基线外，不直接在 `main` 上开发新功能。
- 功能：`feature/F4-seckill-order`、`feature/F5-mock-payment`。
- 修复：`fix/F4-duplicate-order`；维护：`chore/build-config`；文档：`docs/git-workflow`。
- **单人项目不需要额外设 `develop`、`release/*`、多级 GitFlow。**
- 创建新分支前核对当前分支和未提交文件；如果存在可能冲突的用户修改，不覆盖、不 stash、不自动切换，先说明问题并征求处理方式。
- 功能完成并通过检查后提交到本地功能分支；**由用户审阅决定是否合并**。不要自动进入下一任务。

## 4. 每个任务必须执行的 Git 检查点

### A. 开始任务前（必做）

```bash
git status --short
git branch --show-current
git log -1 --oneline
```

1. 读取 `AGENTS.md` 和当前任务规范，确认工作区状态及分支。
2. 检查是否存在未提交的人工改动/其他功能代码；只允许修改当前任务必需文件。
3. 若工作区已有与当前任务冲突的变更，**暂停并报告，不允许擅自清理或丢弃**。
4. 已提交的既有文件可正常阅读。**禁止未经授权的大规模格式化或顺手重构**。

### B. 实现与验证（必做）

1. 明确当前 F 任务、验收标准及预计修改文件。
2. 先写必要测试，再做最小实现和小范围重构。
3. 执行与本次代码相关的实际测试（例如 `./mvnw clean verify`；React 改动用 `pnpm lint && pnpm build`）。
4. 对关键功能/数据/迁移/异步逻辑运行隔离集成测试；未运行就报告 NOT RUN，不伪造。
5. 检查 `git diff --check`、`git diff --stat`、`git status --short`，确认没有无关改动。

### C. 提交前（必做）

逐个添加需要提交的文件（路径只是示例，实际按任务调整）：

```bash
git add src/main/java/com/tokenx/seckill/ src/test/java/com/tokenx/seckill/
git diff --cached --check
git diff --cached --stat
git diff --cached
```

- 检查缓存区中的 SQL、配置、日志、截图、测试数据，禁止将密码/API Key/JWT Secret、`.env`、本地 `application-local.yml`、`target/`、`node_modules/`、`dist/` 等提交。
- 只允许提交本次任务的代码、测试、迁移与必要文档；**不得默认 `git add .` 一键暂存全部变更**。
- 若仓库已有用户暂存的文件，不得私自取消暂存或合并进当前任务提交。
- 后端与前端同时变化且属于**同一个垂直功能**时，可放在同一个提交；独立的基础设施/文档修改应拆分为清晰的原子提交。

### D. 提交与报告（必做）

只有验收通过且确认缓存区安全后，执行本地提交：

```bash
git commit -m "feat(seckill): implement synchronous order creation"
git status --short
git log -1 --oneline
```

- 若关键测试失败，**不要以完成态提交**；先修复或标记 BLOCKED，向用户报告。
- 创建提交后报告本次分支、Commit SHA、涉及文件、实际执行的测试及结果、剩余风险、是否还有未提交文件。
- **本地提交完成即停止；不自动 push/merge，不做下一功能。**

## 5. 提交消息：Conventional Commits（简化版）

格式：`<type>(<scope>): <imperative summary>`（scope 可省略；建议英文，简短且具体）。

| 类型 | 使用场景 | 示例 |
|---|---|---|
| `feat` | 新业务功能 | `feat(wallet): add token credit ledger` |
| `fix` | 修复错误 | `fix(order): prevent duplicate payment` |
| `test` | 新增/修正测试 | `test(seckill): cover concurrent stock update` |
| `refactor` | 不改变外部行为的重构 | `refactor(auth): simplify token validation` |
| `docs` | 文档调整 | `docs: describe local mysql setup` |
| `chore` | 构建/依赖/仓库维护 | `chore: add Maven wrapper` |
| `perf` | 有证据的性能优化 | `perf(catalog): cache product details` |

要求：描述**实际完成的事**，不要写 `update`、`fix bugs`、`WIP done` 等含混标题；没有压测数据不得在提交信息中宣称大幅性能提升。

## 6. `.gitignore` 与敏感数据保护

- 仓库根目录使用本交接包提供的 `.gitignore` 作为起点。
- 忽略运行输出：`target/`、`node_modules/`、`dist/`、`coverage/`、IDE 本地文件、日志等。
- 忽略真实密钥和本地覆盖配置：`.env`、`.env.*`（放行示例模板）、`application-local.yml`、`application-local.properties`。
- **必须提交**：`pom.xml`、`mvnw`、`.mvn/wrapper/` 必要文件、`pnpm-lock.yaml`、版本化 SQL 迁移、`docs/`、测试、`.gitignore`。
- 当机密已经进入暂存区/提交历史时，仅增加 `.gitignore` **不能消除已泄漏风险**。立即停止、报告、撤销/轮换泄漏的凭据，并经用户批准后处理历史。
- 提交前至少运行 `git diff --cached` 人工/AI 审核敏感字段；如果提供凭据扫描工具，运行后记录结果，但不得因扫描工具不存在而假装扫描通过。

## 7. AI 禁止自行执行的 Git 操作

未经**针对该操作的明确授权**，不得运行：

```text
git reset --hard
git clean -fd / git clean -fdx
git push --force / git push --force-with-lease
git rebase -i / git commit --amend
git filter-branch / git filter-repo
git branch -D / git checkout -- . / git restore .
git stash / git stash pop
git merge / git rebase
git push / git tag
```

这些操作有的会丢弃工作区内容，有的会改写历史，有的改变远程状态。**即使看似能快速修复冲突，也不得替用户做决定**。出现冲突、Detached HEAD、误提交、需要回滚时，先展示 `git status` 与可选安全方案，请用户批准后再执行相应操作。

## 8. PR / Review 和 CI 的最小要求

- 提供了 `.github/PULL_REQUEST_TEMPLATE.md`；它只是审查清单，不意味着必须为个人项目建立复杂的 GitHub 工作流。
- 每个 PR 至少注明：功能范围、验收标准、测试命令与结果、数据库/API 影响、风险以及关联 F 任务。
- **当仓库引入远程协作或需要自动质量门禁时**，再增加最小 GitHub Actions：Java21 执行 `./mvnw verify`；有前端时 Node 版本与 pnpm 锁文件固定，执行 `pnpm install --frozen-lockfile && pnpm lint && pnpm build`。
- 对依赖 MySQL/Redis/Kafka 的集成测试，使用 Testcontainers 或 CI 隔离容器；不能连接开发者的本地数据。
- 不为了构建一个展示项目而引入 SonarQube 服务、复杂制品仓库或多级部署流水线。

## 9. 对每轮 Qoder 的 Git 交付报告（必须）

```text
Git 仓库：已初始化/已有仓库；是否存在未提交的用户改动
分支：feature/F?-...
本次提交：<短 SHA> / 未提交（原因）
改动文件：...
暂存审查：git diff --cached --check；敏感文件确认
测试：命令 + PASS/FAIL/NOT RUN（实际结果）
工作区：git status --short 的最终状态
远程操作：无（除非用户本轮明确要求）
下一步：仅建议下一独立任务，停止等待用户确认
```

参考：[Git 官方文档](https://git-scm.com/docs)、[Conventional Commits 1.0.0](https://www.conventionalcommits.org/en/v1.0.0/)。
