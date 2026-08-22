---
code: detect_aigc_with_aide
version: 1.0.0
description: 运行 AIDE 混合频域 AIGC 图片检测器，保存概率、阈值、模型溯源和可解释结果
requiredPermissions: agent:run,asset:read,case:read
allowedCaseStatuses: INVESTIGATING
allowedTools: model.detect_aigc_with_aide
maxSteps: 2
required: true
prePlanning: false
---
结合规划前得到的媒体类型解释 AIDE 输出。阈值结果是 Agent 的初步机器判断，不得替代审核员的最终结论。
