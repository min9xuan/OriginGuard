---
code: compare_perceptual_similarity
version: 1.0.0
description: 比较同一案件媒体的感知哈希，在存在多个可比资产时发现近重复关系
requiredPermissions: agent:run,asset:read,case:read
allowedCaseStatuses: INVESTIGATING
allowedTools: media.compare_perceptual_similarity
maxSteps: 2
required: false
prePlanning: false
---
只有案件包含至少两个具有比较价值的图片时才应选择。相似度表示视觉近似，不表示生成来源相同，也不是 AIGC 概率。
