<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { agentApi } from '../api/agents'
import { caseApi } from '../api/cases'
import { mediaApi } from '../api/media'
import { ApiRequestError } from '../api/http'
import { useAuthStore } from '../stores/auth'
import type { AgentObservation, AgentStep, AgentTaskDetails } from '../types/agent'
import type { InvestigationCase } from '../types/business'
import { formatDate } from '../utils/format'
import {
  agentStatusLabel,
  caseStatusLabel,
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
const details = ref<AgentTaskDetails | null>(null)
const sourceCase = ref<InvestigationCase | null>(null)
const loading = ref(false)
const mutating = ref(false)
const originalUrls = ref<Record<string, string>>({})
const attentionUrls = ref<Record<string, string>>({})
const visualizationLoading = ref<Record<string, boolean>>({})

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
const conclusionVerdict = computed(() => verdictLabel(String(details.value?.task.conclusion.verdict || '尚未生成')))
const conclusionSummary = computed(() => localizeSystemText(String(details.value?.task.conclusion.summary || '')))
const conclusionLimitations = computed(() => {
  const value = details.value?.task.conclusion.limitations
  return Array.isArray(value) ? value.map((item) => localizeSystemText(String(item))) : []
})
const citationCount = computed(() => details.value?.knowledgeRetrievals.reduce(
  (total, retrieval) => total + retrieval.citations.length,
  0,
) ?? 0)
const canOperate = computed(() => Boolean(
  details.value?.task.status === 'PENDING' &&
    details.value.task.createdBy === auth.user?.id &&
    auth.hasPermission('agent:run'),
))
const investigationPhases = computed(() => {
  const steps = new Set(details.value?.steps.map((step) => step.stepType) ?? [])
  const phases = [
    { label: '读取案件', description: '整理案件与媒体上下文', done: steps.has('CONTEXT_ASSEMBLED') },
    { label: '识别类型并制定方案', description: 'CLIP 提供媒体类型，模型据此选择受控取证能力', done: steps.has('PLAN_VALIDATED') },
    { label: '执行取证', description: '工具产生观察与知识依据', done: steps.has('CONCLUSION_SYNTHESIZED') },
    { label: '提交人工核验', description: '候选观察已交给调查员，尚未成为正式证据', done: details.value?.task.status === 'COMPLETED' },
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
    try {
      sourceCase.value = (await caseApi.get(taskDetails.task.caseId, auth.accessToken)).investigationCase
    } catch {
      sourceCase.value = null
    }
  } catch (error) {
    showError(error)
  } finally {
    loading.value = false
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
  return ({ MEDIUM: '中等', LOW: '较低', UNAVAILABLE: '不可用' } as Record<string, string>)[String(value)] || String(value || '未知')
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
  try {
    details.value = await agentApi.run(taskId, details.value.task.version, auth.accessToken)
    ElMessage.success('Agent 取证任务已完成，请核验候选观察')
  } catch (error) {
    showError(error)
    await load()
  } finally {
    mutating.value = false
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
  if (step.stepType === 'PLAN_VALIDATED') return `方案包含 ${output.selectedSkillCount ?? plannerSkills.value.length} 项取证能力，已通过安全策略校验`
  if (step.stepType === 'SKILL_SELECTED') return String(output.reason || `选择“${skillMeta(step.skillCode).name}”`)
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

onMounted(load)
onBeforeUnmount(releaseVisualizations)
</script>

<template>
  <main class="page-shell agent-task-page" v-loading="loading">
    <template v-if="details">
      <nav class="agent-context-path" aria-label="当前位置">
        <button type="button" @click="router.push(`/cases/${details.task.caseId}`)">调查案件</button>
        <span>/</span>
        <strong>{{ sourceCase?.caseNumber || '案件详情' }}</strong>
        <span>/</span>
        <span>Agent 取证结果</span>
      </nav>
      <header class="page-header split-header agent-task-header">
        <div>
          <p class="eyebrow">自动取证任务</p>
          <h1>{{ details.task.goal }}</h1>
          <p>创建于 {{ formatDate(details.task.createdAt) }} · 任务编号 {{ details.task.id }}</p>
        </div>
        <div class="status-stack">
          <span class="status-pill" :data-status="details.task.status">{{ agentStatusLabel(details.task.status) }}</span>
          <span>结果需要人工核验</span>
        </div>
      </header>

      <section class="panel agent-case-context">
        <div>
          <span>本次 Agent 分析所属案件</span>
          <strong>{{ sourceCase ? `${sourceCase.caseNumber} · ${sourceCase.title}` : details.task.caseId }}</strong>
          <p v-if="sourceCase">当前案件状态：{{ caseStatusLabel(sourceCase.status) }}。返回案件后可核验候选观察并决定是否纳入正式证据。</p>
          <p v-else>返回所属案件后可核验本次 Agent 产生的候选观察。</p>
        </div>
        <el-button type="primary" plain @click="router.push(`/cases/${details.task.caseId}`)">
          返回案件核验结果
        </el-button>
      </section>

      <section class="panel investigation-progress" aria-label="Agent 调查进度">
        <div class="section-heading">
          <div><h2>这次任务正在做什么</h2><p>从读取案件到提交候选观察，Agent 的输出不会直接成为最终审核结论。</p></div>
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

      <section class="metric-grid agent-metrics readable-metrics">
        <article class="panel accent-panel"><span>调查规划器</span><strong>{{ plannerName }}</strong></article>
        <article class="panel"><span>计划执行</span><strong>{{ plannerSkills.length }} 项能力</strong></article>
        <article class="panel"><span>候选观察</span><strong>{{ details.observations.length }} 条</strong></article>
        <article class="panel"><span>知识引用</span><strong>{{ citationCount }} 条</strong></article>
      </section>

      <section v-if="Object.keys(details.task.conclusion).length" class="panel agent-conclusion result-panel">
        <div class="section-heading">
          <div><h2>Agent 阶段性结论</h2><p>这是自动取证结果摘要，不是审核员的最终裁决。</p></div>
          <span class="result-badge">{{ conclusionVerdict }}</span>
        </div>
        <p class="conclusion-summary">{{ conclusionSummary || '本次任务没有生成文字结论。' }}</p>
        <div v-if="conclusionLimitations.length" class="conclusion-limitations">
          <strong>为什么现在还不能直接下结论</strong>
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
              <small>技术标识：{{ skill.skillCode }} · v{{ skill.skillVersion }}</small>
            </div>
          </li>
        </ol>
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
                <span>{{ fusionFor(item).decisionReady ? '具备阶段性判断条件' : '仍需补充证据' }}</span>
              </div>
              <div class="aide-score-row">
                <div><span>AI 生成概率</span><strong>{{ probabilityLabel(item.payload.syntheticProbability) }}</strong></div>
                <div><span>AIDE 单模型结果</span><strong>{{ verdictLabel(String(item.payload.classification || 'INCONCLUSIVE')) }}</strong></div>
                <div><span>图像质量门控</span><strong>{{ qualityLabel(qualityFor(item).status) }}</strong></div>
                <div><span>规划前媒体类型</span><strong>{{ mediaTypeLabel(mediaTypeContextFor(item)) }}</strong></div>
                <div><span>类型相对匹配度</span><strong>{{ probabilityLabel(mediaTypeContextFor(item).mediaTypeScore) }}</strong></div>
                <div><span>分析文件</span><strong>{{ item.payload.filename || item.assetId }}</strong></div>
              </div>
              <p v-if="mediaTypeContextFor(item).provider === 'OPENAI_CLIP'" class="attention-notice">
                CLIP 只在规划前识别媒体类型，供 LLM 选择取证策略和解释 AIDE 的适用边界；AIDE 本身仍只接收原图，CLIP 类型不是 AIGC 生成概率。
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
                  <img v-if="originalUrls[item.id]" :src="originalUrls[item.id]" alt="接受 AIDE 分析的原始媒体" />
                  <div v-else class="visual-placeholder">原图暂不可用</div>
                  <figcaption>原始媒体</figcaption>
                </figure>
                <figure>
                  <img v-if="attentionUrls[item.id]" :src="attentionUrls[item.id]" alt="AIDE 语义分支注意力叠加图" />
                  <div v-else class="visual-placeholder">质量门控未通过或注意力图暂不可用</div>
                  <figcaption>AIDE 语义注意力叠加图</figcaption>
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
    </template>
  </main>
</template>
