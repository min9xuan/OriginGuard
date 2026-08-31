# OriginGuard

面向 AIGC 媒体真实性分析的多模态 Agent 系统。

OriginGuard 不让单个大模型直接猜测图片真假，而是通过 Agent Harness 读取媒体上下文、制定调查计划、调用受控取证能力、记录 Observation，并根据新结果动态调整后续步骤。系统最终给出可解释的初步判断，由用户完成人工核验。

## 当前能力

### 用户检测流程

- 产品展示首页与独立登录入口
- JPEG、PNG、WebP 图片上传，单文件最大 25 MB；一次可选择最多 8 张进行联合分析
- 文件魔数、MIME、图片解码、大小与像素上限校验
- 浏览器 SHA-256 计算与服务端内容指纹复核
- 自动创建内部分析记录并启动 Agent，无需用户手工配置案件字段
- 检测记录、原图预览、分析过程与人工核验结果查询
- 用户可选择“AI 生成”“非 AI 生成”或“暂时无法判断”，并决定是否引用 Agent 的结论与理由
- 顶部导航显示当前登录状态与账号，支持退出后切换用户
- 支持删除单个对话，以及删除本人已结束的 Agent 分析任务；运行中的任务会被保护

### Agent Harness

```text
Context → Plan → Validate → Act → Observe → Replan / Stop → Synthesize
```

- 本地多模态 LLM 根据图片类型、调查目标和知识上下文生成受约束计划
- 声明式 Skill、Tool 白名单、权限策略、案件状态约束和 Step Budget
- Observation 驱动的动态重规划，并为异常决策提供安全降级
- 每次模型选择、工具调用、Observation、Checkpoint 和计划调整均持久化留痕
- 执行抽屉实时展示当前阶段、模型回复摘要和可审计事件
- Agent 只形成初步判断，不代替用户的最终核验

### 媒体取证能力

- 媒体类型识别：区分摄影、动漫/漫画、数字绘画、矢量卡通、三维渲染、界面截图和平面设计
- 生成内容鉴别：输出 AI 生成概率、阈值判断、质量评估和语义注意力图
- 动漫/漫画专用鉴别：仅在内容被路由为动漫或漫画时使用 AniXplore，输出整图分数和像素级疑似生成区域
- 跨模型融合：动漫专用模型与通用模型独立读取原图并交叉复核；方向冲突时返回“证据冲突”，不会强行选择一方
- 扩散重建复核：对疑似扩散模型图像计算重建距离，作为独立辅助观察
- 文件完整性检查：核对登记哈希、存储内容和文件大小
- 图片元数据提取：记录格式、尺寸和可用 EXIF 摘要
- 感知相似度：在存在多张可比图片时进行辅助比较
- 多图联合分析：逐图保留模型概率与凭证状态，并汇总相同/近重复关系和跨图结论
- C2PA 来源溯源：验证内容凭证、文件绑定、声明工具与编辑动作；无凭证只记为“未发现”，不作为真假证据
- 结果解释：由本地多模态 LLM 综合模型信号、媒体事实与能力限制，生成中文说明

媒体类型只用于选择取证能力和解释模型适用边界，不直接参与 AIGC 真伪投票；注意力图也不等同于精确生成区域或篡改区域。

### 可插拔模型路由

```text
ForensicModelAdapter
→ ForensicModelRegistry
→ ModelRoute
→ Unified Forensic Observation
```

- 每个模型声明能力代码、版本、用途、媒体类型、标准输出和优先级
- Agent 根据媒体类型选择当前优先级最高的可用模型
- 专用模型缺失时明确标记“降级执行”，不会把通用结果包装成专用结论
- 统一 Observation 保存概率、判断、置信度、质量、可视化、限制和路由原因
- 新模型实现 `ForensicModelAdapter` 并注册为 Spring Bean 后即可参与路由
- `GET /api/v1/agent-tasks/model-capabilities` 可查询当前模型能力目录

当前已经具备通用图像生成内容鉴别能力；动漫/漫画专用模型和扩散重建复核器已完成可执行适配，安装权重与可选依赖后会自动参与路由。数字绘画与矢量卡通不会再误路由到 AniXplore：当前会明确降级到通用模型并暴露跨域专用模型缺口。C2PA 已通过独立 sidecar 接入，三维渲染鉴别与局部篡改定位仍保留插件接口。

实现说明见 [M5.3 可插拔取证模型注册与动态路由](docs/product/m5.3-forensic-model-routing.md)。

### 统一检索与 RAG 增强

