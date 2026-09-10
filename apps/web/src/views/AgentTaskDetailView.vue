<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { agentApi } from '../api/agents'
import { caseApi } from '../api/cases'
import { mediaApi } from '../api/media'
import { ApiRequestError } from '../api/http'
import { useAuthStore } from '../stores/auth'
import type { AgentObservation, AgentStep, AgentTaskDetails } from '../types/agent'
import type { CaseWorkflow, EvidenceConclusion, InvestigationCase } from '../types/business'
import { formatDate } from '../utils/format'
import {
  agentStatusLabel,
  caseStatusLabel,
  evidenceConclusionLabel,
  evidenceTypeLabel,
  localizeSystemText,
  payloadFields,
  skillMeta,
  stepMeta,
  verdictLabel,
} from '../utils/presentation'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const taskId = route.params.taskId as string
const routePrefix = () => route.path.startsWith('/admin') ? '/admin' : '/analyze'
const details = ref<AgentTaskDetails | null>(null)
const sourceCase = ref<InvestigationCase | null>(null)
const workflow = ref<CaseWorkflow>({ evidence: [], decisions: [], agentEvidenceCandidates: [] })
const loading = ref(false)
const mutating = ref(false)
const confirming = ref(false)
const executionDrawerVisible = ref(false)
const verificationSection = ref<HTMLElement | null>(null)
const liveTrace = ref<HTMLElement | null>(null)
let disposed = false
let progressAbort: AbortController | null = null
const originalUrls = ref<Record<string, string>>({})
const attentionUrls = ref<Record<string, string>>({})
const localizationMaskUrls = ref<Record<string, string>>({})
const localizationHeatmapUrls = ref<Record<string, string>>({})
const localizationOverlayUrls = ref<Record<string, string>>({})
const localizationModes = ref<Record<string, 'overlay' | 'heatmap' | 'mask'>>({})
const visualizationLoading = ref<Record<string, boolean>>({})
const verification = reactive({
  finalConclusion: 'LIKELY_SYNTHETIC' as EvidenceConclusion,
  reason: '',
  includeAgentAssessment: true,
})

const planStep = computed(() => details.value?.steps.find((step) => step.stepType === 'PLAN_GENERATED'))
const plannerProvider = computed(() => String(planStep.value?.output.provider || 'PENDING'))
const plannerName = computed(() => {
  if (plannerProvider.value.includes('QWEN')) return '本地 Qwen3-VL'
  if (plannerProvider.value === 'FAKE') return '固定测试规划器'
  return plannerProvider.value === 'PENDING' ? '等待规划' : plannerProvider.value
})
const plannerSummary = computed(() => String(planStep.value?.output.summary || ''))
const isLlmPlanner = computed(() => plannerProvider.value !== 'FAKE' && plannerProvider.value !== 'PENDING')
const plannerSkills = computed(() => {
  const selected = planStep.value?.output.selectedSkills
  const source = Array.isArray(selected)
    ? selected
    : details.value?.steps.filter((step) => step.stepType === 'SKILL_SELECTED').map((step) => step.output) || []
  return source.flatMap((item) => {
    if (!item || typeof item !== 'object') return []
    const value = item as Record<string, unknown>
    return [{
      skillCode: String(value.skillCode || ''),
      skillVersion: String(value.skillVersion || ''),
      reason: String(value.reason || ''),
    }]
  })
})
const replanSteps = computed(() => details.value?.steps.filter((step) =>
  ['REPLAN_DECIDED', 'REPLAN_FALLBACK'].includes(step.stepType),
) ?? [])
const completedToolSteps = computed(() => details.value?.steps.filter((step) => step.stepType === 'TOOL_CALLED') ?? [])
const modelExecutionSteps = computed(() => details.value?.steps.filter((step) => [
  'PRIMARY_MODEL_COMPLETED', 'CROSS_DOMAIN_MODEL_COMPLETED', 'SECONDARY_MODEL_COMPLETED',
  'EVIDENCE_FUSED', 'RESULT_EXPLAINED',
].includes(step.stepType)) ?? [])
const fallbackSteps = computed(() => replanSteps.value.filter((step) => step.stepType === 'REPLAN_FALLBACK'))
const acceptedDecisionSteps = computed(() => replanSteps.value.filter((step) => step.stepType === 'REPLAN_DECIDED'))
const planAdjustmentSummaries = computed(() => {
  const summaries = acceptedDecisionSteps.value.map(stepSummary)
  if (fallbackSteps.value.length) {
    summaries.unshift(`动态调整有 ${fallbackSteps.value.length} 次未通过安全校验，Harness 在对应工具执行完成后继续了已校验计划；重复提示已合并。`)
  }
  return summaries
})
const latestStep = computed(() => details.value?.steps.at(-1))
const currentModelState = computed(() => {
  const step = latestStep.value
  if (!step) return '等待读取案件信息。'
  const message = String(step.output?.message || '').trim()
  if (message) return message
  if (step.stepType === 'CONTEXT_ASSEMBLED') return '正在检查案件目标、关联媒体和已有人工记录。'
  if (step.stepType === 'PLAN_GENERATED') return '初始调查方案已经形成，正在进行权限与预算校验。'
  if (step.stepType === 'PLAN_VALIDATED') return '方案校验通过，准备按顺序调用受控取证能力。'
  if (step.stepType === 'OBSERVATION_RECORDED') return '正在读取最新工具观察，并判断它对后续计划的影响。'
  if (step.stepType === 'REPLAN_DECIDED' || step.stepType === 'REPLAN_FALLBACK') return stepSummary(step)
  if (step.stepType === 'CONCLUSION_SYNTHESIZED') return '现有结果已经汇总，等待调查员核验。'
  if (step.stepType === 'TASK_COMPLETED') return '本次自动取证已经结束。'
  if (step.stepType === 'TASK_FAILED') return '任务执行中断，请检查失败信息。'
  return stepSummary(step)
})
const latestModelOutputStep = computed(() => [...(details.value?.steps ?? [])].reverse().find((step) =>
  ['PLAN_GENERATED', 'REPLAN_DECIDED', 'REPLAN_FALLBACK', 'RESULT_EXPLAINED', 'CONCLUSION_SYNTHESIZED'].includes(step.stepType) &&
  Boolean(String(step.output?.summary || step.output?.message || '').trim()),
))
const latestModelOutput = computed(() => {
  const step = latestModelOutputStep.value
  if (!step) return ''
  return localizeSystemText(String(step.output?.summary || step.output?.message || '').trim())
})
const currentStageName = computed(() => latestStep.value ? stepMeta(latestStep.value.stepType).name : '准备任务')
const conclusionVerdict = computed(() => verdictLabel(String(details.value?.task.conclusion.verdict || '尚未生成')))
const conclusionSummary = computed(() => localizeSystemText(String(details.value?.task.conclusion.summary || '')))
const conclusionConfidence = computed(() => confidenceLabel(details.value?.task.conclusion.confidence))
const performance = computed(() => objectValue(details.value?.task.conclusion.performance))
const multiImageAnalysis = computed(() => objectValue(details.value?.task.conclusion.multiImageAnalysis))
const jointAssetCount = computed(() => Number(multiImageAnalysis.value.assetCount || 1))
const firstDetailedObservationId = computed(() => {
  const observations = details.value?.observations ?? []
  return observations.find(item => item.evidenceType === 'MANIPULATION_LOCALIZATION')?.id
    ?? observations.find(item => item.evidenceType === 'AIGC_DETECTION')?.id
    ?? observations[0]?.id
    ?? ''
})
const perAssetResults = computed(() => Array.isArray(multiImageAnalysis.value.perAsset)
  ? multiImageAnalysis.value.perAsset.map(objectValue) : [])
const relatedPairs = computed(() => Array.isArray(multiImageAnalysis.value.relatedPairs)
  ? multiImageAnalysis.value.relatedPairs.map(objectValue) : [])
const activeAssetId = ref('')
const mediaObservationGroups = computed(() => {
  const groups = new Map<string, { assetId: string; observations: AgentObservation[] }>()
  for (const observation of details.value?.observations ?? []) {
    if (!observation.assetId) continue
    const group = groups.get(observation.assetId) ?? { assetId: observation.assetId, observations: [] }
    group.observations.push(observation)
    groups.set(observation.assetId, group)
  }
  return [...groups.values()]
})
const activeMediaGroup = computed(() => mediaObservationGroups.value.find(group => group.assetId === activeAssetId.value)
  ?? mediaObservationGroups.value[0])
const activeAigcObservation = computed(() => activeMediaGroup.value?.observations.find(item => item.evidenceType === 'AIGC_DETECTION'))
const activeLocalizationObservation = computed(() => activeMediaGroup.value?.observations.find(item => item.evidenceType === 'MANIPULATION_LOCALIZATION'))
const activeMediaTypeObservation = computed(() => activeMediaGroup.value?.observations.find(item => item.evidenceType === 'MEDIA_TYPE_CLASSIFICATION'))
const activeProvenanceObservation = computed(() => activeMediaGroup.value?.observations.find(item => item.evidenceType === 'CONTENT_PROVENANCE'))
const activeIntegrityObservation = computed(() => activeMediaGroup.value?.observations.find(item => item.evidenceType === 'FILE_INTEGRITY'))

