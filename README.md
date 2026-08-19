# OriginGuard

> 当前状态：M5.2 已把 ICLR 2025 AIDE 官方预训练模型接入 Agent Harness。`Qwen3-VL-4B-Instruct` 负责规划受控 Skill，AIDE 负责输出图片 AIGC 检测分数；模型结果保存为可追溯 Observation，仍由调查员和审核员作出最终决定。模型与缓存均位于项目 `.runtime`，不会提交到 Git。

首次使用 AIDE（下载约 3.59 GB 官方权重）运行：

```powershell
.\scripts\setup-aide.ps1
```

首次使用 CLIP 媒体类型识别（下载约 338 MB 官方 ViT-B/32 权重）运行：

```powershell
.\scripts\setup-clip.ps1
```

本地 Qwen3-VL Planner 启动：

```powershell
.\scripts\start-local-stack.ps1
```

该脚本会启动 PostgreSQL、MinIO、BGE/AIDE Model API、Qwen3-VL、Spring Boot 和 Vue，并把进程信息与日志写入 `.runtime`。为避免 8GB 显存与 Qwen 同时驻留时溢出，统一脚本默认让 AIDE 按需在 CPU 加载；可在单独测试时设置 `$env:AIDE_DEVICE='cuda'`。结束开发时运行：

```powershell
.\scripts\stop-local-stack.ps1
```

Qwen 服务启动后可运行 `.\scripts\check-qwen-vl.ps1` 做独立健康检查。8GB 显存默认使用 4096 上下文、单并发、896 像素输入边长；如显存不足，可用 `.\scripts\start-qwen-vl.ps1 -ContextSize 3072 -GpuLayers 36` 降低占用。

详见 [M5.1 本地 Qwen3-VL Planner](docs/product/m5.1-local-qwen-planner.md)和 [M5.2 AIDE 检测 Skill](docs/product/m5.2-aide-detector.md)。

> M4.3 已接入本地真实 Embedding。`BAAI/bge-small-zh-v1.5` 由 Python Model API 生成 512 维向量，Java 后端通过版本化 Provider 向量表完成 pgvector 混合检索。

真实 Embedding 本地启动：

```powershell
docker compose up -d postgres
.\scripts\start-model-api.ps1

$env:EMBEDDING_PROVIDER='bge-small-zh-v1.5'
$env:MODEL_API_BASE_URL='http://127.0.0.1:8090'
cd services/server
.\mvnw.cmd spring-boot:run
```

详见 [M4.3 本地真实 Embedding](docs/product/m4.3-real-embedding.md)。

OriginGuard 是一个面向内容审核与数字取证场景的 AIGC 内容真实性检测、篡改分析和来源溯源平台。

> 当前状态：M4.2 已完成；RAG 具备独立检索调试、Top-K 分数解释、Recall@K/MRR 评测基线和租户/草稿/Citation 安全完整性检查。

## 当前包含

- Vue 3 + TypeScript + Vite 前端骨架
- Spring Boot 模块化单体骨架
- FastAPI 模型 API 与 RabbitMQ Worker 骨架
- PostgreSQL/pgvector、Redis、RabbitMQ、MinIO 的 Compose 定义
- OpenAPI 与事件 JSON Schema 的初始契约
- 架构决策记录（ADR）和基础 CI
- Spring Security、JWT Access Token、HttpOnly Refresh Cookie 轮换
- 调查员、审核员、管理员三角色权限与租户上下文
- JPEG/PNG multipart 上传、MinIO 对象存储、租户授权预览和服务端 SHA-256 复核
- MIME/魔数校验、图片解码、40MP 安全限制、尺寸、EXIF 摘要和 64 位感知 dHash
- 调查案件创建、列表、详情、编辑和媒体关联
- `DRAFT → READY → INVESTIGATING → WAITING_REVIEW` 状态推进
- 管理员分派调查员和独立审核员，且不拥有审核决定权限
- 调查员针对案件媒体追加人工观察证据
- 审核任务自动创建，审核员通过或驳回后进入 `CONFIRMED` / `REJECTED`
- 案件乐观锁、租户范围查询和追加式审计时间线
- `AgentTask → Context Builder → Fake/Local Qwen Planner → Plan Validator → Skill → Real Media Tool → Observation → Checkpoint → Trace` 完整链路
- 本地 Qwen3-VL 读取压缩后的案件首图与租户 RAG 上下文，输出 JSON Schema 约束的 Skill 计划
- ICLR 2025 AIDE 官方 GenImage 检查点、频域/语义混合推理 API 与 `detect_aigc_with_aide` Skill
- AIDE 模型版本、权重 SHA-256、运行设备、阈值、耗时和每图概率的 Observation 留痕
- OpenAI CLIP ViT-B/32 在 LLM 规划前区分摄影、插画卡通、3D 渲染、界面截图和平面设计；类型上下文只参与 Skill 路由、AIDE 适用性判断和结果解释，不参与 AIGC 真假投票
- RAG 知识扩展按需检索 OpenAlex 论文元数据与摘要，不下载 PDF；候选内容先生成外部知识草稿，人工审核发布后才建立 Embedding
- RAG 检索按来源优先级重排：租户知识优先级 100，外部学术知识优先级 40，相关性仍占主要权重
- 版本化 Skill、Tool 白名单、权限/案件状态策略和 Step Budget
- Agent 任务列表、结构化结论、Observation、Checkpoint 与逐步 Trace 页面
- Markdown/纯文本知识草稿、发布、确定性切片、64 维本地向量与 HNSW/GIN 索引
- `retrieve_forensic_guidance` Skill、租户/发布版本过滤、混合召回和可追溯引用
- 可替换 `EmbeddingProvider`、RAG 调试检索页面、评测用例与 Recall@K/MRR 基线

