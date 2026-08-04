# OriginGuard

OriginGuard 是一个面向内容审核与数字取证场景的 AIGC 内容真实性检测、篡改分析和来源溯源平台。

> 当前状态：M0 已完成；M1.1 媒体记录与案件骨架已实现。人工证据、审核决定、文件存储和真实模型尚未接入。

## 当前包含

- Vue 3 + TypeScript + Vite 前端骨架
- Spring Boot 模块化单体骨架
- FastAPI 模型 API 与 RabbitMQ Worker 骨架
- PostgreSQL/pgvector、Redis、RabbitMQ、MinIO 的 Compose 定义
- OpenAPI 与事件 JSON Schema 的初始契约
- 架构决策记录（ADR）和基础 CI
- Spring Security、JWT Access Token、HttpOnly Refresh Cookie 轮换
- 调查员、审核员、管理员三角色权限与租户上下文
- 媒体元数据与 SHA-256 指纹登记（M1.1 不上传文件内容）
- 调查案件创建、列表、详情、编辑和媒体关联
- `DRAFT → READY → INVESTIGATING → WAITING_REVIEW` 状态推进
- 案件乐观锁、租户范围查询和追加式审计时间线

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

## M1.1 使用流程

使用 `investigator` 登录后：

```text
媒体资产 → 选择本地图片 → 浏览器计算 SHA-256 → 登记媒体记录
调查案件 → 创建案件并关联媒体 → 标记 READY → 开始调查 → 提交人工审核
```

当前只保存文件名、MIME、大小和 SHA-256，文件内容不会离开浏览器。MinIO 分片上传、文件安全检查、EXIF 和 C2PA 属于后续媒体取证阶段。

管理员和审核员可以查看当前租户案件，但不能创建或编辑案件；人工审核通过、驳回和报告终签尚未在 M1.1 开放。详细边界见 [M1.1 实现说明](docs/product/m1.1-media-case-foundation.md)。
