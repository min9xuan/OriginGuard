# ADR-003: PostgreSQL + pgvector

- 状态：Accepted
- 决策：业务数据、知识元数据、全文索引与向量统一存入 PostgreSQL。
- 原因：便于事务管理、权限前置过滤和本地一键部署。
- 后果：需要监控向量索引规模，并为未来独立检索服务保留 Adapter。