watch(mediaObservationGroups, (groups) => {
  if (!groups.length) {
    activeAssetId.value = ''
    return
  }
  if (!groups.some(group => group.assetId === activeAssetId.value)) activeAssetId.value = groups[0].assetId
}, { immediate: true })
const seconds = (value: unknown) => typeof value === 'number' ? `${(value / 1000).toFixed(2)} 秒` : '未记录'
const provenanceStatusLabel = (value: unknown) => ({
  VERIFIED: '凭证有效', INVALID: '凭证异常', NOT_FOUND: '未发现凭证',
  NOT_CONFIGURED: '校验器未配置', UNAVAILABLE: '暂不可用',
} as Record<string, string>)[String(value)] ?? String(value || '未知')
const conclusionSource = computed(() => details.value?.task.conclusion.synthesisSource === 'LOCAL_QWEN3_VL'
  ? '本地 Qwen 综合研判'
  : '确定性降级研判')
const conclusionLimitations = computed(() => {
  const value = details.value?.task.conclusion.limitations
  return Array.isArray(value) ? value.map((item) => localizeSystemText(String(item))) : []
})
const conclusionMissingEvidence = computed(() => textItems(details.value?.task.conclusion.missingEvidence))
const retrievalInfluenceSummary = computed(() => String(details.value?.task.conclusion.retrievalInfluenceSummary || ''))
const academicSources = computed(() => Array.isArray(details.value?.task.conclusion.academicSources)
  ? details.value!.task.conclusion.academicSources as Array<Record<string, unknown>> : [])
const webRetrievalProvider = computed(() => String(details.value?.task.conclusion.webRetrievalProvider || 'NOT_EXECUTED'))
const webRetrievalStatus = computed(() => String(details.value?.task.conclusion.webRetrievalStatus
  || (academicSources.value.length ? 'COMPLETED_WITH_RESULTS' : 'NOT_EXECUTED')))
const provenanceSummary = computed(() => objectValue(details.value?.task.conclusion.provenanceSummary))
const provenanceCounts = computed(() => objectValue(provenanceSummary.value.counts))
const provenanceItems = computed(() => Array.isArray(provenanceSummary.value.items)
  ? provenanceSummary.value.items.map(objectValue) : [])
const webRetrievalStatusLabel = computed(() => ({
  COMPLETED_WITH_RESULTS: `已联网并取得 ${academicSources.value.length} 条来源`,
  COMPLETED_NO_RESULTS: '已联网检索，但本次没有取得匹配来源',
  NOT_EXECUTED: '本次任务没有执行实时检索',
} as Record<string, string>)[webRetrievalStatus.value] || webRetrievalStatus.value)
const citationCount = computed(() => details.value?.knowledgeRetrievals.reduce(
  (total, retrieval) => total + retrieval.citations.length,
  0,
) ?? 0)
const canOperate = computed(() => Boolean(
  details.value?.task.status === 'PENDING' &&
    details.value.task.createdBy === auth.user?.id &&
    auth.hasPermission('agent:run'),
))
const pendingDecision = computed(() => workflow.value.decisions.find((decision) => decision.status === 'PENDING') ?? null)
const latestDecision = computed(() => [...workflow.value.decisions].reverse().find((decision) => decision.status !== 'PENDING') ?? null)
const canPrepareVerification = computed(() => Boolean(
  details.value?.task.status === 'COMPLETED' &&
    sourceCase.value?.status === 'INVESTIGATING' &&
    auth.hasPermission('case:submit'),
))
const canConfirmResult = computed(() => Boolean(
  sourceCase.value?.status === 'WAITING_CONFIRMATION' &&
    pendingDecision.value?.confirmerId === auth.user?.id &&
    auth.hasPermission('result:confirm'),
))
const investigationPhases = computed(() => {
  const steps = new Set(details.value?.steps.map((step) => step.stepType) ?? [])
  const phases = [
    { label: '读取案件', description: '整理案件与媒体上下文', done: steps.has('CONTEXT_ASSEMBLED') },
    { label: '识别类型并制定方案', description: 'CLIP 提供媒体类型，模型据此选择受控取证能力', done: steps.has('PLAN_VALIDATED') },
    { label: '观察并动态调整', description: '工具产生观察，规划器据此继续、重规划或停止', done: steps.has('CONCLUSION_SYNTHESIZED') },
    { label: '形成初步判断', description: '汇总证据并给出可解释结果，等待你最终确认', done: details.value?.task.status === 'COMPLETED' },
  ]
  const firstPending = phases.findIndex((phase) => !phase.done)
  return phases.map((phase, index) => ({
    ...phase,
    state: phase.done ? 'completed' : index === firstPending ? 'current' : 'upcoming',
  }))
})

async function load() {
  loading.value = true
  try {
    const taskDetails = await agentApi.get(taskId, auth.accessToken)
    details.value = taskDetails
    await loadForensicVisualizations(taskDetails)
    await loadCaseContext(taskDetails.task.caseId)
  } catch (error) {
    showError(error)
  } finally {
    loading.value = false
  }
}

async function loadCaseContext(caseId: string) {
  try {
    const [caseDetails, caseWorkflow] = await Promise.all([
      caseApi.get(caseId, auth.accessToken),
      caseApi.workflow(caseId, auth.accessToken),
    ])
    sourceCase.value = caseDetails.investigationCase
    workflow.value = caseWorkflow
  } catch {
    sourceCase.value = null
    workflow.value = { evidence: [], decisions: [], agentEvidenceCandidates: [] }
  }
}

function wait(milliseconds: number) {
  return new Promise(resolve => window.setTimeout(resolve, milliseconds))
}

async function refreshProgress() {
  const snapshot = await agentApi.get(taskId, auth.accessToken)
  details.value = snapshot
  return snapshot
}

async function followExistingRun() {
  if (!details.value || !['PENDING', 'RUNNING'].includes(details.value.task.status)) return
  mutating.value = true
  executionDrawerVisible.value = true
  progressAbort?.abort()
  progressAbort = new AbortController()
  try {
    await agentApi.events(taskId, auth.accessToken, async () => {
      if (disposed) return
      const snapshot = await refreshProgress()
      if (['COMPLETED', 'FAILED', 'CANCELLED'].includes(snapshot.task.status)) progressAbort?.abort()
    }, progressAbort.signal)
  } catch (error) {
    if (!progressAbort.signal.aborted && !disposed) showError(error)
  } finally {
    if (!disposed) await refreshProgress().catch(() => undefined)
    if (details.value) {
      await loadForensicVisualizations(details.value)
      await loadCaseContext(details.value.task.caseId)
    }
    mutating.value = false
  }
}

function objectValue(value: unknown): Record<string, unknown> {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? value as Record<string, unknown>
    : {}
}

function explanationFor(observation: AgentObservation) {
  return objectValue(observation.payload.explanation)
}

function qualityFor(observation: AgentObservation) {
  return objectValue(observation.payload.qualityAssessment)
}

function qualityIssues(observation: AgentObservation) {
  const value = qualityFor(observation).issues
  return Array.isArray(value) ? value : []
}

function mediaTypeContextFor(observation: AgentObservation) {
  return objectValue(observation.payload.mediaTypeContext || observation.payload.secondaryDetection)
}

function fusionFor(observation: AgentObservation) {
  return objectValue(observation.payload.fusion)
}

function secondaryVerificationFor(observation: AgentObservation) {
  return objectValue(observation.payload.secondaryVerification)
}

function crossDomainVerificationFor(observation: AgentObservation) {
  return objectValue(observation.payload.crossDomainVerification)
}

function crossDomainLabel(observation: AgentObservation) {
  const verification = crossDomainVerificationFor(observation)
  const status = String(verification.status || 'NOT_REQUIRED')
  if (status === 'SUCCEEDED') {
    return `${verdictLabel(String(verification.classification || 'INCONCLUSIVE'))} · ${probabilityLabel(verification.syntheticProbability)}`
  }
  if (status === 'UNAVAILABLE') return '交叉复核不可用'
  return '当前类型无需追加'
}

function analysisStagesFor(observation: AgentObservation) {
  const media = mediaTypeContextFor(observation)
  const selected = selectedCapabilityFor(observation)
  const crossDomain = crossDomainVerificationFor(observation)
  const crossDomainStatus = String(crossDomain.status || 'NOT_REQUIRED')
  const secondary = secondaryVerificationFor(observation)
  const secondaryStatus = String(secondary.status || 'SKIPPED')
  const distance = typeof secondary.reconstructionDistance === 'number'
    ? Number(secondary.reconstructionDistance).toFixed(4)
    : ''
  return [
    {
      index: '01', label: '理解图片类型', value: mediaTypeLabel(media),
      detail: `相对匹配度 ${probabilityLabel(media.mediaTypeScore)}，仅用于选择检测能力`, state: 'complete',
    },
    {
      index: '02', label: '运行主检测', value: verdictLabel(String(observation.payload.classification || 'INCONCLUSIVE')),
      detail: `${String(selected.displayName || '通用生成内容鉴别')} · AI 生成概率 ${probabilityLabel(observation.payload.syntheticProbability)}`,
      state: 'complete',
    },
    {
      index: '03', label: '跨域模型复核', value: crossDomainLabel(observation),
      detail: crossDomainStatus === 'SUCCEEDED'
        ? '通用模型独立读取原图，用于核对动漫专用模型方向'
        : String(crossDomain.reason || '仅动漫与漫画路由需要追加通用模型交叉复核'),
      state: crossDomainStatus === 'SUCCEEDED' ? 'complete' : 'neutral',
    },
    {
      index: '04', label: '独立扩散复核',
      value: secondaryStatus === 'SUCCEEDED' ? `重建距离 ${distance}` : secondaryStatus === 'SKIPPED' ? '本次无需追加' : '当前不可用',
      detail: secondaryStatus === 'SUCCEEDED'
        ? (secondary.calibrated ? '已使用校准阈值解释' : '未校准阈值，仅作为辅助信号')
        : String(secondary.reason || '系统保留主检测结果继续分析'),
      state: secondaryStatus === 'SUCCEEDED' ? 'complete' : 'neutral',
    },
    {
      index: '05', label: '融合并解释', value: verdictLabel(String(fusionFor(observation).verdict || 'INCONCLUSIVE')),
      detail: `融合置信度 ${confidenceLabel(fusionFor(observation).confidence)}，最终结果由你确认`, state: 'complete',
    },
  ]
}

