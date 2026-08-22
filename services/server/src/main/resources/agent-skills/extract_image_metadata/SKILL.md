---
code: extract_image_metadata
version: 1.0.0
description: 提取图片格式、尺寸和可用元数据，为内容解释和异常检查提供结构事实
requiredPermissions: agent:run,asset:read,case:read
allowedCaseStatuses: INVESTIGATING
allowedTools: media.extract_image_metadata
maxSteps: 2
required: false
prePlanning: false
---
仅对图片资产执行。元数据缺失不能单独证明图片由 AI 生成；输出应作为辅助事实交给后续综合判断。
