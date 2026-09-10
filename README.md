<div align="center">

# OriginGuard

### 面向 AIGC 媒体真实性分析的可审计多模态 Agent

不是让单个模型直接猜真假，而是让 Agent 规划并调用受控取证能力，保留证据、来源、限制和完整执行轨迹，再由用户完成最终核验。

![Vue 3](https://img.shields.io/badge/Vue-3-5f817f?style=flat-square)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4-5f817f?style=flat-square)
![Python](https://img.shields.io/badge/Python-3.11-526274?style=flat-square)
![PostgreSQL](https://img.shields.io/badge/PostgreSQL-pgvector-526274?style=flat-square)
![License](https://img.shields.io/badge/License-MIT-273443?style=flat-square)

</div>

---

## 产品概览

OriginGuard 是一个对话式媒体调查工作台。普通问题由本地大模型直接回答；当用户询问具体图片是否由 AI 生成、是否被修改或来源是否可信时，系统会把问题与受约束的取证方案结合，异步执行 Agent 任务。

```text
用户问题与媒体
      ↓
意图识别 ── 普通问答 ──→ LLM 直接回答
      │
      └──── 媒体调查 ──→ Context → Plan → Validate → Act → Observe
                                              ↑              ↓
                                        Replan / Stop ← Observation
                                                            ↓
                              AIGC 检测 · 篡改定位 · C2PA · RAG
                                                            ↓
                                      可解释初步判断 → 人工核验
```

### 设计原则

- **模型不越权**：LLM 负责理解、规划和解释，不直接制造取证事实。
- **证据可追溯**：模型版本、权重指纹、输入资产、工具结果和引用来源均可回看。
- **结论可反驳**：正向信号、反向信号、缺失证据和能力限制同时展示。
- **失败可降级**：可选模型、Redis 或网络来源不可用时保留状态，不伪造结果。
- **最终由人确认**：Agent 只形成初步方向，不代替调查员作最终结论。

## 核心能力

### 对话式调查工作台

- 支持常识问答、上下文追问和媒体取证意图识别。
- 一次上传最多 8 张 JPEG、PNG 或 WebP 图片，单文件最大 25 MB。
- 自动创建分析记录并启动 Agent，无需用户填写案件字段。
- 多图结果通过左右切换集中展示，不把每张图片的相同结果纵向堆叠。
- 可删除不再需要的对话和已结束的 Agent 任务；运行中任务受保护。
- 登录状态、账号菜单、退出登录和管理入口具有明确权限边界。

### Agent Harness

- 本地多模态 LLM 根据用户目标、媒体类型和知识上下文生成计划。
- 声明式 Skill、Tool 白名单、RBAC、案件状态约束和 Step Budget。
- Observation 驱动动态重规划，并对异常计划提供确定性安全降级。
- RabbitMQ 异步执行，SSE 实时推送步骤、结果和失败状态。
- Checkpoint、幂等消费、死信队列、Redis 入队去重与用户级限流。
- 基于真实任务 Trace 的 Agent Evaluation，覆盖规划、工具选择、证据忠实度、鲁棒性和性能。

### 媒体取证

| 能力 | 当前实现 | 输出与定位 |
| --- | --- | --- |
| 媒体类型识别 | OpenAI CLIP | 区分摄影、动漫、数字插画、矢量卡通、3D、截图和平面设计；只参与路由 |
| AIGC 生成检测 | 通用多特征检测器 | 整图生成概率、质量门控、初步方向和语义注意力图 |
| 动漫/漫画检测 | AniXplore 可选适配 | 动漫域整图分数和像素响应；需要安装官方源码与权重 |
| 跨模型融合 | 通用模型 + 领域模型 | 独立读取原图；方向冲突时返回证据冲突，不强行投票 |
| 扩散重建复核 | AEROBLADE 可选适配 | 输出重建距离，只作为辅助信号，不包装成生成概率 |
| 局部篡改定位 | Mesorch 可选适配 | 掩码、热力图、原图叠加图、候选区域和响应统计 |
| 来源溯源 | C2PA Sidecar | 验证内容凭证、签名、文件绑定和声明的编辑动作 |
| 基础取证 | 内部工具 | 文件哈希、格式、尺寸、EXIF、完整性与多图感知相似度 |

> CLIP 类型分数和注意力图不是真假证据；Mesorch 响应分数也不是“发生篡改的确定概率”。未校准信号会在界面中明确标记，必须结合原图、来源凭证和人工核验。

### 多源 RAG

`RetrievalOrchestrator` 统一调度以下上下文：

1. 模型自身知识与当前对话；
2. 管理员发布的租户知识库；
3. OpenAlex 学术元数据与摘要；
4. 可选的 Tavily 通用网页搜索。

普通问答按需使用网页搜索；专业 AIGC 调查优先计算机视觉顶会、CCF-A、IEEE 等可核验学术来源。知识只影响调查计划、方法解释和限制说明，不直接修改检测概率。若检索内容影响结论，LLM 会总结具体影响并随引用展示。

### Web 安全调查

工作台可以对用户明确提交的 HTTP/HTTPS URL 进行防御性初筛：

- SSRF 目标校验与内网地址阻断；
- 公网 DNS、TLS 证书和主机名核验；
- URL 与 IOC 风险规则；
- 可选实时公开威胁线索检索。

系统不会执行目标页面内容，公开搜索结果也不会直接修改风险分。详见 [M6.2 受控 Web 安全调查](docs/product/m6.2-web-security-investigation.md)。

## 系统架构

```text
┌──────────────────────────── Vue 3 Web ────────────────────────────┐
│ 对话工作台 · 多图浏览 · Agent 过程 · 证据详情 · 人工核验 · 管理端 │
└────────────────────────────────┬──────────────────────────────────┘
                                 │ REST + SSE
┌────────────────────────────────▼──────────────────────────────────┐
│                    Spring Boot Application                       │
│ Identity/RBAC · Investigation · Agent Harness · Retrieval · Audit│
└───────────────┬────────────────┬─────────────────┬────────────────┘
                │                │                 │
        RabbitMQ Worker     PostgreSQL/pgvector   Redis
                │            MinIO Object Store   Cache/Rate Limit
                │
┌───────────────▼───────────────────────────────────────────────────┐
│ Python Model API                                                  │
│ Embedding · CLIP · AIGC Detector · AniXplore · AEROBLADE · Mesorch│
└───────────────────────────────┬───────────────────────────────────┘
                                │
                         C2PA Verification Sidecar
```

### 目录结构

```text
apps/web                 用户端、调查工作台与管理端
services/server          身份、业务流程、Agent Harness、RAG 与审计
services/model-api       Embedding、媒体理解和取证模型 API
services/c2pa-sidecar    C2PA 内容凭证验证适配器
packages                 API 与事件契约
knowledge-base           受控知识源
infra                    PostgreSQL、MinIO、Redis、RabbitMQ
docs                     产品说明、架构、安全与 ADR
scripts                  环境安装和本地统一启停脚本
```

## 快速开始

### 环境要求

- Windows 10/11
- Java 22、Maven 3.9+
- Python 3.11
- Node.js 22+、npm 10+
- Docker Desktop、Docker Compose v2

### 1. 配置环境

```powershell
Copy-Item .env.example .env
```

只在 `.env` 中填写真实密钥和本机路径。`.env` 已被 Git 忽略，任何密钥都不应写进 `.env.example`。

### 2. 启动本地服务

```powershell
.\scripts\start-local-stack.ps1
```

脚本会启动 PostgreSQL/pgvector、MinIO、Redis、RabbitMQ、Model API、C2PA Sidecar、本地 LLM、Spring Boot 后端和 Vue 前端，并把 PID 与日志保存在 `.runtime`。

| 服务 | 地址 |
| --- | --- |
| 产品首页 | <http://127.0.0.1:5173> |
| 用户工作台 | <http://127.0.0.1:5173/analyze> |
| 管理端 | <http://127.0.0.1:5173/admin> |
| 后端健康检查 | <http://127.0.0.1:18080/actuator/health> |
| 模型服务健康检查 | <http://127.0.0.1:8090/health> |
| C2PA 健康检查 | <http://127.0.0.1:8091/health> |
| RabbitMQ 管理台 | <http://127.0.0.1:15672> |

停止全部服务：

```powershell
.\scripts\stop-local-stack.ps1
```

### 演示账号

| 入口 | 用户名 | 默认密码 | 权限 |
| --- | --- | --- | --- |
| `/login` | `investigator` | `OriginGuard@123` | 上传、分析、查看记录和人工核验 |
| `/admin/login` | `admin` | `OriginGuard@123` | 系统配置、知识管理、能力目录与审计 |

默认密码可通过 `ORIGINGUARD_DEMO_PASSWORD` 覆盖，禁止用于生产环境。

## 可选模型安装

模型源码、权重和缓存只写入 `.runtime`，不会提交到 Git。

```powershell
# 动漫/漫画专用检测
.\scripts\setup-anixplore.ps1 -CheckpointPath D:\path\to\official-checkpoint.pth

# 扩散重建复核
.\scripts\setup-aeroblade.ps1

# 局部篡改定位
.\scripts\setup-mesorch.ps1 -CheckpointPath D:\path\to\mesorch-98.pth

# C2PA 官方验证工具
.\scripts\setup-c2pa.ps1
```

可选模型未安装时不会阻止系统启动：对应能力会记录为 `UNAVAILABLE`，Agent 继续处理其他证据。Mesorch 的完整配置和输出约定见 [M6.3 图像篡改定位](docs/product/m6.3-image-manipulation-localization.md)。

## 任务性能与可观测性

每个完成的 Agent 任务都会保存：

| 指标 | 含义 |
| --- | --- |
| `queueWaitMillis` | 创建任务到消费者开始执行的等待时间 |
| `executionDurationMillis` | Agent 在消费者中的实际执行时间 |
| `endToEndMillis` | 创建任务到完成的总时间 |
| `cacheHitCount` | 媒体理解、检测或定位结果的缓存命中次数 |

RabbitMQ 主要降低 HTTP 请求阻塞并提升并发承载能力，不会缩短一次冷模型推理。相同媒体与相同模型配置的热缓存任务才会显著降低耗时。

## 安全边界

- JWT Access Token 与 HttpOnly Refresh Cookie 轮换。
- 普通用户和管理员入口、权限与数据范围分离。
- 上传文件同时执行浏览器 SHA-256、服务端魔数/MIME、解码和像素上限校验。
- Agent 只能调用注册的受控工具，不能执行任意 Shell、SQL 或外部 URL。
- 媒体、EXIF、模型输出、网页和知识文档均按不可信输入处理。
- 案件状态更新使用乐观锁；关键操作写入追加式审计日志。
- `.env`、密钥、模型权重、上传媒体、日志和运行缓存不会提交到 Git。

## 测试与构建

```powershell
# 前端
cd apps/web
npm test
npm run build

# Java 后端（集成测试需要可用的 Docker）
cd ../../services/server
mvn test

# Python Model API
cd ../model-api
python -m pytest
python -m ruff check src tests
python -m mypy --config-file pyproject.toml src
```

## 文档

- [Agent 行为评测基线](docs/product/m6.1-agent-evaluation.md)
- [受控 Web 安全调查](docs/product/m6.2-web-security-investigation.md)
- [图像篡改定位](docs/product/m6.3-image-manipulation-localization.md)
- [可插拔取证模型路由](docs/product/m5.3-forensic-model-routing.md)
- [用户负责结果确认](docs/adr/ADR-007-investigator-owned-result-confirmation.md)

## 路线图

- 使用固定业务验证集校准 AIGC 分类器、AniXplore 与 Mesorch 阈值。
- 按 IMDL-BenCo 协议评估图像级和像素级篡改定位性能。
- 接入数字绘画、矢量卡通和 3D 渲染的跨域专用检测能力。
- 扩展视频抽帧、关键帧检测和时序一致性分析。
- 提供可导出的结构化真实性分析报告。
- 增加工具超时、消息重复投递、提示注入和 Checkpoint 恢复评测。

## 使用声明

OriginGuard 输出的是辅助调查信息，不是法律、新闻核验或平台治理中的自动最终裁决。模型分数、热力图、网络材料和来源凭证都应由具备相应背景的人员结合原始媒体核验。

## License

[MIT](LICENSE)
