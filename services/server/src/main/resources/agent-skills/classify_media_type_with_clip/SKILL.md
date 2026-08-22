---
code: classify_media_type_with_clip
version: 1.0.0
description: 在规划前使用 CLIP 识别媒体内容类型，为 Skill 路由和 AIDE 结果解释提供语义上下文
requiredPermissions: agent:run,asset:read,case:read
allowedCaseStatuses: INVESTIGATING
allowedTools: model.classify_media_type_with_clip
maxSteps: 2
required: true
prePlanning: true
---
这是 Harness 的规划前步骤，规划器不得重复选择。CLIP 输出是媒体类型的相对匹配结果，不是 AIGC 生成概率。
