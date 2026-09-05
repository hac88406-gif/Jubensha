# 工程化改造变更日志（上传 GitHub 前整理）

> **改造时间**：2026-09-04
> **改造目标**：将开发态仓库整理为适合面试展示 / 公开上传的工程化结构。
> **核心原则**：**零源码变更（不修改任何 .java / .py / .lua / .yml / .xml / .sql 文件）**，
> 只新增工程化文件（.gitignore、README、docs），临时文件仅通过 .gitignore 做「软忽略」。

---

## 🔒 一、本次改造明确不触及的范围（Red Lines）

| 类别 | 包含内容 | 是否修改 |
|---|---|---|
| 🔴 Java 源代码 | 所有模块下的 `.java` 文件（共 ~60 个） | ❌ 未修改 |
| 🔴 Python 源代码 | `python-agent/` 下所有 `.py` 文件 | ❌ 未修改 |
| 🔴 Lua 脚本 | `order-service/src/main/resources/lua/*.lua`（2 个） | ❌ 未修改 |
| 🔴 Maven 配置 | 所有 `pom.xml`（父 POM + 6 个子模块，共 7 个） | ❌ 未修改 |
| 🔴 Spring 配置 | 所有 `application.yml`（6 个服务） | ❌ 未修改 |
| 🔴 数据库脚本 | `init-scripts/01-init.sql` | ❌ 未修改 |
| 🔴 Docker 编排 | `docker-compose.yml` | ❌ 未修改（保留原位） |
| 🔴 依赖清单 | `python-agent/requirements.txt` | ❌ 未修改 |
| 🔴 已有脚本 | `benchmark_stock.js` / `make_bench_session.js` / `smoke_mainchain.js` / `_start_services.ps1` | ❌ 未修改（保留原位，仅在 docs 中说明） |

---

## ✅ 二、本次新增的文件清单（共 8 个）

### 1. 新增：`.gitignore`（仓库根目录）
- **原因**：上传前缺少 Git 忽略规则，`target/` 构建产物、`.log` 日志、临时测试文件会全部进仓库。
- **作用**：忽略以下类别文件（均不删除本地文件，仅 Git 层面忽略）：
  - Maven：`target/`、`*.class`、`*.jar`、`_mvn_install.log*`
  - Python：`__pycache__/`、`*.pyc`、`.venv/`
  - IDE：`.idea/`、`.vscode/`、`*.iml`
  - 日志目录：`_logs/`
  - 根目录散落日志：`*.log`、`*.err.log`、`_svc_*.log*`、`order_boot_prod*.log`
  - 冒烟测试产物：`smoke_*.log`、`smoke_*.txt`
  - 压测产物：`interview_bench_*.txt`
  - 本地临时草稿：`_deliverables_*.md`（内容已迁移至 `docs/`）、`blog_post.md`

### 2. 新增：`README.md`（仓库根目录，面试门面）
- **原因**：GitHub 仓库首页默认展示，缺少 README 面试官无法快速了解项目。
- **内容结构**：
  - 🎯 项目亮点 × 4（Redis+Lua 零超卖 / TTL+DLX 最终一致性 / 三环鉴权 / AI 熔断兜底），每条都带量化数据
  - 🏗️ 系统架构图（文字版 ASCII + 指向 `interview_shots/architecture_renderer.html`）
  - 📁 模块说明 × 8（6 Java + Python Agent + init-scripts + interview_shots，端口/职责/关键能力）
  - 🛠️ 技术栈（后端 Java17 / 中间件 / Python Agent）
  - 🚀 快速启动 6 步走（Docker → 初始化 SQL → Nacos 配置 → Maven 构建+启动 → Python Agent → 冒烟测试）
  - 📊 压测对比（MySQL 乐观锁 vs Redis+Lua）
  - 📚 文档索引（指向 docs/ 下 4 个文档）
- **信息来源**：全部从现有代码结构、项目记忆、docs 内素材真实提取，不做任何虚构。

### 3. 新增：`docs/PROJECT_NARRATIVE.md`
- **原因**：原 `_deliverables_project_narrative.md` 为本地草稿，`.gitignore` 中已忽略；需要在 `docs/` 下保留归档版本供面试翻阅。
- **内容**：与 `_deliverables_project_narrative.md` 完全一致（逐字复制，零改动），结构为「问题 → 方案 → 结果 × 3 个核心问题 + 踩坑教训」。

