---
code: detect_aigc_with_aide
version: 1.0.0
description: 运行多特征生成内容鉴别模型，保存概率、阈值、模型溯源和可解释结果
requiredPermissions: agent:run,asset:read,case:read
allowedCaseStatuses: INVESTIGATING
allowedTools: model.detect_aigc_with_aide
maxSteps: 2
required: true
prePlanning: false
---
结合规划前得到的媒体类型，从模型能力注册中心选择当前最匹配的生成内容鉴别能力。若专用模型尚未接入，必须明确记录降级原因。所有模型结果统一保存概率、判断、置信度、质量、可视化和限制信息；它们只是 Agent 的初步机器判断，必须由用户最终确认。
