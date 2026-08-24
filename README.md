# OriginGuard

面向 AIGC 媒体真实性分析的多模态 Agent 系统。

OriginGuard 不让单个大模型直接猜测图片真假，而是通过 Agent Harness 读取媒体上下文、制定调查计划、调用受控取证能力、记录 Observation，并根据新结果动态调整后续步骤。系统最终给出可解释的初步判断，由用户完成人工核验。

## 当前能力

### 用户检测流程

- 产品展示首页与独立登录入口
- JPEG、PNG、WebP 图片上传，单文件最大 25 MB
- 文件魔数、MIME、图片解码、大小与像素上限校验
- 浏览器 SHA-256 计算与服务端内容指纹复核
- 自动创建内部分析记录并启动 Agent，无需用户手工配置案件字段
- 检测记录、原图预览、分析过程与人工核验结果查询
- 用户可选择“AI 生成”“非 AI 生成”或“暂时无法判断”，并决定是否引用 Agent 的结论与理由

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

- 媒体类型识别：区分摄影、插画卡通、三维渲染、界面截图和平面设计
- 生成内容鉴别：输出 AI 生成概率、阈值判断、质量评估和语义注意力图
- 文件完整性检查：核对登记哈希、存储内容和文件大小
- 图片元数据提取：记录格式、尺寸和可用 EXIF 摘要
- 感知相似度：在存在多张可比图片时进行辅助比较
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

当前已经具备通用图像生成内容鉴别能力，并为插画卡通鉴别、三维渲染鉴别、局部篡改定位和内容来源凭证验证预留插件接口。

实现说明见 [M5.3 可插拔取证模型注册与动态路由](docs/product/m5.3-forensic-model-routing.md)。

### RAG 取证知识

- Markdown 和纯文本知识文档管理、草稿审核与版本发布
- 中文 Embedding、pgvector 向量索引和 PostgreSQL 全文检索
- 语义分数、关键词分数、来源优先级共同参与混合召回
- 用户自建知识优先于外部学术候选知识
- 按需检索权威论文元数据与摘要，不自动下载论文 PDF
- 外部知识先保存为草稿，确认发布后才进入 Agent 检索范围
- Citation 保存文档版本、知识片段、排序和各项召回分数
- RAG 只为规划与结果解释提供参考，不直接构成图片真伪证据

## 技术栈

- Web：Vue 3、TypeScript、Vite、Element Plus
- 业务与 Agent 中枢：Java 22、Spring Boot、Spring Security、Flyway、Maven
- 模型服务：Python 3.11、FastAPI、PyTorch、Transformers
- 数据与检索：PostgreSQL、pgvector
- 对象存储：MinIO
- 基础设施：Docker Compose
- 扩展基础：Redis、RabbitMQ、异步模型 Worker 与内容凭证 Sidecar 骨架

Redis、RabbitMQ 和异步 Worker 已保留工程结构，但当前本地同步分析主链路不依赖它们运行。

## 系统结构

```text
apps/web                    用户端、检测记录与隐藏管理端
services/server             身份安全、业务流程、Agent Harness 与 RAG
services/model-api          Embedding、媒体分类和取证模型 API
services/c2pa-sidecar       内容来源凭证适配器占位
workers/model-worker        异步模型任务 Worker 骨架
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
- Python Model API
- 本地多模态 LLM API
- Spring Boot 后端
- Vue 前端

启动完成后访问：

- 产品首页：<http://127.0.0.1:5173>
- 用户分析入口：<http://127.0.0.1:5173/analyze>
- 管理端入口：<http://127.0.0.1:5173/admin>
- 后端健康检查：<http://127.0.0.1:8080/actuator/health>
- 模型服务健康检查：<http://127.0.0.1:8090/health>

脚本以后台进程方式运行服务，并将 PID 与日志保存在 `.runtime`。启动结束后当前终端可以继续输入命令。

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
→ LLM 生成受约束调查计划
→ Harness 校验 Skill、Tool、权限与预算
→ 模型注册中心选择适用取证模型
→ 工具执行并保存统一 Observation
→ LLM 根据 Observation 决定继续、重规划或停止
→ 汇总生成可解释的 Agent 初步判断
→ 用户查看原图、概率、注意力图、知识依据和能力限制
→ 用户完成人工核验
```

系统内部仍保留分析记录状态、乐观锁、数据隔离和追加式审计，用于保证并发安全和可追溯性；这些实现细节不会要求普通用户手工操作。

## 身份与安全

- JWT Access Token 与 HttpOnly Refresh Cookie 轮换
- 普通用户和管理员两类入口与权限边界
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

## 后续计划

- 接入插画和卡通专用生成内容鉴别模型
- 接入局部篡改定位并输出定位掩码
- 接入内容来源凭证验证
- 增加检测记录归档、删除与重新分析
- 生成可导出的结构化真实性分析报告
- 扩展视频抽帧、关键帧分析和时序一致性检测
- 获得验证集后校准不同媒体类型和模型版本的判定阈值
