import type { AgentTaskStatus } from '../types/agent'
import type { CasePriority, CaseStatus, EvidenceConfidence, EvidenceConclusion } from '../types/business'

const caseStatusLabels: Record<CaseStatus, string> = {
  DRAFT: '草稿',
  READY: '待调查',
  INVESTIGATING: '调查中',
  WAITING_REVIEW: '待人工审核',
  CONFIRMED: '已确认',
  REJECTED: '已驳回',
  FAILED: '处理失败',
  ARCHIVED: '已归档',
}

const priorityLabels: Record<CasePriority, string> = {
  LOW: '低',
  NORMAL: '普通',
  HIGH: '高',
  CRITICAL: '紧急',
}

const agentStatusLabels: Record<AgentTaskStatus, string> = {
  PENDING: '等待运行',
  RUNNING: '正在取证',
  COMPLETED: '取证完成',
  FAILED: '运行失败',
  CANCELLED: '已取消',
}

const skillLabels: Record<string, { name: string; description: string }> = {
  classify_media_type_with_clip: { name: 'CLIP 媒体类型识别', description: '在规划前识别图片内容类型，为 LLM 路由和结果解释提供上下文' },
  verify_media_integrity: { name: '文件完整性检查', description: '核验文件哈希、大小和存储内容是否一致' },
  extract_image_metadata: { name: '图片元数据提取', description: '读取图片格式、尺寸和可用的元数据信息' },
  compare_perceptual_similarity: { name: '感知相似度分析', description: '在存在多个可比媒体时检查视觉相似程度' },
  detect_aigc_with_aide: { name: 'AIDE 生成检测', description: '运行频域与语义混合特征模型，输出可追溯的 AIGC 检测分数' },
  retrieve_forensic_guidance: { name: '取证知识检索', description: '从知识库检索与当前案件相关的调查指引' },
}

const stepLabels: Record<string, { name: string; description: string }> = {
  CONTEXT_ASSEMBLED: { name: '整理案件上下文', description: '汇总案件、媒体和人工审核信息' },
  PLAN_GENERATED: { name: '生成调查方案', description: '多模态模型根据案件内容选择取证能力' },
  PLAN_VALIDATED: { name: '校验调查方案', description: '检查模型方案是否满足权限、预算和必选步骤' },
  REPLAN_DECIDED: { name: '根据观察决定下一步', description: '规划器读取最新观察，决定继续、调整计划或停止' },
  REPLAN_FALLBACK: { name: '重规划降级', description: '动态决策异常，Harness 继续执行已校验的安全计划' },
  REPLAN_LIMIT_REACHED: { name: '达到重规划上限', description: '停止请求动态决策，继续完成当前安全计划' },
  SKILL_SELECTED: { name: '选择取证能力', description: '确定本轮将要执行的 Skill' },
  TOOL_CALLED: { name: '执行取证工具', description: '调用受控工具读取媒体并产生事实结果' },
  KNOWLEDGE_RETRIEVAL_RECORDED: { name: '记录知识依据', description: '保存本次 RAG 检索结果和引用来源' },
  OBSERVATION_RECORDED: { name: '记录 Agent 观察', description: '将工具结果保存为待调查员确认的观察项' },
  CHECKPOINT_SAVED: { name: '保存运行检查点', description: '记录可恢复的任务执行进度' },
  CONCLUSION_SYNTHESIZED: { name: '汇总阶段性结论', description: '汇总已取得的事实和当前能力边界' },
  TASK_COMPLETED: { name: '完成自动取证', description: 'Agent 已完成，结果等待调查员核验' },
  TASK_FAILED: { name: '自动取证失败', description: '任务未能完成，请查看失败原因' },
  TASK_CANCELLED: { name: '自动取证已取消', description: '任务被操作者取消' },
}

const evidenceLabels: Record<string, string> = {
  MEDIA_METADATA: '媒体元数据',
  BASIC_MEDIA_FORENSICS: '基础媒体检查',
  FILE_INTEGRITY: '文件完整性',
  IMAGE_METADATA: '图片元数据',
  PERCEPTUAL_SIMILARITY: '感知相似度',
  MEDIA_TYPE_CLASSIFICATION: 'CLIP 媒体类型',
  AIGC_DETECTION: 'AIGC 模型检测',
}