function isLocalizationResult(observation: AgentObservation) {
  return String(observation.payload.localizationMethod || '').length > 0
}

function modelRoutingFor(observation: AgentObservation) {
  return objectValue(observation.payload.modelRouting)
}

function selectedCapabilityFor(observation: AgentObservation) {
  return objectValue(modelRoutingFor(observation).selectedCapability)
}

function unavailableCapabilitiesFor(observation: AgentObservation) {
  const value = modelRoutingFor(observation).recommendedUnavailable
  return Array.isArray(value) ? value.map(objectValue) : []
}

function qualityLabel(value: unknown) {
  return ({ PASS: '质量通过', WARN: '存在质量警告', REJECT: '输入不适用' } as Record<string, string>)[String(value)] || '尚未评估'
}

function mediaTypeLabel(detection: Record<string, unknown>) {
  if (detection.mediaTypeLabel) return String(detection.mediaTypeLabel)
  return ({
    PHOTOGRAPH: '摄影图像',
    ANIME_MANGA: '动漫或漫画',
    DIGITAL_ILLUSTRATION: '数字插画或绘画',
    VECTOR_CARTOON: '矢量卡通或扁平插画',
    ILLUSTRATION_CARTOON: '插画或卡通',
    THREE_D_RENDER: '3D 渲染或游戏画面',
    DOCUMENT_SCREENSHOT: '文档、网页或界面截图',
    DIAGRAM_GRAPHIC: '图表、海报或平面设计',
    UNKNOWN: '类型不明确',
  } as Record<string, string>)[String(detection.mediaType)] || '尚未识别'
}

function confidenceLabel(value: unknown) {
  return ({ HIGH: '较高', MEDIUM: '中等', LOW: '较低', UNAVAILABLE: '不可用' } as Record<string, string>)[String(value)] || String(value || '未知')
}

function textItems(value: unknown) {
  return Array.isArray(value) ? value.map((item) => String(item)).filter(Boolean) : []
}

function probabilityLabel(value: unknown) {
  return typeof value === 'number' ? `${(value * 100).toFixed(1)}%` : '未知'
}

function observationFileLabel(observation: AgentObservation) {
  return String(observation.payload.filename || observation.assetId || '综合观察')
}

function observationOutcomeLabel(observation: AgentObservation) {
  if (observation.evidenceType === 'AIGC_DETECTION') {
    const verdict = verdictLabel(String(fusionFor(observation).verdict || observation.payload.classification || 'INCONCLUSIVE'))
    return `${verdict} · AI 生成概率 ${probabilityLabel(observation.payload.syntheticProbability)}`
  }
  if (observation.evidenceType === 'CONTENT_PROVENANCE') {
    return `C2PA：${provenanceStatusLabel(observation.payload.status)}`
  }
  if (observation.evidenceType === 'MANIPULATION_LOCALIZATION') {
    if (observation.payload.status !== 'SUCCEEDED') return '篡改定位不可用'
    const result = String(observation.payload.classification) === 'SUSPICIOUS_MANIPULATION'
      ? '发现疑似局部篡改区域'
      : '未发现高响应篡改区域'
    return `${result} · 响应分数 ${probabilityLabel(observation.payload.tamperedProbability)}`
  }
  return '查看观察详情'
}

function groupAigcObservation(group: { observations: AgentObservation[] }) {
  return group.observations.find(item => item.evidenceType === 'AIGC_DETECTION')
}

function groupOutcomeLabel(group: { observations: AgentObservation[] }) {
  const observation = groupAigcObservation(group)
  return observation ? observationOutcomeLabel(observation) : '尚未取得主检测结果'
}

function groupFileLabel(group: { assetId: string; observations: AgentObservation[] }) {
  const filename = group.observations.map(item => item.payload.filename).find(Boolean)
  return String(filename || group.assetId)
}

function selectAdjacentAsset(offset: number) {
  const groups = mediaObservationGroups.value
  if (groups.length < 2) return
  const current = Math.max(0, groups.findIndex(group => group.assetId === activeMediaGroup.value?.assetId))
  activeAssetId.value = groups[(current + offset + groups.length) % groups.length].assetId
}

function isModelExecutionStep(type: string) {
  return [
    'MODEL_ROUTING_STARTED', 'PRIMARY_MODEL_STARTED', 'PRIMARY_MODEL_COMPLETED',
    'MANIPULATION_MODEL_STARTED', 'MANIPULATION_MODEL_COMPLETED', 'MANIPULATION_MODEL_UNAVAILABLE',
    'CROSS_DOMAIN_MODEL_STARTED', 'CROSS_DOMAIN_MODEL_COMPLETED', 'CROSS_DOMAIN_MODEL_UNAVAILABLE',
    'SECONDARY_CHECK_DECIDED', 'SECONDARY_MODEL_STARTED', 'SECONDARY_MODEL_COMPLETED',
    'SECONDARY_MODEL_UNAVAILABLE', 'EVIDENCE_FUSION_STARTED', 'EVIDENCE_FUSED',
    'RESULT_EXPLANATION_STARTED', 'RESULT_EXPLAINED',
  ].includes(type)
}

function stepMetricItems(step: AgentStep) {
  const output = step.output || {}
  const items: Array<{ label: string; value: string }> = []
  if (output.capabilityName) items.push({ label: '能力', value: String(output.capabilityName) })
  if (typeof output.syntheticProbability === 'number') items.push({ label: 'AI 生成概率', value: probabilityLabel(output.syntheticProbability) })
  if (typeof output.reconstructionDistance === 'number') items.push({ label: '重建距离', value: Number(output.reconstructionDistance).toFixed(4) })
  if (typeof output.succeededImageCount === 'number') items.push({ label: '定位完成', value: `${output.succeededImageCount} 张` })
  if (output.verdict) items.push({ label: '融合方向', value: verdictLabel(String(output.verdict)) })
  if (output.confidence) items.push({ label: '置信度', value: confidenceLabel(output.confidence) })
  return items
}

function releaseVisualizations() {
  Object.values(originalUrls.value).forEach((url) => URL.revokeObjectURL(url))
  Object.values(attentionUrls.value).forEach((url) => URL.revokeObjectURL(url))
  Object.values(localizationMaskUrls.value).forEach((url) => URL.revokeObjectURL(url))
  Object.values(localizationHeatmapUrls.value).forEach((url) => URL.revokeObjectURL(url))
  Object.values(localizationOverlayUrls.value).forEach((url) => URL.revokeObjectURL(url))
  originalUrls.value = {}
  attentionUrls.value = {}
  localizationMaskUrls.value = {}
  localizationHeatmapUrls.value = {}
  localizationOverlayUrls.value = {}
}

function localizationMode(observationId: string) {
  return localizationModes.value[observationId] || 'overlay'
}

function setLocalizationMode(observationId: string, mode: 'overlay' | 'heatmap' | 'mask') {
  localizationModes.value = { ...localizationModes.value, [observationId]: mode }
}

function localizationUrl(observationId: string) {
  const mode = localizationMode(observationId)
  if (mode === 'mask') return localizationMaskUrls.value[observationId]
  if (mode === 'heatmap') return localizationHeatmapUrls.value[observationId]
  return localizationOverlayUrls.value[observationId]
}

function localizationCaption(observationId: string) {
  return ({ overlay: '疑似篡改叠加图', heatmap: '像素响应热力图', mask: '阈值化定位掩码' } as const)[localizationMode(observationId)]
}

async function loadForensicVisualizations(taskDetails: AgentTaskDetails) {
  releaseVisualizations()
  const observations = taskDetails.observations.filter((item) =>
    ['AIGC_DETECTION', 'MANIPULATION_LOCALIZATION'].includes(item.evidenceType),
  )
  await Promise.all(observations.map(async (observation) => {
    if (!observation.assetId) return
    visualizationLoading.value = { ...visualizationLoading.value, [observation.id]: true }
    try {
      const original = await mediaApi.content(observation.assetId, auth.accessToken)
      originalUrls.value = { ...originalUrls.value, [observation.id]: URL.createObjectURL(original) }
      if (observation.evidenceType === 'AIGC_DETECTION') {
        const artifactId = String(objectValue(observation.payload.attentionArtifact).artifactId || '')
        if (artifactId) {
          const attention = await agentApi.artifact(taskDetails.task.id, observation.id, artifactId, auth.accessToken)
          attentionUrls.value = { ...attentionUrls.value, [observation.id]: URL.createObjectURL(attention) }
        }
      } else {
        const artifactTargets = [
          ['maskArtifact', localizationMaskUrls],
          ['heatmapArtifact', localizationHeatmapUrls],
          ['overlayArtifact', localizationOverlayUrls],
        ] as const
        await Promise.all(artifactTargets.map(async ([field, target]) => {
          const artifactId = String(objectValue(observation.payload[field]).artifactId || '')
          if (!artifactId) return
          const content = await agentApi.artifact(taskDetails.task.id, observation.id, artifactId, auth.accessToken)
          target.value = { ...target.value, [observation.id]: URL.createObjectURL(content) }
        }))
      }
    } catch {
      // The textual result remains usable when an artifact cannot be loaded.
    } finally {
      visualizationLoading.value = { ...visualizationLoading.value, [observation.id]: false }
    }
  }))
}

