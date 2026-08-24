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
const originalUrls = ref<Record<string, string>>({})
const attentionUrls = ref<Record<string, string>>({})
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
  ['PLAN_GENERATED', 'REPLAN_DECIDED', 'REPLAN_FALLBACK', 'CONCLUSION_SYNTHESIZED'].includes(step.stepType) &&
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
const conclusionSource = computed(() => details.value?.task.conclusion.synthesisSource === 'LOCAL_QWEN3_VL'
  ? '本地 Qwen 综合研判'
  : '确定性降级研判')
const conclusionLimitations = computed(() => {
  const value = details.value?.task.conclusion.limitations
  return Array.isArray(value) ? value.map((item) => localizeSystemText(String(item))) : []
})
const conclusionMissingEvidence = computed(() => textItems(details.value?.task.conclusion.missingEvidence))
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
    await loadAigcVisualizations(taskDetails)
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
  if (!details.value || details.value.task.status !== 'RUNNING') return
  mutating.value = true
  executionDrawerVisible.value = true
  try {
    while (!disposed && details.value?.task.status === 'RUNNING') {
      await wait(900)
      await refreshProgress()
    }
    if (details.value) {
      await loadAigcVisualizations(details.value)
      await loadCaseContext(details.value.task.caseId)
    }
  } catch (error) {
    if (!disposed) showError(error)
  } finally {
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

function qualityLabel(value: unknown) {
  return ({ PASS: '质量通过', WARN: '存在质量警告', REJECT: '输入不适用' } as Record<string, string>)[String(value)] || '尚未评估'
}

function mediaTypeLabel(detection: Record<string, unknown>) {
  if (detection.mediaTypeLabel) return String(detection.mediaTypeLabel)
  return ({
    PHOTOGRAPH: '摄影图像',
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

function releaseVisualizations() {
  Object.values(originalUrls.value).forEach((url) => URL.revokeObjectURL(url))
  Object.values(attentionUrls.value).forEach((url) => URL.revokeObjectURL(url))
  originalUrls.value = {}
  attentionUrls.value = {}
}

async function loadAigcVisualizations(taskDetails: AgentTaskDetails) {
  releaseVisualizations()
  const observations = taskDetails.observations.filter((item) => item.evidenceType === 'AIGC_DETECTION')
  await Promise.all(observations.map(async (observation) => {
    const artifact = objectValue(observation.payload.attentionArtifact)
    const artifactId = String(artifact.artifactId || '')
    if (!observation.assetId) return
    visualizationLoading.value = { ...visualizationLoading.value, [observation.id]: true }
    try {
      const original = await mediaApi.content(observation.assetId, auth.accessToken)
      originalUrls.value = { ...originalUrls.value, [observation.id]: URL.createObjectURL(original) }
      if (artifactId) {
        const attention = await agentApi.artifact(taskDetails.task.id, observation.id, artifactId, auth.accessToken)
        attentionUrls.value = { ...attentionUrls.value, [observation.id]: URL.createObjectURL(attention) }
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
  let finished = false
  let response: AgentTaskDetails | null = null
  let requestError: unknown = null
  try {
    const request = agentApi.run(taskId, details.value.task.version, auth.accessToken)
      .then((value) => { response = value })
      .catch((error) => { requestError = error })
      .finally(() => { finished = true })
    while (!disposed && !finished) {
      await wait(800)
      if (!finished) {
        try { await refreshProgress() } catch { /* The running request remains authoritative. */ }
      }
    }
    await request
    if (requestError) throw requestError
    const completedResponse = response as AgentTaskDetails | null
    if (completedResponse) {
      details.value = completedResponse
      await loadAigcVisualizations(completedResponse)
      await loadCaseContext(completedResponse.task.caseId)
    }
    ElMessage.success('图片分析已完成，请在页面底部完成人工核验')
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
    const labels: Record<string, string> = { CONTINUE: '继续原计划', REPLAN: '调整剩余计划', STOP: '停止调用工具并汇总' }
    return `${labels[String(output.action)] || '已完成动态决策'}：${String(output.summary || '未提供说明')}`
  }
  if (step.stepType === 'REPLAN_LIMIT_REACHED') return '动态决策次数已达到安全上限，Harness 将完成当前已校验计划'
  if (step.stepType === 'REPLAN_REQUESTED') return String(output.message || '规划器正在根据最新观察判断下一步')
  if (step.stepType === 'SKILL_SELECTED') return String(output.reason || `选择“${skillMeta(step.skillCode).name}”`)
  if (step.stepType === 'TOOL_EXECUTION_STARTED') return String(output.message || `${skillMeta(step.skillCode).name}正在执行`)
  if (step.stepType === 'TOOL_CALLED') return `${skillMeta(step.skillCode).name}执行完成，结果已交给 Harness 处理`
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
          <h1>图片真实性分析结果</h1>
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
        <article class="panel"><span>分析结果</span><strong>{{ details.observations.length }} 项</strong></article>
        <article class="panel"><span>参考知识</span><strong>{{ citationCount }} 条</strong></article>
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
        </div>
        <p class="conclusion-summary">{{ conclusionSummary || '本次任务没有生成文字结论。' }}</p>
        <div v-if="conclusionMissingEvidence.length" class="conclusion-limitations">
          <strong>尚待补充的专用能力</strong>
          <ul><li v-for="item in conclusionMissingEvidence" :key="item">{{ item }}</li></ul>
        </div>
        <div v-if="conclusionLimitations.length" class="conclusion-limitations">
          <strong>人工复核时需要注意</strong>
          <ul><li v-for="item in conclusionLimitations" :key="item">{{ item }}</li></ul>
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
          <div class="section-heading"><div><h2>媒体取证观察</h2><p>工具产生的客观事实，需由调查员确认后才能纳入正式证据。</p></div></div>
          <div v-for="item in details.observations" :key="item.id" class="observation-card readable-card">
            <div class="card-title-row"><strong>{{ evidenceTypeLabel(item.evidenceType) }}</strong><span>候选观察</span></div>
            <p>{{ item.summary }}</p>
            <template v-if="item.evidenceType === 'AIGC_DETECTION'">
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
              <div v-if="textItems(fusionFor(item).reasons).length" class="fusion-reasons">
                <strong>系统为什么形成这个融合结果</strong>
                <ul><li v-for="reason in textItems(fusionFor(item).reasons)" :key="reason">{{ reason }}</li></ul>
              </div>
              <div v-if="qualityIssues(item).length" class="quality-issues">
                <strong>图像质量问题</strong>
                <ul><li v-for="issue in qualityIssues(item)" :key="String(objectValue(issue).code)">{{ objectValue(issue).message }}</li></ul>
              </div>
              <div class="aide-visual-grid" v-loading="visualizationLoading[item.id]">
                <figure>
                  <img v-if="originalUrls[item.id]" :src="originalUrls[item.id]" alt="接受生成内容鉴别分析的原始媒体" />
                  <div v-else class="visual-placeholder">原图暂不可用</div>
                  <figcaption>原始媒体</figcaption>
                </figure>
                <figure>
                  <img v-if="attentionUrls[item.id]" :src="attentionUrls[item.id]" alt="鉴别模型语义注意力叠加图" />
                  <div v-else class="visual-placeholder">质量门控未通过或注意力图暂不可用</div>
                  <figcaption>鉴别模型注意力叠加图</figcaption>
                </figure>
              </div>
              <p class="attention-notice">颜色越暖表示该区域对当前分类的语义贡献越高；它不是精确的 AI 生成位置或篡改位置。</p>
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
            <dl v-else-if="payloadFields(item.payload).length" class="fact-grid">
              <template v-for="field in payloadFields(item.payload)" :key="field.label">
                <dt>{{ field.label }}</dt><dd>{{ field.value }}</dd>
              </template>
            </dl>
          </div>
          <el-empty v-if="!details.observations.length" description="本次任务还没有产生媒体观察" />
        </article>

        <article class="panel">
          <div class="section-heading"><div><h2>取证知识依据</h2><p>RAG 为调查方案提供参考，不会直接成为媒体真假证据。</p></div></div>
          <div v-for="retrieval in details.knowledgeRetrievals" :key="retrieval.id" class="knowledge-group">
            <p class="knowledge-query">本次 RAG 检索获得 {{ retrieval.citations.length }} 条可引用知识片段。</p>
            <article v-for="citation in retrieval.citations" :key="citation.id" class="citation-card">
              <strong>{{ citation.documentTitle }}</strong>
              <p>{{ citation.quote }}</p>
              <small>文档版本 {{ citation.documentVersion }} · 引用 {{ citation.citationOrder }}</small>
            </article>
          </div>
          <el-empty v-if="!details.knowledgeRetrievals.length" description="本次任务没有检索到知识依据" />
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
            :class="{ active: details.task.status === 'RUNNING' && index === details.steps.length - 1 }"
          >
            <span class="live-trace-marker"></span>
            <div>
              <div><strong>{{ stepMeta(step.stepType).name }}</strong><small>{{ formatDate(step.createdAt) }}</small></div>
              <p>{{ stepSummary(step) }}</p>
              <small v-if="step.skillCode">{{ skillMeta(step.skillCode).name }}</small>
            </div>
          </li>
        </ol>
        <p v-if="!details.steps.length" class="agent-live-empty">任务启动后，真实执行事件会依次显示在这里。</p>
      </div>
    </el-drawer>
  </main>
</template>
