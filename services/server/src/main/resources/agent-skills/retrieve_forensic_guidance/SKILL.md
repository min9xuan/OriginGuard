---
code: retrieve_forensic_guidance
version: 1.0.0
description: 检索已发布的取证知识，返回带文档版本和知识片段标识的可追溯引用
requiredPermissions: agent:run,case:read,knowledge:read
allowedCaseStatuses: INVESTIGATING
allowedTools: rag.retrieve_forensic_guidance
maxSteps: 2
required: true
prePlanning: false
---
检索内容用于约束调查方法、解释证据局限和支持规划，不应被保存为媒体本身的 Observation，也不能直接证明媒体真伪。