async function run() {
  if (!details.value) return
  mutating.value = true
  executionDrawerVisible.value = true
  try {
    details.value = await agentApi.run(taskId, details.value.task.version, auth.accessToken)
    ElMessage.success('Agent 任务已进入队列，可离开页面后稍后查看')
    await followExistingRun()
  } catch (error) {
    showError(error)
    await load()
  } finally {
    mutating.value = false
  }
}

async function focusVerification() {
  await nextTick()
  verificationSection.value?.scrollIntoView({ behavior: 'smooth', block: 'start' })
}

async function prepareVerification() {
  if (!details.value || !sourceCase.value) return
  confirming.value = true
  try {
    const prepared = await caseApi.prepareAgentConfirmation(
      sourceCase.value.id,
      details.value.task.id,
      sourceCase.value.version,
      auth.accessToken,
    )
    sourceCase.value = prepared.investigationCase
    await loadCaseContext(sourceCase.value.id)
    await focusVerification()
  } catch (error) {
    showError(error)
    await loadCaseContext(details.value.task.caseId)
  } finally {
    confirming.value = false
  }
}

async function confirmResult() {
  if (!details.value || !sourceCase.value || !pendingDecision.value) return
  confirming.value = true
  try {
    await caseApi.confirmResult(
      sourceCase.value.id,
      pendingDecision.value.id,
      {
        finalConclusion: verification.finalConclusion,
        reason: verification.reason,
        citedEvidenceIds: workflow.value.evidence.map((item) => item.id),
        includeAgentAssessment: verification.includeAgentAssessment,
        agentTaskId: verification.includeAgentAssessment ? details.value.task.id : null,
        taskVersion: pendingDecision.value.version,
        caseVersion: sourceCase.value.version,
      },
      auth.accessToken,
    )
    verification.reason = ''
    await loadCaseContext(sourceCase.value.id)
    ElMessage.success(verification.finalConclusion === 'INCONCLUSIVE' ? '已保存为暂时无法判断' : '人工核验结果已保存')
  } catch (error) {
    showError(error)
    await loadCaseContext(details.value.task.caseId)
  } finally {
    confirming.value = false
  }
}

async function cancel() {
  if (!details.value) return
  mutating.value = true
  try {
    details.value = await agentApi.cancel(taskId, details.value.task.version, auth.accessToken)
    ElMessage.success('Agent 任务已取消')
  } catch (error) {
    showError(error)
    await load()
  } finally {
    mutating.value = false
  }
}

function stepSummary(step: AgentStep) {
  const output = step.output || {}
  if (step.stepType === 'CONTEXT_ASSEMBLED') {
    return `已读取 ${output.assetCount ?? 0} 个媒体文件，案件状态为 ${output.caseStatus ?? '未知'}`
  }
  if (step.stepType === 'PLAN_GENERATED') return String(output.summary || '调查方案已经生成')
  if (step.stepType === 'PLAN_REQUESTED') return String(output.message || '规划器正在读取案件、媒体类型和取证知识')
  if (step.stepType === 'PLAN_VALIDATED') return `方案包含 ${output.selectedSkillCount ?? plannerSkills.value.length} 项取证能力，已通过安全策略校验`
  if (step.stepType === 'REPLAN_DECIDED' || step.stepType === 'REPLAN_FALLBACK') {
    const labels: Record<string, string> = { CONTINUE: '按既定方案继续', REPLAN: '调整剩余计划', STOP: '停止调用工具并汇总' }
    return `${labels[String(output.action)] || '已完成动态决策'}：${String(output.summary || '未提供说明')}`
  }
  if (step.stepType === 'REPLAN_LIMIT_REACHED') return '动态决策次数已达到安全上限，Harness 将完成当前已校验计划'
  if (step.stepType === 'REPLAN_REQUESTED') return String(output.message || '规划器正在根据最新观察判断下一步')
  if (step.stepType === 'SKILL_SELECTED') return String(output.reason || `选择“${skillMeta(step.skillCode).name}”`)
  if (step.stepType === 'TOOL_EXECUTION_STARTED') return String(output.message || `${skillMeta(step.skillCode).name}正在执行`)
  if (step.stepType === 'TOOL_CALLED') return `${skillMeta(step.skillCode).name}执行完成，结果已交给 Harness 处理`
  if (step.stepType === 'MODEL_ROUTING_STARTED') return String(output.message || '正在匹配当前媒体可用的检测能力')
  if (step.stepType === 'PRIMARY_MODEL_STARTED') return String(output.message || '主检测模型正在分析原始图片')
  if (step.stepType === 'PRIMARY_MODEL_COMPLETED') {
    return `${String(output.capabilityName || '主检测模型')}完成：${verdictLabel(String(output.classification || 'INCONCLUSIVE'))}，AI 生成概率 ${probabilityLabel(output.syntheticProbability)}`
  }
  if (step.stepType === 'CROSS_DOMAIN_MODEL_STARTED') return String(output.message || '通用模型正在执行跨域交叉复核')
  if (step.stepType === 'CROSS_DOMAIN_MODEL_COMPLETED') {
    return `通用模型交叉复核完成：${verdictLabel(String(output.classification || 'INCONCLUSIVE'))}，AI 生成概率 ${probabilityLabel(output.syntheticProbability)}`
  }
  if (step.stepType === 'CROSS_DOMAIN_MODEL_UNAVAILABLE') return String(output.message || '跨域交叉复核不可用，保留领域模型结果')
  if (step.stepType === 'SECONDARY_CHECK_DECIDED') return `${String(output.action) === 'RUN' ? '追加复核' : '跳过复核'}：${String(output.reason || output.message || '')}`
  if (step.stepType === 'SECONDARY_MODEL_STARTED') return String(output.message || '正在执行扩散重建复核')
  if (step.stepType === 'SECONDARY_MODEL_COMPLETED') {
    const distance = typeof output.reconstructionDistance === 'number' ? Number(output.reconstructionDistance).toFixed(4) : '未知'
    return `扩散重建距离 ${distance}；${output.calibrated ? '已应用校准阈值' : '尚未校准，仅作为辅助观察'}`
  }
  if (step.stepType === 'SECONDARY_MODEL_UNAVAILABLE') return String(output.message || '扩散复核不可用，继续使用主检测结果')
  if (step.stepType === 'EVIDENCE_FUSION_STARTED') return String(output.message || '正在融合多源检测信号')
  if (step.stepType === 'EVIDENCE_FUSED') return `${String(output.message || '信号融合完成')}：${verdictLabel(String(output.verdict || 'INCONCLUSIVE'))}`
  if (step.stepType === 'RESULT_EXPLANATION_STARTED') return String(output.message || 'LLM 正在生成中文结果说明')
  if (step.stepType === 'RESULT_EXPLAINED') return localizeSystemText(String(output.summary || output.message || '结果解释已完成'))
  if (step.stepType === 'KNOWLEDGE_RETRIEVAL_RECORDED') return `已保存 ${output.citationCount ?? 0} 条可追溯知识引用`
  if (step.stepType === 'OBSERVATION_RECORDED') return String(output.summary || '工具结果已保存为 Agent 候选观察')
  if (step.stepType === 'CHECKPOINT_SAVED') return `已保存第 ${output.checkpointVersion ?? ''} 个任务恢复点`
  if (step.stepType === 'CONCLUSION_SYNTHESIZED') return localizeSystemText(String(output.summary || '已汇总当前调查结果与局限'))
  if (step.stepType === 'TASK_COMPLETED') return '自动取证执行完毕，等待调查员核验结果'
  if (step.stepType === 'TASK_FAILED') return String(output.message || '任务执行失败')
  return stepMeta(step.stepType).description
}

function showError(error: unknown) {
  ElMessage.error(error instanceof ApiRequestError ? error.message : 'Agent 任务请求失败')
}

watch(() => details.value?.steps.length, async () => {
  if (!executionDrawerVisible.value) return
  await nextTick()
  if (liveTrace.value) liveTrace.value.scrollTop = liveTrace.value.scrollHeight
})

watch(() => details.value?.task.status, (status) => {
  if (status && ['COMPLETED', 'FAILED', 'CANCELLED'].includes(status)) {
    executionDrawerVisible.value = false
  }
})

onMounted(async () => {
  await load()
  if (route.query.autorun === '1' && canOperate.value) {
    await router.replace({ path: route.path, query: {} })
    await run()
  } else if (details.value?.task.status === 'RUNNING') {
    void followExistingRun()
  }
})
onBeforeUnmount(() => {
  disposed = true
  progressAbort?.abort()
  releaseVisualizations()
})
</script>

