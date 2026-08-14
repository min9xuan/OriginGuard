# RAG

M4.1 已实现第一版：租户和发布状态前置过滤、PostgreSQL 全文检索、pgvector 混合排序与逐条文档/Chunk/版本引用。当前向量由本地确定性 Feature Hashing 生成，用于验证 Harness 完整链路；真实 Embedding、RRF、Reranker 和检索评测属于下一迭代。