const conclusionLabels: Record<EvidenceConclusion, string> = {
  LIKELY_AUTHENTIC: '倾向真实',
  LIKELY_SYNTHETIC: '倾向 AI 生成',
  INCONCLUSIVE: '证据不足',
}

const confidenceLabels: Record<EvidenceConfidence, string> = {
  LOW: '低置信度',
  MEDIUM: '中置信度',
  HIGH: '高置信度',
}

const fieldLabels: Record<string, string> = {
  caseNumber: '案件编号',
  caseStatus: '案件状态',
  assetCount: '媒体数量',
  humanReviewCount: '人工审核数量',
  filename: '文件名',
  contentType: '文件类型',
  byteSize: '文件大小（字节）',
  sha256: 'SHA-256',
  storedSha256: '存储文件 SHA-256',
  matchesRegisteredHash: '与登记哈希一致',
  width: '宽度',
  height: '高度',
  format: '图片格式',
  imageCount: '图片数量',
  allChecksPassed: '全部检查通过',
  toolVersion: '工具版本',
  provider: '执行来源',
  model: '检测模型',
  modelVersion: '模型版本',
  checkpointSha256: '权重指纹',
  device: '运行设备',
  precision: '计算精度',
  analyzedImageCount: '已检测图片数',
  overallClassification: '模型阶段判断',
  maximumSyntheticProbability: '最高 AI 生成概率',
  syntheticProbability: 'AI 生成概率',
  authenticProbability: '真实图片概率',
  syntheticThreshold: 'AI 生成判定阈值',
  authenticThreshold: '真实图片判定阈值',
  processingMilliseconds: '检测耗时（毫秒）',
  mediaType: '媒体类型代码',
  mediaTypeLabel: '媒体类型',
  mediaTypeScore: '类型相对匹配度',
  mediaTypeMargin: '类型领先幅度',
  promptVersion: '类型提示词版本',
  nearDuplicateThreshold: '近重复阈值',
  comparisonCount: '实际比较次数',
  algorithm: '分析算法',
  comparedAssetCount: '参与比较的媒体数',
  knowledgeAvailable: '知识库是否有结果',
  citationCount: '引用数量',
  status: '状态',
  verdict: '判断',
  summary: '摘要',
  remainingStepBudget: '剩余步骤预算',
  checkpointVersion: '检查点版本',
  replanCount: '动态决策次数',
  executionMode: '执行模式',
}

export function caseStatusLabel(status: CaseStatus) {
  return caseStatusLabels[status] ?? status
}

export function priorityLabel(priority: CasePriority) {
  return priorityLabels[priority] ?? priority
}

export function agentStatusLabel(status: AgentTaskStatus) {
  return agentStatusLabels[status] ?? status
}

export function skillMeta(code: string) {
  return skillLabels[code] ?? { name: code || '未命名 Skill', description: '受控取证能力' }
}

export function stepMeta(type: string) {
  return stepLabels[type] ?? { name: type, description: 'Agent 持久化执行步骤' }
}

export function evidenceTypeLabel(type: string) {
  return evidenceLabels[type] ?? type
}

export function evidenceConclusionLabel(value: EvidenceConclusion) {
  return conclusionLabels[value] ?? value
}

export function evidenceConfidenceLabel(value: EvidenceConfidence) {
  return confidenceLabels[value] ?? value
}

export function verdictLabel(value: string) {
  const labels: Record<string, string> = {
    INCONCLUSIVE: '证据不足，暂无法判断',
    LIKELY_AUTHENTIC: '倾向真实',
    LIKELY_SYNTHETIC: '倾向 AI 生成',
    CONFLICTING_EVIDENCE: '证据冲突，不能下结论',
    UNSUPPORTED_INPUT: '输入不适用',
  }
  return labels[value] ?? value
}