## 仓库结构

```text
apps/web                    Vue 前端
services/server             Java 业务与 Agent 中枢
services/model-api          Python 同步模型 API
services/c2pa-sidecar       C2PA 受控适配器占位
workers/model-worker        Python 异步模型 Worker
packages/api-contract       OpenAPI 契约
packages/event-schema       异步事件契约
knowledge-base              四类受控知识源
tests                       跨服务测试与评测入口
infra                       基础设施配置
docs                        架构、产品、安全与 ADR
scripts                     Windows/Unix 开发脚本
```

## 环境要求

- Java 22（匹配当前本机 JDK；生产发布前可再评估切回 Java 21 LTS）
- Maven 3.9+
- Python 3.11
- Node.js 22+
- npm 10+（后续可切换 pnpm）
- Docker Desktop / Docker Engine + Compose v2

## 安全约定

- 不提交 `.env`、密钥、模型权重和运行数据。
- 上传内容、OCR、EXIF 和检索文档一律视为不可信证据。
- Agent 只能通过受控工具调用应用服务，不能执行任意 Shell、SQL 或 URL 请求。
- 当前示例密码只适用于本地开发，启动前复制 `.env.example` 为 `.env` 并修改。

## 本地基础设施

```powershell
Copy-Item .env.example .env
docker compose up -d postgres redis rabbitmq minio
./scripts/dev.ps1
```

当前开发机已完成前端、Java、Python 依赖安装与构建测试，并验证 PostgreSQL/pgvector、Redis、RabbitMQ、MinIO 健康运行。详细结果见 [M0 清单](docs/product/m0-checklist.md)。

## 本地登录

启动 PostgreSQL 和后端：

```powershell
docker compose up -d postgres
cd services/server
.\mvnw.cmd spring-boot:run
```

另开终端启动前端：

```powershell
cd apps/web
npm run dev
```

本地租户代码为 `demo`，开发账号为 `investigator`、`reviewer`、`admin`，初始密码均为 `OriginGuard@123`。密码可通过 `ORIGINGUARD_DEMO_PASSWORD` 覆盖；这些账号不得用于生产环境。

管理员默认不拥有 `review:approve` 或 `report:finalize`，需要参与复核的人必须单独获得审核员角色，并继续受禁止自审规则约束。实现说明见 [身份与权限基础](docs/product/identity-rbac.md)。

## M1.2 使用流程

使用 `investigator` 登录后：

```text
媒体资产 → 选择本地图片 → 浏览器计算 SHA-256 → 登记媒体记录
调查案件 → 创建案件并关联媒体 → 标记 READY → 开始调查
```

随后使用三个职责分离账号完成：

```text
admin → 为案件分派调查员与审核员
investigator → 针对关联媒体追加人工证据 → 提交人工审核
reviewer → 查看证据与审核任务 → 通过或填写理由驳回
```

新上传的 JPEG/PNG 会存入 MinIO，并可在媒体列表和案件详情中授权预览。旧版 `REGISTERED` 记录没有对应文件内容，仍会显示为不可预览。当前单文件上限 25 MB；C2PA、恶意文件扫描、视频和分片上传仍属于后续阶段。

管理员可以查看和分派案件，但不能创建、调查或作出审核决定；审核员只能决定分派给自己的任务，并禁止审核自己创建或调查的案件。详细边界见 [M1.2 实现说明](docs/product/m1.2-evidence-review-workflow.md)。

## M3.2 确定性媒体 Skill 流水线

调查员先上传新的 JPEG/PNG 媒体并关联案件，将已分派给自己的案件推进到 `INVESTIGATING`，然后点击“运行 Agent”。任务会同步执行确定性流程并跳转到 Trace 页面。具有 `agent:trace:read` 权限的用户也可从“Agent 任务”导航查看租户内任务。

Fake Planner 仍不调用 LLM，但会固定编排文件完整性、图片元数据、感知相似度、AIDE 生成检测和 RAG 五个版本化 Skill。对应 Tool 都会读取 MinIO 中的真实字节；AIDE 的概率会进入阶段性结论，但不会自动成为人工审核决定。实现说明见 [M3.2 多确定性 Skill](docs/product/m3.2-deterministic-media-skills.md)与 [M5.2 AIDE 检测 Skill](docs/product/m5.2-aide-detector.md)。

`retrieve_forensic_guidance` 从当前租户已发布知识中进行 PostgreSQL 全文检索与 pgvector 混合召回。结果独立保存为 Knowledge Retrieval/Citation，供 Agent/LLM 解释媒体 Observation 和制定调查方案，不能直接纳入案件证据。实现说明见 [M4.1 RAG 第一版](docs/product/m4.1-rag-foundation.md)。

## M3.3 Observation、正式证据与审核引用

Agent 运行后，文件完整性、图片元数据、感知相似度和 AIDE 检测 Observation 会作为候选调查材料出现在案件页。分派的调查员可逐条确认纳入正式案件证据；系统保存原 Observation ID，禁止重复纳入。案件进入审核后，审核员必须勾选至少一条正式证据才能提交决定，引用关系随审核任务永久保存。实现说明见 [M3.3 Agent 证据审核闭环](docs/product/m3.3-agent-evidence-review-loop.md)。
