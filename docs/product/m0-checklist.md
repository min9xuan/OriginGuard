# M0 Repository & Architecture

## 已完成

- [x] Monorepo 目录与所有权边界
- [x] Vue、Spring Boot、FastAPI、Worker 骨架
- [x] 基础设施 Compose 定义
- [x] OpenAPI 与事件 Schema 起点
- [x] 五份核心 ADR
- [x] 基础 CI 工作流
- [x] 四类知识库目录

## 环境与依赖

- [x] 确认所有主要包管理器缓存和虚拟环境均位于 D 盘
- [x] 安装前端依赖并生成 lockfile
- [x] 安装 Maven 3.9.16 并生成 Maven Wrapper
- [x] 使用本机 Python 3.11 创建 D 盘虚拟环境并安装开发依赖
- [x] 安装 Docker Desktop 4.84.0 并拉取基础设施镜像
- [x] 执行真实 build、test 和 compose config

## M0 完成门槛

- [x] 前端类型检查、Vitest 与生产构建成功
- [x] Spring Context 测试成功（Java 22 / Spring Boot 4.0.1）
- [x] Python Ruff、MyPy、Pytest 与导入测试成功（Python 3.11）
- [x] `docker compose config` 成功
- [x] PostgreSQL/pgvector、Redis、RabbitMQ、MinIO 健康检查通过

## 2026-08-02 验证结果

- 前端：146 个依赖包；TypeScript 5.9.3；1 个空测试集正常退出；Vite 生产构建成功。
- Java：1 个 Spring Context 测试通过，0 失败；Maven `BUILD SUCCESS`。
- Python：1 个 Pytest 测试通过，Ruff/MyPy/包导入通过，`pip check` 无冲突。
- PostgreSQL：`vector`、`pgcrypto` 扩展已启用。
- Redis：`PONG`。
- RabbitMQ：`Ping succeeded`。
- MinIO：健康端点 HTTP 200。