<template>
  <main class="page-shell agent-task-page" v-loading="loading">
    <template v-if="details">
      <nav class="agent-context-path" aria-label="当前位置">
        <button type="button" @click="router.push(`${routePrefix()}/history`)">检测记录</button>
        <span>/</span>
        <strong>{{ sourceCase?.caseNumber || '案件详情' }}</strong>
        <span>/</span>
        <span>检测结果</span>
      </nav>
      <header class="page-header split-header agent-task-header">
        <div>
          <p class="eyebrow">DETECTION RESULT</p>
          <h1>{{ jointAssetCount > 1 ? '多图联合真实性分析结果' : '图片真实性分析结果' }}</h1>
          <p>{{ sourceCase?.title || '图片真实性检测' }} · {{ formatDate(details.task.createdAt) }}</p>
        </div>
        <div class="status-stack">
          <span class="status-pill" :data-status="details.task.status">{{ agentStatusLabel(details.task.status) }}</span>
          <span>{{ sourceCase?.status === 'COMPLETED' ? '人工核验已完成' : '等待你确认结果' }}</span>
        </div>
      </header>

      <section class="panel agent-case-context">
        <div>
          <span>本次检测对象</span>
          <strong>{{ sourceCase ? `${sourceCase.caseNumber} · ${sourceCase.title}` : details.task.caseId }}</strong>
          <p v-if="sourceCase">当前状态：{{ caseStatusLabel(sourceCase.status) }}。Agent 给出初步判断后，由你在本页底部完成最终核验。</p>
          <p v-else>Agent 给出的结果需要由你最终确认。</p>
        </div>
        <el-button v-if="details.task.status === 'COMPLETED'" type="primary" plain @click="focusVerification">
          前往人工核验
        </el-button>
      </section>

      <section class="panel investigation-progress" aria-label="Agent 调查进度">
        <div class="section-heading">
          <div><h2>本次分析过程</h2><p>Agent 负责分析并给出初步判断，最终结论由你确认。</p></div>
        </div>
        <ol class="agent-phase-list">
          <li v-for="(phase, index) in investigationPhases" :key="phase.label" :class="phase.state">
            <span>{{ phase.done ? '✓' : index + 1 }}</span>
            <div><strong>{{ phase.label }}</strong><small>{{ phase.description }}</small></div>
          </li>
        </ol>
      </section>

      <el-alert
        v-if="details.task.status === 'FAILED'"
        :title="details.task.failureMessage || 'Agent 任务运行失败'"
        type="error"
        :closable="false"
        show-icon
      />

      <section v-if="canOperate" class="panel agent-actions">
        <div><h2>开始自动取证</h2><p>模型会制定方案，Harness 校验后才允许受控工具执行。</p></div>
        <div><el-button type="primary" :loading="mutating" @click="run">运行 Agent</el-button><el-button :disabled="mutating" @click="cancel">取消</el-button></div>
      </section>

      <section v-if="details.task.status === 'RUNNING'" class="agent-live-banner">
        <div><strong>Agent 正在执行</strong><span>{{ currentModelState }}</span></div>
        <el-button plain @click="executionDrawerVisible = true">查看实时过程</el-button>
      </section>

      <section class="metric-grid agent-metrics readable-metrics">
        <article class="panel accent-panel"><span>分析模型</span><strong>{{ plannerName }}</strong></article>
        <article class="panel"><span>已执行能力</span><strong>{{ completedToolSteps.length }} 项</strong></article>
        <article class="panel"><span>可见模型阶段</span><strong>{{ modelExecutionSteps.length }} 个</strong></article>
        <article class="panel"><span>参考知识</span><strong>{{ citationCount }} 条</strong></article>
      </section>

      <section v-if="jointAssetCount > 1" class="panel joint-analysis-panel">
        <div class="section-heading">
          <div><h2>{{ jointAssetCount }} 张图片的联合分析</h2><p>先逐图检测，再比较图片间的感知相似关系与来源凭证；各图片仍保留独立模型结果。</p></div>
          <span class="source-badge">{{ multiImageAnalysis.comparisonCount || 0 }} 组比较</span>
        </div>
        <div class="joint-result-grid">
          <article v-for="item in perAssetResults" :key="String(item.assetId)">
            <span>{{ item.filename }}</span>
            <strong>{{ verdictLabel(String(item.classification || 'INCONCLUSIVE')) }}</strong>
            <small>AI 生成概率 {{ probabilityLabel(item.syntheticProbability) }} · {{ provenanceStatusLabel(item.provenanceStatus) }}</small>
          </article>
        </div>
        <div class="joint-relation-summary">
          <strong>跨图关系</strong>
          <p v-if="relatedPairs.length">发现 {{ relatedPairs.length }} 组相同或近重复图片；点击下方感知相似度观察可查看距离。</p>
          <p v-else>没有发现相同或近重复图片。相似度只描述视觉关系，不能单独证明共同来源。</p>
        </div>
      </section>

      <section v-if="Object.keys(details.task.conclusion).length" class="panel agent-conclusion result-panel">
        <div class="section-heading">
          <div><h2>Agent 综合初步判断</h2><p>生成内容鉴别模型提供检测方向，CLIP 负责类型路由，LLM 综合现有证据；该结果需要由你核验。</p></div>
          <span class="result-badge">{{ conclusionVerdict }}</span>
        </div>
        <div class="agent-assessment-meta">
          <span>{{ conclusionSource }}</span>
          <span>{{ conclusionConfidence }}</span>
          <span>需要人工复核</span>
          <span>排队 {{ seconds(performance.queueWaitMillis) }}</span>
          <span>执行 {{ seconds(performance.executionDurationMillis) }}</span>
          <span>缓存命中 {{ performance.cacheHitCount ?? 0 }} 次</span>
        </div>
        <p class="conclusion-summary">{{ conclusionSummary || '本次任务没有生成文字结论。' }}</p>
        <div v-if="retrievalInfluenceSummary" class="conclusion-limitations retrieval-impact-panel">
          <strong>检索来源如何影响判断</strong>
          <p>{{ retrievalInfluenceSummary }}</p>
          <ul v-if="academicSources.length">
            <li v-for="source in academicSources" :key="String(source.url || source.title)">
              <a :href="String(source.url)" target="_blank" rel="noreferrer">{{ source.title }}</a>
              <small>{{ source.venue || '学术来源' }} · {{ source.qualityReason }}</small>
            </li>
          </ul>
        </div>
        <div v-if="conclusionMissingEvidence.length" class="conclusion-limitations">
          <strong>尚待补充的专用能力</strong>
          <ul><li v-for="item in conclusionMissingEvidence" :key="item">{{ item }}</li></ul>
        </div>
        <div v-if="conclusionLimitations.length" class="conclusion-limitations">
          <strong>人工复核时需要注意</strong>
          <ul><li v-for="item in conclusionLimitations" :key="item">{{ item }}</li></ul>
        </div>
      </section>

      <section v-if="Object.keys(details.task.conclusion).length" class="panel evidence-visibility-panel">
        <div class="section-heading"><div><h2>联网检索与内容溯源</h2><p>明确展示两项能力是否真正执行，以及执行后取得了什么结果。</p></div></div>
        <div class="evidence-visibility-grid">
          <article>
            <span>实时学术检索</span>
            <strong>{{ webRetrievalStatusLabel }}</strong>
            <p>提供方：{{ webRetrievalProvider }}。专业检测仅用这些资料解释方法、适用范围与局限，不把论文当作当前图片真假的直接证据。</p>
          </article>
          <article>
            <span>C2PA 内容溯源</span>
            <strong v-if="provenanceItems.length">已校验 {{ provenanceItems.length }} 个文件</strong>
            <strong v-else>本次没有形成溯源结果</strong>
            <p v-if="provenanceItems.length">
              有效 {{ provenanceCounts.VERIFIED || 0 }} · 异常 {{ provenanceCounts.INVALID || 0 }} ·
              无凭证 {{ provenanceCounts.NOT_FOUND || 0 }} · 不可用/未配置 {{ (Number(provenanceCounts.UNAVAILABLE) || 0) + (Number(provenanceCounts.NOT_CONFIGURED) || 0) }}
            </p>
            <p v-else>C2PA 校验应在文件完整性检查阶段执行；旧任务不会自动补跑新增的溯源能力。</p>
          </article>
        </div>
      </section>

      <section v-if="planStep" class="panel agent-plan">
        <div class="section-heading">
          <div>
            <h2>模型制定的调查方案</h2>
            <p>{{ isLlmPlanner ? '本地多模态模型结合图片、案件信息和知识库选择下列能力。' : '当前使用固定方案完成流程回归。' }}</p>
          </div>
          <span class="source-badge">{{ plannerName }}</span>
        </div>
        <article class="llm-response">
          <span>方案说明</span>
          <p>{{ plannerSummary || '规划器未返回文字说明。' }}</p>
        </article>
        <ol class="planner-skill-list">
          <li v-for="(skill, index) in plannerSkills" :key="`${skill.skillCode}-${index}`">
            <span>{{ index + 1 }}</span>
            <div>
              <strong>{{ skillMeta(skill.skillCode).name }}</strong>
              <p>{{ skill.reason || skillMeta(skill.skillCode).description }}</p>
                  <small>能力版本：v{{ skill.skillVersion }}</small>
            </div>
          </li>
        </ol>
        <div v-if="completedToolSteps.length" class="execution-decision-summary">
          <div>
            <strong>已实际执行的分析能力</strong>
            <p>下列工具均已完成调用并保存结果，动态调整发生在每项工具执行之后。</p>
            <ol class="executed-skill-list">
              <li v-for="step in completedToolSteps" :key="step.id">
                <span>✓</span>{{ skillMeta(step.skillCode).name }}
              </li>
            </ol>
          </div>
          <div v-if="planAdjustmentSummaries.length" class="plan-adjustment-summary">
            <strong>计划调整摘要</strong>
            <ul><li v-for="summary in planAdjustmentSummaries" :key="summary">{{ summary }}</li></ul>
          </div>
        </div>
      </section>

      <section class="detail-grid agent-result-grid">
        <article class="panel">
          <div class="section-heading"><div><h2>媒体取证观察</h2><p>工具产生的客观事实与模型信号，最终结果由你完成人工核验。</p></div></div>
          <section v-if="jointAssetCount > 1 && activeMediaGroup" class="multi-media-browser">
            <header class="multi-media-browser-header">
              <div>
                <span>逐图分析</span>
                <strong>{{ mediaObservationGroups.findIndex(group => group.assetId === activeMediaGroup?.assetId) + 1 }} / {{ mediaObservationGroups.length }}</strong>
              </div>
              <nav aria-label="切换当前分析图片">
                <button type="button" aria-label="上一张图片" @click="selectAdjacentAsset(-1)">←</button>
                <button type="button" aria-label="下一张图片" @click="selectAdjacentAsset(1)">→</button>
              </nav>
            </header>

            <div class="media-switcher" role="tablist" aria-label="多图分析结果">
              <button
                v-for="(group, index) in mediaObservationGroups"
                :key="group.assetId"
                type="button"
                role="tab"
                :aria-selected="group.assetId === activeMediaGroup?.assetId"
                :class="{ active: group.assetId === activeMediaGroup?.assetId }"
                @click="activeAssetId = group.assetId"
              >
                <span>{{ String(index + 1).padStart(2, '0') }}</span>
                <div>
                  <strong>{{ groupFileLabel(group) }}</strong>
                  <small>{{ groupOutcomeLabel(group) }}</small>
                </div>
              </button>
            </div>

            <div v-if="activeAigcObservation || activeLocalizationObservation" class="active-media-analysis">
              <section class="active-media-visuals" aria-label="当前图片与篡改定位可视化">
                <figure>
                  <img
                    v-if="originalUrls[(activeLocalizationObservation || activeAigcObservation)!.id]"
                    :src="originalUrls[(activeLocalizationObservation || activeAigcObservation)!.id]"
                    alt="当前接受分析的原始媒体"
                  />
                  <div v-else class="visual-placeholder">原图暂不可用</div>
                  <figcaption>原始图片</figcaption>
                </figure>
                <figure v-if="activeLocalizationObservation" class="analysis-visual localization-figure">
                  <div class="visual-mode-switch" aria-label="切换篡改定位图层">
                    <button
                      v-for="mode in [{ key: 'overlay', label: '叠加图' }, { key: 'heatmap', label: '热力图' }, { key: 'mask', label: '掩码' }]"
                      :key="mode.key"
                      type="button"
                      :class="{ active: localizationMode(activeLocalizationObservation.id) === mode.key }"
                      @click="setLocalizationMode(activeLocalizationObservation.id, mode.key as 'overlay' | 'heatmap' | 'mask')"
                    >{{ mode.label }}</button>
                  </div>
                  <img v-if="localizationUrl(activeLocalizationObservation.id)" :src="localizationUrl(activeLocalizationObservation.id)" alt="当前图片的疑似篡改定位结果" />
                  <div v-else class="visual-placeholder">{{ activeLocalizationObservation.payload.status === 'SUCCEEDED' ? '定位图暂不可用' : 'Mesorch 当前未配置或执行失败' }}</div>
                  <figcaption>{{ localizationCaption(activeLocalizationObservation.id) }}（像素响应仅供人工核验）</figcaption>
                </figure>
                <figure v-else class="analysis-visual">
                  <img v-if="activeAigcObservation && attentionUrls[activeAigcObservation.id]" :src="attentionUrls[activeAigcObservation.id]" alt="当前图片的生成内容模型可视化" />
                  <div v-else class="visual-placeholder">当前模型没有生成区域可视化</div>
                  <figcaption>模型关注区域（不等同于精确生成位置）</figcaption>
                </figure>
              </section>

              <aside v-if="activeAigcObservation" class="active-media-summary">
                <span>当前图片初步判断</span>
                <strong>{{ verdictLabel(String(fusionFor(activeAigcObservation).verdict || activeAigcObservation.payload.classification || 'INCONCLUSIVE')) }}</strong>
                <div class="active-probability">
                  <small>AI 生成概率</small>
                  <b>{{ probabilityLabel(activeAigcObservation.payload.syntheticProbability) }}</b>
                </div>
                <p>{{ explanationFor(activeAigcObservation).summary || activeAigcObservation.summary }}</p>
                <dl>
                  <div><dt>媒体类型</dt><dd>{{ activeMediaTypeObservation ? mediaTypeLabel(activeMediaTypeObservation.payload) : mediaTypeLabel(mediaTypeContextFor(activeAigcObservation)) }}</dd></div>
                  <div><dt>模型置信度</dt><dd>{{ confidenceLabel(fusionFor(activeAigcObservation).confidence) }}</dd></div>
                  <div><dt>跨域交叉复核</dt><dd>{{ crossDomainLabel(activeAigcObservation) }}</dd></div>
                  <div><dt>C2PA 溯源</dt><dd>{{ activeProvenanceObservation ? provenanceStatusLabel(activeProvenanceObservation.payload.status) : '未执行' }}</dd></div>
                  <div v-if="activeLocalizationObservation"><dt>局部篡改响应</dt><dd>{{ observationOutcomeLabel(activeLocalizationObservation) }}</dd></div>
                </dl>
              </aside>
              <aside v-else-if="activeLocalizationObservation" class="active-media-summary">
                <span>局部篡改定位</span>
                <strong>{{ observationOutcomeLabel(activeLocalizationObservation) }}</strong>
                <p>{{ activeLocalizationObservation.summary }}</p>
                <dl>
                  <div><dt>高响应面积</dt><dd>{{ probabilityLabel(activeLocalizationObservation.payload.tamperedAreaRatio) }}</dd></div>
                  <div><dt>候选区域</dt><dd>{{ Array.isArray(activeLocalizationObservation.payload.regions) ? activeLocalizationObservation.payload.regions.length : 0 }} 个</dd></div>
                  <div><dt>阈值校准</dt><dd>{{ activeLocalizationObservation.payload.calibrated ? '已校准' : '尚未校准' }}</dd></div>
                </dl>
              </aside>
            </div>

            <div v-else class="active-media-missing">当前图片尚未取得模型检测或篡改定位结果，可查看下方辅助观察。</div>

            <section class="active-supporting-evidence">
              <article v-if="activeMediaTypeObservation">
                <span>内容类型与模型路由</span>
                <strong>{{ mediaTypeLabel(activeMediaTypeObservation.payload) }}</strong>
                <p>{{ activeMediaTypeObservation.summary }}</p>
              </article>
              <article v-if="activeIntegrityObservation">
                <span>文件完整性</span>
                <strong>已完成检查</strong>
                <p>{{ activeIntegrityObservation.summary }}</p>
              </article>
              <article v-if="activeProvenanceObservation">
                <span>C2PA 来源凭证</span>
                <strong>{{ provenanceStatusLabel(activeProvenanceObservation.payload.status) }}</strong>
                <p>{{ activeProvenanceObservation.summary }}</p>
              </article>
            </section>

            <details v-if="activeAigcObservation" class="active-model-details">
              <summary>查看当前图片的模型链路与解释依据</summary>
              <ol class="evidence-pipeline" aria-label="当前图片模型分析链路">
                <li v-for="stage in analysisStagesFor(activeAigcObservation)" :key="stage.index" :class="stage.state">
                  <span>{{ stage.index }}</span>
                  <div><small>{{ stage.label }}</small><strong>{{ stage.value }}</strong><p>{{ stage.detail }}</p></div>
                </li>
              </ol>
              <div v-if="textItems(fusionFor(activeAigcObservation).reasons).length" class="fusion-reasons">
                <strong>融合判断依据</strong>
                <ul><li v-for="reason in textItems(fusionFor(activeAigcObservation).reasons)" :key="reason">{{ reason }}</li></ul>
              </div>
              <div v-if="textItems(explanationFor(activeAigcObservation).counterSignals).length" class="explanation-list counter">
                <strong>需要谨慎看待的现象</strong>
                <ul><li v-for="signal in textItems(explanationFor(activeAigcObservation).counterSignals)" :key="signal">{{ signal }}</li></ul>
              </div>
            </details>
          </section>
          <details
            v-else
            v-for="item in details.observations"
            :key="item.id"
            class="observation-card readable-card observation-disclosure"
            :open="jointAssetCount <= 1 || item.id === firstDetailedObservationId"
          >
            <summary class="observation-summary">
              <span class="observation-summary-copy">
                <small>{{ evidenceTypeLabel(item.evidenceType) }} · {{ observationFileLabel(item) }}</small>
                <strong>{{ observationOutcomeLabel(item) }}</strong>
              </span>
              <span class="observation-summary-action">查看详情</span>
            </summary>
            <div class="observation-expanded">
              <p>{{ item.summary }}</p>
            <template v-if="item.evidenceType === 'AIGC_DETECTION'">
              <section class="aigc-result-brief">
                <div class="aigc-result-verdict">
                  <span>Agent 初步方向</span>
                  <strong>{{ verdictLabel(String(fusionFor(item).verdict || 'INCONCLUSIVE')) }}</strong>
                  <small>融合置信度 {{ confidenceLabel(fusionFor(item).confidence) }} · 最终结果由你确认</small>
                </div>
                <p>{{ explanationFor(item).summary || '系统已完成模型检测，正在整理可解释说明。' }}</p>
              </section>
              <ol class="evidence-pipeline" aria-label="本次模型分析链路">
                <li v-for="stage in analysisStagesFor(item)" :key="stage.index" :class="stage.state">
                  <span>{{ stage.index }}</span>
                  <div><small>{{ stage.label }}</small><strong>{{ stage.value }}</strong><p>{{ stage.detail }}</p></div>
                </li>
              </ol>
              <details class="result-technical-details">
                <summary>查看模型指标、路由依据与质量信息</summary>
                <div v-if="Object.keys(modelRoutingFor(item)).length" class="model-routing-card">
                <div class="model-routing-heading">
                  <div>
                    <span>本次模型路由</span>
                    <strong>{{ selectedCapabilityFor(item).displayName || '通用生成内容鉴别' }}</strong>
                  </div>
                  <span :class="{ degraded: modelRoutingFor(item).degraded }">
                    {{ modelRoutingFor(item).degraded ? '降级执行' : '直接匹配' }}
                  </span>
                </div>
                <p>{{ modelRoutingFor(item).reason }}</p>
                <dl>
                  <div><dt>识别媒体类型</dt><dd>{{ mediaTypeLabel(mediaTypeContextFor(item)) }}</dd></div>
                  <div><dt>能力版本</dt><dd>{{ selectedCapabilityFor(item).version || '未记录' }}</dd></div>
                  <div><dt>标准输出</dt><dd>概率、判断、置信度、质量与可视化</dd></div>
                </dl>
                <div v-if="unavailableCapabilitiesFor(item).length" class="model-capability-gap">
                  <strong>更匹配但尚未接入的能力</strong>
                  <ul>
                    <li v-for="capability in unavailableCapabilitiesFor(item)" :key="String(capability.code)">
                      {{ capability.displayName }}：{{ capability.description }}
                    </li>
                  </ul>
                </div>
              </div>
              <div class="fusion-result" :data-verdict="fusionFor(item).verdict">
                <div>
                  <span>多证据融合结果</span>
                  <strong>{{ verdictLabel(String(fusionFor(item).verdict || 'INCONCLUSIVE')) }}</strong>
                  <small>融合置信度：{{ confidenceLabel(fusionFor(item).confidence) }}</small>
                </div>
                <span>{{ fusionFor(item).decisionReady ? '已形成 Agent 初步方向' : '仍需补充证据' }}</span>
              </div>
              <div class="aide-score-row">
                <div><span>AI 生成概率</span><strong>{{ probabilityLabel(item.payload.syntheticProbability) }}</strong></div>
                <div><span>鉴别模型结果</span><strong>{{ verdictLabel(String(item.payload.classification || 'INCONCLUSIVE')) }}</strong></div>
                <div><span>图像质量门控</span><strong>{{ qualityLabel(qualityFor(item).status) }}</strong></div>
                <div><span>规划前媒体类型</span><strong>{{ mediaTypeLabel(mediaTypeContextFor(item)) }}</strong></div>
                <div><span>类型相对匹配度</span><strong>{{ probabilityLabel(mediaTypeContextFor(item).mediaTypeScore) }}</strong></div>
                <div><span>分析文件</span><strong>{{ item.payload.filename || item.assetId }}</strong></div>
              </div>
              <p v-if="mediaTypeContextFor(item).provider === 'OPENAI_CLIP'" class="attention-notice">
                CLIP 只在规划前识别媒体类型，供 LLM 选择取证策略并解释生成内容鉴别模型的适用边界；鉴别模型本身仍只接收原图，CLIP 类型不是 AIGC 生成概率。
              </p>
              <div v-if="secondaryVerificationFor(item).status && secondaryVerificationFor(item).status !== 'SKIPPED'" class="fusion-reasons">
                <strong>扩散重建复核</strong>
                <p v-if="secondaryVerificationFor(item).status === 'SUCCEEDED'">
                  已取得感知重建距离 {{ secondaryVerificationFor(item).reconstructionDistance }}；
                  {{ secondaryVerificationFor(item).calibrated ? '已按校准阈值解释。' : '尚未校准阈值，因此只作为辅助观察，不作为生成概率。' }}
                </p>
                <p v-else>本次复核不可用，主检测结果仍会保留并交由你核验。</p>
              </div>
              <div v-if="crossDomainVerificationFor(item).status && crossDomainVerificationFor(item).status !== 'NOT_REQUIRED'" class="fusion-reasons">
                <strong>通用模型交叉复核</strong>
                <p v-if="crossDomainVerificationFor(item).status === 'SUCCEEDED'">
                  {{ crossDomainLabel(item) }}。该模型独立读取原图，用于核对动漫专用模型方向；两者冲突时系统不会强行给出结论。
                </p>
                <p v-else>{{ crossDomainVerificationFor(item).reason || '本次交叉复核不可用，领域模型结果已降低置信度。' }}</p>
              </div>
              <div v-if="textItems(fusionFor(item).reasons).length" class="fusion-reasons">
                <strong>系统为什么形成这个融合结果</strong>
                <ul><li v-for="reason in textItems(fusionFor(item).reasons)" :key="reason">{{ reason }}</li></ul>
              </div>
              <div v-if="qualityIssues(item).length" class="quality-issues">
                <strong>图像质量问题</strong>
                <ul><li v-for="issue in qualityIssues(item)" :key="String(objectValue(issue).code)">{{ objectValue(issue).message }}</li></ul>
              </div>
              </details>
              <div class="aide-visual-grid" v-loading="visualizationLoading[item.id]">
                <figure>
                  <img v-if="originalUrls[item.id]" :src="originalUrls[item.id]" alt="接受生成内容鉴别分析的原始媒体" />
                  <div v-else class="visual-placeholder">原图暂不可用</div>
                  <figcaption>原始媒体</figcaption>
                </figure>
                <figure>
                  <img v-if="attentionUrls[item.id]" :src="attentionUrls[item.id]" alt="鉴别模型语义注意力叠加图" />
                  <div v-else class="visual-placeholder">质量门控未通过或注意力图暂不可用</div>
                  <figcaption>{{ isLocalizationResult(item) ? '疑似生成区域定位图' : '鉴别模型注意力叠加图' }}</figcaption>
                </figure>
              </div>
              <p class="attention-notice">
                {{ isLocalizationResult(item)
                  ? '高亮区域来自动漫/漫画专用模型的像素级响应，只表示疑似生成区域，仍需人工结合原图判断。'
                  : '颜色越暖表示该区域对当前分类的语义贡献越高；它不是精确的 AI 生成位置或篡改位置。' }}
              </p>
              <div class="aide-explanation">
                <div class="card-title-row">
                  <strong>中文结果解释</strong>
                  <span>{{ explanationFor(item).source === 'LOCAL_QWEN3_VL' ? 'Qwen3-VL' : '规则降级' }}</span>
                </div>
                <p>{{ explanationFor(item).summary || '本条结果没有生成解释文本。' }}</p>
                <div v-if="textItems(explanationFor(item).supportingSignals).length" class="explanation-list">
                  <strong>支持当前结果的现象</strong>
                  <ul><li v-for="signal in textItems(explanationFor(item).supportingSignals)" :key="signal">{{ signal }}</li></ul>
                </div>
                <div v-if="textItems(explanationFor(item).counterSignals).length" class="explanation-list counter">
                  <strong>需要谨慎看待的现象</strong>
                  <ul><li v-for="signal in textItems(explanationFor(item).counterSignals)" :key="signal">{{ signal }}</li></ul>
                </div>
              </div>
            </template>
            <section v-else-if="item.evidenceType === 'MANIPULATION_LOCALIZATION'" class="localization-result">
              <header>
                <div>
                  <span>像素级局部篡改定位</span>
                  <strong>{{ observationOutcomeLabel(item) }}</strong>
                </div>
                <span :class="['localization-status', String(item.payload.status).toLowerCase()]">
                  {{ item.payload.status === 'SUCCEEDED' ? '定位完成' : '能力不可用' }}
                </span>
              </header>
              <div class="localization-metrics">
                <div><span>疑似篡改响应</span><strong>{{ probabilityLabel(item.payload.tamperedProbability) }}</strong></div>
                <div><span>高响应面积</span><strong>{{ probabilityLabel(item.payload.tamperedAreaRatio) }}</strong></div>
                <div><span>候选区域</span><strong>{{ Array.isArray(item.payload.regions) ? item.payload.regions.length : 0 }} 个</strong></div>
                <div><span>阈值状态</span><strong>{{ item.payload.calibrated ? '已校准' : '未校准' }}</strong></div>
              </div>
              <div class="localization-viewport" v-loading="visualizationLoading[item.id]">
                <figure>
                  <img v-if="originalUrls[item.id]" :src="originalUrls[item.id]" alt="接受篡改定位分析的原始媒体" />
                  <div v-else class="visual-placeholder">原图暂不可用</div>
                  <figcaption>原始媒体</figcaption>
                </figure>
                <figure class="localization-figure">
                  <div class="visual-mode-switch" aria-label="切换篡改定位图层">
                    <button
                      v-for="mode in [{ key: 'overlay', label: '叠加图' }, { key: 'heatmap', label: '热力图' }, { key: 'mask', label: '掩码' }]"
                      :key="mode.key"
                      type="button"
                      :class="{ active: localizationMode(item.id) === mode.key }"
                      @click="setLocalizationMode(item.id, mode.key as 'overlay' | 'heatmap' | 'mask')"
                    >{{ mode.label }}</button>
                  </div>
                  <img v-if="localizationUrl(item.id)" :src="localizationUrl(item.id)" alt="疑似篡改区域定位结果" />
                  <div v-else class="visual-placeholder">{{ item.payload.status === 'SUCCEEDED' ? '定位图暂不可用' : 'Mesorch 当前未配置或执行失败' }}</div>
                  <figcaption>{{ localizationCaption(item.id) }}</figcaption>
                </figure>
              </div>
              <p class="attention-notice">掩码与热力图表示模型响应，不代表已经证明发生篡改；压缩、缩放和复杂纹理可能造成误报。</p>
              <div v-if="textItems(item.payload.limitations).length" class="quality-issues">
                <strong>能力边界</strong>
                <ul><li v-for="limitation in textItems(item.payload.limitations)" :key="limitation">{{ limitation }}</li></ul>
              </div>
            </section>
            <section v-else-if="item.evidenceType === 'CONTENT_PROVENANCE'" class="provenance-card" :data-status="item.payload.status">
              <div>
                <span>C2PA 校验状态</span>
                <strong>{{ provenanceStatusLabel(item.payload.status) }}</strong>
                <small>{{ item.payload.filename || item.assetId }}</small>
              </div>
              <dl>
                <div><dt>是否含凭证</dt><dd>{{ item.payload.credentialPresent ? '是' : '否' }}</dd></div>
                <div><dt>清单数量</dt><dd>{{ item.payload.manifestCount ?? 0 }}</dd></div>
                <div><dt>声明/签署工具</dt><dd>{{ item.payload.signer || '未记录' }}</dd></div>
              </dl>
              <p>C2PA 能验证签名、文件绑定与声明的编辑历史；未发现凭证不是负面证据，有效凭证也不等于画面内容必然真实。</p>
            </section>
              <dl v-else-if="payloadFields(item.payload).length" class="fact-grid">
                <template v-for="field in payloadFields(item.payload)" :key="field.label">
                  <dt>{{ field.label }}</dt><dd>{{ field.value }}</dd>
                </template>
              </dl>
            </div>
          </details>
          <el-empty v-if="!details.observations.length" description="本次任务还没有产生媒体观察" />
        </article>

        <article class="panel">
          <div class="section-heading"><div><h2>取证知识依据</h2><p>分别展示已发布知识库与实时网络学术检索；它们为调查方案提供参考，不会直接成为媒体真假证据。</p></div></div>
          <section class="live-retrieval-section">
            <div class="live-retrieval-heading">
              <div><span>实时网络 · {{ webRetrievalProvider }}</span><strong>{{ webRetrievalStatusLabel }}</strong></div>
              <small>执行状态会保留，即使返回 0 条也不会隐藏</small>
            </div>
            <article v-for="source in academicSources" :key="String(source.url || source.title)" class="academic-source-card">
              <a :href="String(source.url)" target="_blank" rel="noreferrer">{{ source.title }}</a>
              <small>{{ source.venue || '学术来源' }}<template v-if="source.publicationYear"> · {{ source.publicationYear }}</template></small>
              <p>{{ source.snippet }}</p>
              <span>{{ source.qualityReason || source.qualityTier }}</span>
            </article>
          </section>
          <div v-for="retrieval in details.knowledgeRetrievals" :key="retrieval.id" class="knowledge-group">
            <p class="knowledge-query">已发布知识库获得 {{ retrieval.citations.length }} 条可引用知识片段。</p>
            <article v-for="citation in retrieval.citations" :key="citation.id" class="citation-card">
              <strong>{{ citation.documentTitle }}</strong>
              <p>{{ citation.quote }}</p>
              <small>文档版本 {{ citation.documentVersion }} · 引用 {{ citation.citationOrder }}</small>
            </article>
          </div>
          <el-empty v-if="!details.knowledgeRetrievals.length && !academicSources.length" description="本次任务没有检索到知识依据" />
        </article>
      </section>

      <details class="panel process-details">
        <summary>查看执行过程（{{ details.steps.length }} 个步骤）</summary>
        <p>这里用于解释 Agent 为什么得到上述结果，不展示底层 JSON。</p>
        <ol class="trace-list readable-trace">
          <li v-for="step in details.steps" :key="step.id">
            <span class="trace-index">{{ step.sequenceNumber }}</span>
            <div>
              <div class="trace-title-row"><strong>{{ stepMeta(step.stepType).name }}</strong><span>{{ step.status === 'SUCCEEDED' ? '已完成' : '失败' }}</span></div>
              <p>{{ stepSummary(step) }}</p>
              <small>{{ formatDate(step.createdAt) }}<template v-if="step.skillCode"> · {{ skillMeta(step.skillCode).name }}</template></small>
            </div>
          </li>
        </ol>
        <p class="checkpoint-summary">系统已保存 {{ details.checkpoints.length }} 个恢复检查点，任务中断后可据此定位执行进度。</p>
      </details>

      <section v-if="details.task.status === 'COMPLETED'" id="human-verification" ref="verificationSection" class="panel human-verification-panel">
        <div class="section-heading">
          <div>
            <p class="eyebrow">FINAL CHECK</p>
            <h2>人工核验</h2>
            <p>结合原图与 Agent 初步判断，确认你认可的最终结果。该选择不会反向修改模型输出。</p>
          </div>
        </div>

        <div v-if="sourceCase?.status === 'COMPLETED' && latestDecision" class="verification-complete">
          <span>最终结果已保存</span>
          <strong>{{ evidenceConclusionLabel(latestDecision.finalConclusion || 'INCONCLUSIVE') }}</strong>
          <p>{{ latestDecision.decisionReason }}</p>
        </div>

        <div v-else-if="canConfirmResult && pendingDecision" class="verification-form">
          <el-radio-group v-model="verification.finalConclusion" class="verdict-radio-group">
            <el-radio value="LIKELY_SYNTHETIC" border><strong>AI 生成</strong><small>我认为该图片主要由生成式 AI 产生</small></el-radio>
            <el-radio value="LIKELY_AUTHENTIC" border><strong>非 AI 生成</strong><small>我认为该图片更可能由人工制作或真实拍摄</small></el-radio>
            <el-radio value="INCONCLUSIVE" border><strong>暂时无法判断</strong><small>现有分析不足以支持明确结论</small></el-radio>
          </el-radio-group>
          <div class="agent-reference-option">
            <div><strong>在最终记录中引用 Agent 的结论与理由</strong><small>关闭后只保存你的选择和补充说明</small></div>
            <el-switch v-model="verification.includeAgentAssessment" />
          </div>
          <el-input v-model="verification.reason" type="textarea" :rows="3" maxlength="2000" placeholder="补充说明（可选），例如你认同或不认同 Agent 的原因" />
          <el-button type="primary" :loading="confirming" @click="confirmResult">保存人工核验结果</el-button>
        </div>

        <div v-else-if="canPrepareVerification" class="verification-start">
          <p>分析已经完成，可以开始确认最终结果。</p>
          <el-button type="primary" :loading="confirming" @click="prepareVerification">开始人工核验</el-button>
        </div>

        <el-alert v-else-if="sourceCase?.status === 'WAITING_CONFIRMATION'" title="当前账号不能确认这条检测结果" type="warning" :closable="false" />
      </section>
    </template>

    <el-drawer
      v-model="executionDrawerVisible"
      class="agent-execution-drawer"
      size="min(540px, 92vw)"
      append-to-body
      :modal="false"
      :lock-scroll="false"
      title="Agent 调查执行台"
    >
      <div v-if="details" class="agent-live-console">
        <section class="agent-current-mind">
          <span>当前执行阶段 · {{ currentStageName }}</span>
          <p>{{ currentModelState }}</p>
          <article v-if="latestModelOutput" class="agent-stage-output">
            <small>最近一次模型阶段输出</small>
            <blockquote>{{ latestModelOutput }}</blockquote>
          </article>
          <small>内容随服务端事件更新，展示可审计的模型回复摘要，不包含隐藏思维链或系统提示词。</small>
        </section>
        <div class="agent-live-meta">
          <span class="status-pill" :data-status="details.task.status">{{ agentStatusLabel(details.task.status) }}</span>
          <span>{{ details.steps.length }} 个已记录事件</span>
          <span>{{ details.task.remainingStepBudget }} 步预算剩余</span>
        </div>
        <ol ref="liveTrace" class="agent-live-trace">
          <li
            v-for="(step, index) in details.steps"
            :key="step.id"
            :class="{ active: details.task.status === 'RUNNING' && index === details.steps.length - 1, model: isModelExecutionStep(step.stepType) }"
          >
            <span class="live-trace-marker"></span>
            <div>
              <div><strong>{{ stepMeta(step.stepType).name }}</strong><small>{{ formatDate(step.createdAt) }}</small></div>
              <p>{{ stepSummary(step) }}</p>
              <dl v-if="stepMetricItems(step).length" class="live-event-metrics">
                <div v-for="metric in stepMetricItems(step)" :key="metric.label"><dt>{{ metric.label }}</dt><dd>{{ metric.value }}</dd></div>
              </dl>
              <small v-if="step.skillCode">{{ skillMeta(step.skillCode).name }}</small>
            </div>
          </li>
        </ol>
        <p v-if="!details.steps.length" class="agent-live-empty">任务启动后，真实执行事件会依次显示在这里。</p>
      </div>
    </el-drawer>
  </main>
</template>