export function localizeSystemText(value: string) {
  const translations: Record<string, string> = {
    'Planner-selected deterministic checks and knowledge retrieval completed; the resulting facts support human review and do not independently prove AI generation.':
      '规划器选择的确定性检查和取证知识检索已完成；当前事实可辅助人工审核，但不能独立证明媒体由 AI 生成。',
    'C2PA verifier not configured': '尚未配置 C2PA 内容凭证校验器',
    'No AIGC classifier or manipulation localization model': '尚未接入 AIGC 分类器或篡改定位模型',
    'The local multimodal LLM selects Skills but does not issue the forensic verdict':
      '本地多模态模型只负责选择 Skill，不直接作出取证裁决',
    'The Fake Planner uses a fixed Skill sequence and does not interpret media content':
      '固定测试规划器只执行预设 Skill 顺序，不理解媒体内容',
  }
  return translations[value] ?? value
}

export interface DisplayField {
  label: string
  value: string
}

export function payloadFields(payload: Record<string, unknown>, limit = 8): DisplayField[] {
  return Object.entries(payload)
    .filter(([, value]) => value !== null && value !== undefined && typeof value !== 'object')
    .slice(0, limit)
    .map(([key, value]) => ({ label: fieldLabels[key] ?? key, value: displayFieldValue(key, value) }))
}

function displayFieldValue(key: string, value: unknown) {
  if (['classification', 'overallClassification', 'verdict'].includes(key) && typeof value === 'string') {
    return verdictLabel(value)
  }
  if (['syntheticProbability', 'authenticProbability', 'maximumSyntheticProbability', 'syntheticThreshold', 'authenticThreshold', 'mediaTypeScore', 'mediaTypeMargin'].includes(key)
    && typeof value === 'number') {
    return `${(value * 100).toFixed(1)}%`
  }
  return displayValue(value)
}

export function displayValue(value: unknown) {
  if (typeof value === 'boolean') return value ? '是' : '否'
  if (Array.isArray(value)) return value.map(String).join('、') || '无'
  if (value === null || value === undefined || value === '') return '无'
  return String(value)
}

export function auditActionLabel(action: string) {
  const labels: Record<string, string> = {
    CASE_CREATED: '创建案件',
    CASE_UPDATED: '更新案件信息',
    CASE_STATUS_CHANGED: '推进案件状态',
    CASE_ASSIGNED: '分派调查职责',
    CASE_ASSET_LINKED: '关联媒体',
    AGENT_TASK_CREATED: '创建 Agent 任务',
    AGENT_TASK_COMPLETED: 'Agent 取证完成',
    AGENT_TASK_FAILED: 'Agent 取证失败',
    AGENT_OBSERVATION_INCLUDED: '纳入 Agent 观察',
    EVIDENCE_CREATED: '记录人工证据',
    REVIEW_TASK_CREATED: '创建审核任务',
    REVIEW_DECIDED: '提交审核决定',
  }
  return labels[action] ?? action
}

export function auditSummary(action: string, details: Record<string, unknown>) {
  if (action === 'CASE_CREATED') return `案件创建完成，已关联 ${details.assetCount ?? 0} 个媒体文件`
  if (action === 'CASE_UPDATED') return '案件基础信息已更新'
  if (action === 'CASE_STATUS_CHANGED') {
    const from = caseStatusLabels[String(details.from) as CaseStatus] ?? String(details.from ?? '未知')
    const to = caseStatusLabels[String(details.to) as CaseStatus] ?? String(details.to ?? '未知')
    return `案件由“${from}”推进为“${to}”`
  }
  if (action === 'AGENT_TASK_CREATED') return '已创建自动取证任务，等待 Agent 执行'
  if (action === 'AGENT_TASK_COMPLETED') {
    const skills = Array.isArray(details.executedSkills) ? details.executedSkills.length : 0
    return `自动取证完成，共执行 ${skills} 项受控能力`
  }
  if (action === 'AGENT_OBSERVATION_INCLUDED') return '调查员已将一条 Agent 候选观察纳入正式证据'
  if (action === 'CASE_ASSIGNED') return '调查员和独立审核员职责已更新'
  if (action === 'REVIEW_DECIDED') return '指定审核员已提交审核决定'
  return Object.keys(details).length ? `已记录 ${Object.keys(details).length} 项操作信息` : '操作已记录'
}
