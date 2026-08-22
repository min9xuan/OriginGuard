---
code: verify_media_integrity
version: 1.0.0
description: 核验存储文件的大小、类型和 SHA-256，确认后续分析使用的字节未被替换
requiredPermissions: agent:run,asset:read,case:read
allowedCaseStatuses: INVESTIGATING
allowedTools: media.verify_integrity
maxSteps: 2
required: true
prePlanning: false
---
对案件中的每个媒体资产执行完整性核验。结果属于确定性事实；哈希一致不等于内容真实，只说明当前文件与登记文件一致。
