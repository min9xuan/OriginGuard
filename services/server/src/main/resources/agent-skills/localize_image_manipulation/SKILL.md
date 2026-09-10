---
code: localize_image_manipulation
version: 1.0.0
description: 使用 Mesorch 检测局部内容篡改并输出像素级掩码、热力图、叠加图和疑似区域
requiredPermissions: agent:run,asset:read,case:read
allowedCaseStatuses: INVESTIGATING
allowedTools: model.localize_image_manipulation
maxSteps: 2
required: true
prePlanning: false
---
对每张图片运行局部篡改定位。输出必须保留模型版本、权重指纹、阈值、校准状态、处理耗时、疑似篡改概率、面积占比、连通区域以及三种可视化。该能力用于发现拼接、复制移动、擦除、修复或局部生成等内容变化，不得把热力响应解释为编辑工具、生成器或责任主体；未配置模型时必须记录不可用状态，不得伪造定位结果。