### 4. 新增：`docs/RESUME_HIGHLIGHTS.md`
- **原因**：同 PROJECT_NARRATIVE，原草稿 `.gitignore` 忽略，`docs/` 下归档版本供简历对应查阅。
- **内容**：与 `_deliverables_resume_highlights.md` 完全一致（逐字复制，零改动），结构为「亮点 1-4 各含 Problem/Solution/Result + 一句话版本」。

### 5. 新增：`docs/INTERVIEW_ASSETS.md`
- **原因**：`interview_shots/` 目录下有 15 个文件（架构图 HTML + Nacos/MQ/代码/Knife4j 截图 PNG + 一个辅助脚本），无说明则面试官不知道是什么。
- **内容**：按「架构图 / Nacos / MQ / 核心代码 / Knife4j」五大类列出每个文件的用途、对应截图的关键信息、面试话术参考，最后给了演示顺序建议。

### 6. 新增：`scripts/README.md`
- **原因**：根目录下 3 个 `.js` 脚本（benchmark_stock / make_bench_session / smoke_mainchain）+ interview_shots 下 1 个 py 脚本，无说明会被面试官视为散乱文件。
- **内容**：按「压测脚本 / 冒烟测试脚本 / 代码截图辅助脚本」三大类分别说明用途、运行命令、前置条件、临时产物、FAQ。脚本本身为了零改动风险**保留原位**未移动。

### 7. 新增：`CHANGELOG_工程化改造.md`（本文件）
- **原因**：体现做事可追溯。让面试官看到「上传前做过工程化整理，且明确不碰源代码」，增强信任感。
- **内容**：本文件。明确列出红线、新增文件清单、未执行项与原因、安全声明。

---

## ⚠️ 三、本次「故意未执行」的改造项（与原因）

| 未执行项 | 原因（均为「安全优先」原则，避免改错） | 后续可选优化方向 |
|---|---|---|
| 未移动 java 子模块到 `java-modules/` 目录 | Maven 多模块项目中，子模块必须与**父 POM 同级**才能被 `<modules>` 正确识别，强行移动会导致构建失败 | 保持现状即可（这是标准 Maven 多模块布局，面试官反而觉得规范） |
| 未重构 `reservation-common/` 分包（根包下 9 个类未拆 exception/result/util） | 修改 package 会级联修改所有子模块中对这些类的 `import` 语句，有改错/漏改风险 | 后续可新建分支统一修改，配合 IDE 重构功能保证引用正确 |
| 未修改 `_start_services.ps1` 中的硬编码绝对路径 | 怕改坏后本地启动失败，且脚本主要作者自用 | 下版本改用 `Split-Path -Parent $MyInvocation.MyCommand.Path` 获取脚本目录 |
| 未移动 `docker-compose.yml` 和 `_start_services.ps1` 到 `deploy/` 目录 | 怕已有文档/脚本对原路径有引用；且面试官对根目录放 docker-compose 并不反感 | 下版本一起迁移，并同步更新 README 中路径 |
| 未移动根目录下的 3 个 `.js` 脚本到 `scripts/` 目录 | 脚本内部可能存在对相对路径的依赖，移动会导致执行失败 | 已通过 `scripts/README.md` 做分类说明，零风险等效 |
| 未给 `python-agent/` 单独写 README | Python 目录已有 `requirements.txt`，启动步骤在根 README「第五步」已完整说明 | 后续可补，含 `.env.example` 模板 |
| 未将 `application.yml` 中的演示密码脱敏 / 抽模板 | 服务内部配置不动代码原则；根 README 已加「安全说明」标注为演示环境配置、生产请修改 | 后续提供 `application.example.yml` 模板 |

---

## 🛡️ 四、安全声明

1. **未删除任何本地文件**：所有日志、临时测试产物、原 `_deliverables_*.md` 草稿仍保存在用户本地磁盘，仅通过 `.gitignore` 阻止它们进入 Git 版本库。回滚方式：删除 `.gitignore` 对应行即可。
2. **未改写任何业务逻辑**：所有新增文件均为文档 / 配置 / 忽略规则，零代码执行路径变更。
3. **未提交任何敏感信息脱敏工作**：docker-compose.yml 中 `root:123456` 等演示账号密码保留，但根 README 已在「快速启动」表格下加⚠️ 红色安全说明，明确标注为**本地演示环境**，生产请修改。
4. **一键回滚方案**：如对本次改造不满意，删除以下 **8 个文件 + 2 个目录** 即可完全恢复原样：
   ```
   删除文件：
     .gitignore
     README.md
     CHANGELOG_工程化改造.md
   删除目录（含内部文件）：
     docs/
     scripts/
   ```
