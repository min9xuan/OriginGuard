# ADR-004: Bounded Agent Workflow

- 状态：Accepted
- 决策：使用单 Agent、显式状态机、有限步数、Checkpoint 和人工审批。
- 原因：调查过程必须可恢复、可审计，且不能让模型获得无限工具自治权。
- 后果：LLM 只负责受限规划与结构化推理；策略和权限在模型外执行。