- `RetrievalOrchestrator` 统一调度模型知识、会话上下文、已发布知识库和实时网络来源
- Markdown 和纯文本知识文档管理、草稿管理与版本发布
- 中文 Embedding、pgvector 向量索引和 PostgreSQL 全文检索
- 语义分数、关键词分数、来源优先级共同参与混合召回
- 用户自建知识优先于外部学术候选知识
- 普通问答按需使用通用网页搜索；专业 AIGC 检测强制使用学术检索策略
- 专业检索优先已发布知识、维护的计算机视觉/AI 顶级会议与期刊、IEEE 期刊，再考虑其他可核验学术来源
- 系统不会依据期刊名称猜测分区；未核验的 IEEE/其他期刊会明确标记仍需按年份和分区体系复核
- 默认实时学术源为 OpenAlex；配置 Tavily 后可为普通问题扩展通用网页搜索
- 按需检索权威论文元数据与摘要，不自动下载论文 PDF
- 外部知识先保存为草稿，确认发布后才进入 Agent 检索范围
- Citation 保存文档版本、知识片段、排序和各项召回分数
- LLM 会总结哪些检索来源实际影响了回答、方案选择或限制说明，并随引用展示
- 知识库与网络材料只为规划、方法说明和结果解释提供参考，不直接改变模型概率或构成图片真伪证据

### 对话式分析工作台

- 常识性问题由本地大模型直接回答，不创建案件或 Agent 任务
- 涉及具体图片真实性、生成来源或媒体取证的问题会自动路由到 Agent
- Agent 把用户问题与受控取证方案合并，执行媒体理解、模型检测、证据整理与结果解释
- 回答保留正向信号、反向信号、缺失证据和能力限制，并提供完整任务过程入口
- 对话可继续追问，并复用本轮上下文和最近一次上传的媒体
- 用户可以删除不再需要的单个对话，避免会话与来源材料无限累积

相关配置见 `.env.example` 中的 `ASSISTANT_LLM_PROVIDER`、`WEB_SEARCH_PROVIDER` 与可选的 `TAVILY_API_KEY`。

## 技术栈

- Web：Vue 3、TypeScript、Vite、Element Plus
- 业务与 Agent 中枢：Java 22、Spring Boot、Spring Security、Flyway、Maven
- 模型服务：Python 3.11、FastAPI、PyTorch、Transformers
- 数据与检索：PostgreSQL、pgvector
- 对象存储：MinIO
- 基础设施：Docker Compose、RabbitMQ、Redis
- 异步执行：RabbitMQ 持久化 Agent 任务队列、幂等消费与死信队列
- 实时状态：SSE 推送任务步骤、完成和失败事件
- 性能保护：Redis 模型结果缓存、入队去重与用户级固定窗口限流

Agent 创建和 HTTP 请求已经与实际取证执行解耦：接口返回 `202 Accepted` 后由 RabbitMQ
消费者运行任务；前端通过 SSE 持续接收进度。Redis 不保存案件主数据，只用于可失效的缓存、
短期入队去重和每用户限流；Redis 暂时不可用时，取证能力会降级运行而不是丢失业务数据。

## 系统结构

```text
apps/web                    用户端、检测记录与隐藏管理端
services/server             身份安全、业务流程、Agent Harness 与 RAG
services/model-api          Embedding、媒体分类和取证模型 API
services/c2pa-sidecar       C2PA 内容凭证验证与标准化适配器
workers/model-worker        独立模型 Worker 扩展目录
packages/api-contract       OpenAPI 契约
packages/event-schema       异步事件契约
knowledge-base              受控知识源
infra                       基础设施配置
docs                        架构、产品、安全和 ADR
scripts                     本地环境与统一启动脚本
tests                       跨服务测试与评测入口
```

## 快速启动

### 环境要求

- Windows 10/11
- Java 22
- Maven 3.9+
- Python 3.11
- Node.js 22+
- npm 10+
- Docker Desktop 与 Docker Compose v2
- 本地模型资源已放入项目 `.runtime` 对应目录

### 启动全部服务

在项目根目录运行：

```powershell
.\scripts\start-local-stack.ps1
```

统一脚本会启动：

- PostgreSQL / pgvector
- MinIO
- Redis
- RabbitMQ
- Python Model API
- C2PA 验证 Sidecar（未安装 c2patool 时以未配置状态安全运行）
- 本地多模态 LLM API
- Spring Boot 后端
- Vue 前端

启动完成后访问：

- 产品首页：<http://127.0.0.1:5173>
- 用户分析入口：<http://127.0.0.1:5173/analyze>
- 管理端入口：<http://127.0.0.1:5173/admin>
- 后端健康检查：<http://127.0.0.1:18080/actuator/health>
- 模型服务健康检查：<http://127.0.0.1:8090/health>
- C2PA 服务健康检查：<http://127.0.0.1:8091/health>
- RabbitMQ 管理台：<http://127.0.0.1:15672>

### Agent 性能指标

每个完成任务会在结论的 `performance` 字段和最终执行事件中记录：

- `queueWaitMillis`：任务从创建到消费者开始执行的等待时间
- `executionDurationMillis`：消费者内部实际执行 Agent 的时间
- `endToEndMillis`：创建任务到结果完成的总时间
- `cacheHitCount`：本次任务复用模型检测缓存的次数

