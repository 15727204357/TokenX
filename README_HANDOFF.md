# TokenX / Qoder 开发交接包

本包提供**规划和代理规则**，不包含应用源码；所有业务均未被宣称实现或通过测试。

## 如何使用

1. 在 TokenX 项目目录解压本包（含 `AGENTS.md`、`docs/`、`.gitignore`、`.gitattributes`、`.github/`、`QODER_START_PROMPT.md`）；**保持这些文件在仓库根目录的相对路径不变**。如果目录已经有 `.git/`，不要再次初始化或覆盖历史。
2. 使用 Qoder 打开仓库根目录。Qoder 官方规则机制能读取根目录 `AGENTS.md`；Git 初始化、分支、提交与禁止操作详见 `docs/GIT_WORKFLOW.md`。
3. 在 Qoder 聊天中粘贴 `QODER_START_PROMPT.md` 的内容，只让它做 `F0-1`。
4. 验收 F0-1（真正能启动、测试通过且本地 Git 提交可追溯）后，再依次实施 `F0-2`、`F1` ...，一次只交给 Qoder 一个任务。
5. 长期维护 `docs/DEVELOPMENT_PLAN.md`：经过验证完成后才能打勾；不要一开始让 AI 自动实现所有模块。

## 目录

```text
AGENTS.md                         Qoder 必须遵守的总规则
docs/PROJECT_SPEC.md              业务范围、技术选型、API 与初始数据模型
docs/ENGINEERING_STANDARDS.md     TDD、Google Java Style、设计模式、审查门槛
docs/DEVELOPMENT_PLAN.md          F0-F13 阶段与逐任务验收
docs/GIT_WORKFLOW.md              Git 版本控制与 AI 安全操作清单
docs/REFERENCES.md                黑马点评、TokenMarket、官方规范来源
QODER_START_PROMPT.md             首轮给 Qoder 的任务文本
README_HANDOFF.md                 本文件
.gitignore                        本地缓存、产物、密钥忽略规则
.gitattributes                    跨平台换行符与二进制文件标记
.github/PULL_REQUEST_TEMPLATE.md  最小审查与测试清单
```

## 额外说明

- `PROJECT_SPEC.md` 中的数据库表/接口为**设计草案**，开发每个功能时才实现相应部分，不需要一次性搭齐。
- 技术版本固定的是已选基础版本，其他第三方库由 Maven BOM/前端锁文件管理。
- MySQL9.7 / Flyway 的适配属于 F0-2 的需验证项目，不要默认已兼容。
- 黑马点评的旧技术栈不能直接照搬，且源代码复用前必须核对许可证。

## Git 的执行约定

- Qoder 每次任务开始要检查 Git 工作区和分支，完成后只对测试通过的当前功能做本地原子提交。
- **不自动推送到 GitHub，不自动合并到 `main`，也不自动进行下一任务。** 用户可以在本地审查、决定是否推送或合并。
- 如果已有未提交用户文件、Git 作者信息缺失或发生冲突，Qoder 必须暂停危险操作并说明，不能擅自清理现场。
