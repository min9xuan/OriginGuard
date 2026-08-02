# ADR-005: RAG Trust Model

- 状态：Accepted
- 决策：检索内容按来源、租户、版本、可见性和信任等级过滤；上传文本永远不升级为系统指令。
- 原因：降低跨租户泄露、过期知识和间接 Prompt Injection 风险。
- 后果：摄取、发布审核、检索和 Context Builder 都必须保留信任标签与引用。