异步化主要缩短用户请求的阻塞时间并提高并发承载力，不会凭空缩短一次冷模型推理；同一媒体、
同一模型能力再次分析时，Redis 缓存才会显著减少重复模型调用。实际提升应以相同媒体的冷、热两次
任务以及历史同步任务的上述指标为准。

脚本以后台进程方式运行服务，并将 PID 与日志保存在 `.runtime`。启动结束后当前终端可以继续输入命令。

首次启用 C2PA 前安装官方验证工具：

```powershell
.\scripts\setup-c2pa.ps1
```

二进制、上传媒体和验证临时文件均位于 `.runtime` 或系统临时目录，不会提交到 Git。

### 停止全部服务

```powershell
.\scripts\stop-local-stack.ps1
```

## 本地演示账号

| 入口 | 账号 | 初始密码 | 用途 |
| --- | --- | --- | --- |
| `/login` | `investigator` | `OriginGuard@123` | 上传图片、运行分析、查看记录、人工核验 |
| `/admin/login` | `admin` | `OriginGuard@123` | 系统配置、知识管理、能力查看和运行审计 |

管理端不会出现在普通用户登录页中。演示密码可通过 `ORIGINGUARD_DEMO_PASSWORD` 覆盖，不得用于生产环境。

## 一次完整分析的数据流

```text
用户选择图片
→ 浏览器检查文件签名并计算 SHA-256
→ 后端再次验证图片并保存到 MinIO
→ 系统自动创建分析记录和 Agent Task
→ 媒体分类模型识别图片类型
→ RetrievalOrchestrator 获取已发布知识与质量排序后的学术来源
→ LLM 生成受约束调查计划
→ Harness 校验 Skill、Tool、权限与预算
→ 模型注册中心选择适用取证模型
→ 工具执行并保存统一 Observation
→ LLM 根据 Observation 决定继续、重规划或停止
→ 汇总生成可解释的 Agent 初步判断
→ 用户查看原图、概率、注意力图、知识依据、检索影响和能力限制
→ 用户完成人工核验
```

系统内部仍保留分析记录状态、乐观锁、数据隔离和追加式审计，用于保证并发安全和可追溯性；这些实现细节不会要求普通用户手工操作。

## 身份与安全

- JWT Access Token 与 HttpOnly Refresh Cookie 轮换
- 普通用户和管理员两类入口与权限边界
- 用户端明确显示登录状态、当前账号和退出入口，退出后可使用其他账号重新登录
- 管理员不能替用户确认最终检测结果
- 所有业务查询均带数据隔离上下文
- Agent 只能调用受控应用工具，不能执行任意 Shell、SQL 或外部 URL
- 上传媒体、EXIF、模型输出和知识文档都按不可信输入处理
- 不提交 `.env`、访问密钥、模型权重、上传内容、日志和运行缓存
- 案件状态更新使用乐观锁，防止并发请求静默覆盖结果

相关决策见 [ADR-007 用户负责结果确认](docs/adr/ADR-007-investigator-owned-result-confirmation.md)。

## 测试与构建

前端：

```powershell
cd apps/web
npm run test -- --run
npm run build
```

后端：

```powershell
cd services/server
mvn test
```

后端集成测试通过 Testcontainers 启动隔离的 PostgreSQL、pgvector 与 MinIO，不会写入本地开发数据库。

## 本地数据与模型目录

以下内容只保存在本机，不进入 Git：

```text
.runtime/models             模型权重
.runtime/cache              模型与运行缓存
.runtime/logs               统一启动日志
.runtime/pids               后台进程信息
.data                       Docker 持久化数据
```

### 可选取证模型

动漫/漫画专用模型：

```powershell
.\scripts\setup-anixplore.ps1 -CheckpointPath D:\path\to\official-checkpoint.pth
```

安装脚本固定 AniXplore 源码提交并为权重生成 SHA-256 sidecar；运行时会校验摘要。默认将分数 `<= 0.35` 解释为倾向真实、`>= 0.65` 解释为倾向生成，中间区间保持不确定。阈值是保守默认值，仍应通过业务验证集校准。

扩散重建复核器：

```powershell
.\scripts\setup-aeroblade.ps1
```

两项能力均只把源码、依赖、权重和模型缓存写入项目的 `.runtime` 或项目 Python 环境。扩散重建距离不是 AIGC 概率；未使用验证集配置 `AEROBLADE_DISTANCE_THRESHOLD` 时只展示为辅助证据，不参与方向性投票。

## 后续计划

- 使用动漫/漫画业务验证集校准 AniXplore 决策区间，并为数字绘画与矢量卡通接入跨域专用模型
- 接入局部篡改定位并输出定位掩码
- 增加检测记录归档与一键重新分析
- 生成可导出的结构化真实性分析报告
- 扩展视频抽帧、关键帧分析和时序一致性检测
- 获得验证集后校准不同媒体类型和模型版本的判定阈值
