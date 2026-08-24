# ADR-007：由调查员确认 Agent 辅助调查结果

状态：已采纳，取代 ADR-006 中将独立审核员作为核心角色的设计。

## 决策

OriginGuard 的核心用户流程只保留两个角色：

- `INVESTIGATOR`：上传媒体、创建案件、运行 Agent、核验 Observation、形成正式证据，并确认或修正 Agent 初步判断；
- `ADMIN`：管理租户、用户、知识库、模型、工具和系统审计，可以分派案件负责人，但不自动拥有结果确认权限。

案件主流程为：

`DRAFT → READY → INVESTIGATING → WAITING_CONFIRMATION → COMPLETED`

若调查员在确认阶段选择 `INCONCLUSIVE`，案件返回 `INVESTIGATING` 补充材料。Agent 只产生可追溯的初步判断，最终结果仍需人确认。

## 原因

当前产品面向普通用户或调查人员对网络图像、视频执行 AIGC 真实性分析，并非多人审批平台。强制增加审核员会制造额外账号、分派和等待环节，削弱 Agent 检测主线。若未来企业租户需要职责分离，可作为可选审批策略扩展，而不再作为所有案件的默认流程。
