<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { agentApi } from '../api/agents'
import { ApiRequestError } from '../api/http'
import { useAuthStore } from '../stores/auth'
import type { AgentStep, AgentTaskDetails } from '../types/agent'
import { formatDate } from '../utils/format'
import {
  agentStatusLabel,
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
const loading = ref(false)
const mutating = ref(false)

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
    { label: '制定方案', description: '模型选择受控取证能力', done: steps.has('PLAN_VALIDATED') },
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
    details.value = await agentApi.get(taskId, auth.accessToken)
  } catch (error) {
    showError(error)
  } finally {
    loading.value = false
  }
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
</script>

<template>
  <main class="page-shell agent-task-page" v-loading="loading">
    <template v-if="details">
      <button class="text-back" type="button" @click="router.push(`/cases/${details.task.caseId}`)">← 返回案件</button>
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
            <dl v-if="payloadFields(item.payload).length" class="fact-grid">
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
