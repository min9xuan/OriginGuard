<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { agentApi } from '../api/agents'
import { caseApi } from '../api/cases'
import { ApiRequestError } from '../api/http'
import { mediaApi } from '../api/media'
import { useAuthStore } from '../stores/auth'
import type {
  AssignableUser,
  AuditEntry,
  CaseDetails,
  CasePriority,
  CaseStatus,
  CaseWorkflow,
  EvidenceConclusion,
  EvidenceConfidence,
  MediaAsset,
  ReviewStatus,
} from '../types/business'
import { nextInvestigatorTransition } from '../utils/case-workflow'
import { formatBytes, formatDate } from '../utils/format'
import {
  auditActionLabel,
  auditSummary,
  caseStatusLabel,
  evidenceConfidenceLabel,
  evidenceConclusionLabel,
  evidenceTypeLabel,
  payloadFields,
} from '../utils/presentation'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const caseId = route.params.caseId as string
const details = ref<CaseDetails | null>(null)
const workflow = ref<CaseWorkflow>({ evidence: [], reviewTasks: [], agentEvidenceCandidates: [] })
const allAssets = ref<MediaAsset[]>([])
const assignees = ref<AssignableUser[]>([])
const audit = ref<AuditEntry[]>([])
const loading = ref(false)
const saving = ref(false)
const agentRunning = ref(false)
const selectedAssetId = ref('')
const edit = reactive({ title: '', description: '', priority: 'NORMAL' as CasePriority })
const assignment = reactive({ investigatorId: '', reviewerId: '' })
const evidence = reactive({
  assetId: '',
  title: '',
  observation: '',
  conclusion: 'INCONCLUSIVE' as EvidenceConclusion,
  confidence: 'MEDIUM' as EvidenceConfidence,
})
const review = reactive({
  decision: 'APPROVED' as ReviewStatus,
  reason: '',
  citedEvidenceIds: [] as string[],
})
const previewUrl = ref('')
const previewName = ref('')
const previewVisible = ref(false)
const previewLoadingId = ref('')

const current = computed(() => details.value?.investigationCase ?? null)
const canOperate = computed(() => {
  const item = current.value
  return Boolean(
    item &&
      auth.hasPermission('case:update') &&
      (item.createdBy === auth.user?.id || item.assignedInvestigatorId === auth.user?.id),
  )
})
const canEdit = computed(() => Boolean(
  canOperate.value && current.value && ['DRAFT', 'REJECTED'].includes(current.value.status),
))
const canAddEvidence = computed(() => Boolean(
  current.value &&
    current.value.status === 'INVESTIGATING' &&
    current.value.assignedInvestigatorId === auth.user?.id &&
    auth.hasPermission('case:update'),
))
const canRunAgent = computed(() => Boolean(
  current.value &&
    current.value.status === 'INVESTIGATING' &&
    current.value.assignedInvestigatorId === auth.user?.id &&
    auth.hasPermission('agent:run'),
))
const canAssign = computed(() => Boolean(
  current.value &&
    auth.hasPermission('case:assign') &&
    ['DRAFT', 'READY', 'INVESTIGATING', 'REJECTED'].includes(current.value.status),
))
const investigatorOptions = computed(() => assignees.value.filter((user) => user.role === 'INVESTIGATOR'))
const reviewerOptions = computed(() => assignees.value.filter((user) => user.role === 'REVIEWER'))
const availableAssets = computed(() => {
  const linked = new Set(details.value?.assets.map((asset) => asset.id) ?? [])
  return allAssets.value.filter((asset) => !linked.has(asset.id))
})
const pendingReview = computed(() => workflow.value.reviewTasks.find((task) => task.status === 'PENDING') ?? null)
const canReview = computed(() => Boolean(
  current.value?.status === 'WAITING_REVIEW' &&
    pendingReview.value?.reviewerId === auth.user?.id &&
    (auth.hasPermission('review:approve') || auth.hasPermission('review:reject')),
))
const availableAgentObservations = computed(() => workflow.value.agentEvidenceCandidates.filter(
  (candidate) => !candidate.promotedEvidenceId,
))
const nextTransition = computed<{ target: CaseStatus; label: string } | null>(() => {
  const status = current.value?.status
  return status
    ? nextInvestigatorTransition(status, canOperate.value, auth.hasPermission('case:submit'))
    : null
})
const caseStages = [
  { label: '材料准备', description: '完善案件信息并关联待调查媒体' },
  { label: '等待调查', description: '确认人员职责并正式开始调查' },
  { label: '调查取证', description: 'Agent 提供观察，调查员核验并形成证据' },
  { label: '独立审核', description: '审核员依据正式证据作出决定' },
  { label: '形成结果', description: '案件确认通过或退回补充调查' },
]
const currentStageIndex = computed(() => {
  const indexByStatus: Record<CaseStatus, number> = {
    DRAFT: 0,
    READY: 1,
    INVESTIGATING: 2,
    WAITING_REVIEW: 3,
    CONFIRMED: 4,
    REJECTED: 4,
    FAILED: 2,
    ARCHIVED: 4,
  }
  return current.value ? indexByStatus[current.value.status] : 0
})

function stageState(index: number) {
  if (index < currentStageIndex.value) return 'completed'
  if (index === currentStageIndex.value) return 'current'
  return 'upcoming'
}

async function load() {
  loading.value = true
  try {
    const requests = [
      caseApi.get(caseId, auth.accessToken),
      mediaApi.list(auth.accessToken),
      caseApi.audit(caseId, auth.accessToken),
      caseApi.workflow(caseId, auth.accessToken),
    ] as const
    const [caseResult, assetsResult, auditResult, workflowResult] = await Promise.all(requests)
    setDetails(caseResult)
    allAssets.value = assetsResult
    audit.value = auditResult
    workflow.value = workflowResult
    assignees.value = await caseApi.assignees(auth.accessToken)
  } catch (error) {
    showError(error)
  } finally {
    loading.value = false
  }
}

function setDetails(value: CaseDetails) {
  details.value = value
  edit.title = value.investigationCase.title
  edit.description = value.investigationCase.description
  edit.priority = value.investigationCase.priority
  assignment.investigatorId = value.investigationCase.assignedInvestigatorId ?? ''
  assignment.reviewerId = value.investigationCase.assignedReviewerId ?? ''
}

async function save() {
  if (!current.value) return
  await mutate(async () => {
    await caseApi.update(caseId, { ...edit, version: current.value!.version }, auth.accessToken)
    ElMessage.success('案件信息已更新')
  })
}

async function linkAsset() {
  if (!current.value || !selectedAssetId.value) return
  await mutate(async () => {
    await caseApi.linkAsset(caseId, selectedAssetId.value, current.value!.version, auth.accessToken)
    selectedAssetId.value = ''
    ElMessage.success('媒体已关联到案件')
  })
}

async function assignCase() {
  if (!current.value || !assignment.investigatorId || !assignment.reviewerId) return
  await mutate(async () => {
    await caseApi.assign(caseId, { ...assignment, version: current.value!.version }, auth.accessToken)
    ElMessage.success('调查员与独立审核员已分派')
  })
}

async function addEvidence() {
  if (!current.value || !evidence.assetId || !evidence.title.trim() || !evidence.observation.trim()) return
  await mutate(async () => {
    await caseApi.addEvidence(caseId, { ...evidence, version: current.value!.version }, auth.accessToken)
    evidence.title = ''
    evidence.observation = ''
    ElMessage.success('人工证据已记录')
  })
}

async function promoteAgentObservation(observationId: string) {
  if (!current.value) return
  await mutate(async () => {
    await caseApi.promoteAgentObservation(
      caseId,
      observationId,
      current.value!.version,
      auth.accessToken,
    )
    ElMessage.success('Agent Observation 已纳入正式案件证据')
  })
}

async function transition() {
  if (!current.value || !nextTransition.value) return
  await mutate(async () => {
    await caseApi.transition(
      caseId,
      nextTransition.value!.target,
      current.value!.version,
      auth.accessToken,
    )
    ElMessage.success(`案件状态已更新为 ${nextTransition.value!.target}`)
  })
}

async function startAgent() {
  if (!current.value) return
  agentRunning.value = true
  try {
    const created = await agentApi.create(
      caseId,
      '运行文件完整性、图片元数据、感知相似度分析并检索取证知识',
      9,
      auth.accessToken,
    )
    const completed = await agentApi.run(created.task.id, created.task.version, auth.accessToken)
    ElMessage.success('媒体分析与 RAG 检索 Agent 已完成，可查看完整 Trace')
    await router.push(`/agent-tasks/${completed.task.id}`)
  } catch (error) {
    showError(error)
  } finally {
    agentRunning.value = false
  }
}

async function previewAsset(asset: Pick<MediaAsset, 'id' | 'originalFilename' | 'storageStatus'>) {
  if (asset.storageStatus !== 'STORED') return
  previewLoadingId.value = asset.id
  try {
    if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
    previewUrl.value = URL.createObjectURL(await mediaApi.content(asset.id, auth.accessToken))
    previewName.value = asset.originalFilename
    previewVisible.value = true
  } catch (error) {
    showError(error)
  } finally {
    previewLoadingId.value = ''
  }
}

async function decideReview() {
  if (!current.value || !pendingReview.value) return
  await mutate(async () => {
    await caseApi.decideReview(
      caseId,
      pendingReview.value!.id,
      {
        decision: review.decision,
        reason: review.reason,
        citedEvidenceIds: review.citedEvidenceIds,
        taskVersion: pendingReview.value!.version,
        caseVersion: current.value!.version,
      },
      auth.accessToken,
    )
    review.reason = ''
    review.citedEvidenceIds = []
    ElMessage.success(review.decision === 'APPROVED' ? '审核已通过' : '案件已驳回')
  })
}

async function mutate(action: () => Promise<void>) {
  saving.value = true
  try {
    await action()
    await load()
  } catch (error) {
    showError(error)
    if (error instanceof ApiRequestError && ['CASE_VERSION_CONFLICT', 'REVIEW_VERSION_CONFLICT'].includes(error.code)) {
      await load()
    }
  } finally {
    saving.value = false
  }
}

function assigneeName(id: string | null) {
  if (!id) return '未分派'
  const user = assignees.value.find((item) => item.id === id)
  return user ? `${user.displayName}（${user.username}）` : id
}

function assetName(id: string) {
  return details.value?.assets.find((asset) => asset.id === id)?.originalFilename ?? id
}

function evidenceTitle(id: string) {
  return workflow.value.evidence.find((item) => item.id === id)?.title ?? id
}

function showError(error: unknown) {
  ElMessage.error(error instanceof ApiRequestError ? error.message : '请求失败，请稍后重试')
}

onMounted(load)
onBeforeUnmount(() => {
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
})
</script>

<template>
  <main class="page-shell" v-loading="loading">
    <template v-if="current && details">
      <header class="page-header split-header">
        <div>
          <p class="eyebrow">{{ current.caseNumber }}</p>
          <h1>{{ current.title }}</h1>
          <p>{{ current.description || '暂无调查说明' }}</p>
        </div>
        <div class="status-stack">
          <el-tag size="large" effect="dark">{{ caseStatusLabel(current.status) }}</el-tag>
          <span>version {{ current.version }}</span>
        </div>
      </header>

      <section class="detail-grid">
        <article class="panel">
          <div class="section-heading"><div><h2>案件信息</h2><p>基础字段仅在草稿或驳回状态可编辑</p></div></div>
          <el-form label-position="top">
            <el-form-item label="标题"><el-input v-model="edit.title" :disabled="!canEdit" /></el-form-item>
            <el-form-item label="说明"><el-input v-model="edit.description" type="textarea" :rows="4" :disabled="!canEdit" /></el-form-item>
            <el-form-item label="优先级">
              <el-select v-model="edit.priority" :disabled="!canEdit">
                <el-option label="低" value="LOW" /><el-option label="普通" value="NORMAL" />
                <el-option label="高" value="HIGH" /><el-option label="紧急" value="CRITICAL" />
              </el-select>
            </el-form-item>
            <el-button v-if="canEdit" type="primary" :loading="saving" @click="save">保存修改</el-button>
          </el-form>
        </article>

        <article class="panel workflow-panel">
          <div class="workflow-heading">
            <div><span>案件处理流程</span><strong>{{ caseStatusLabel(current.status) }}</strong></div>
            <small>Agent 只辅助取证，不会自动推进案件或代替人工审核。</small>
          </div>
          <ol class="case-stage-list">
            <li v-for="(stage, index) in caseStages" :key="stage.label" :class="stageState(index)">
              <span class="stage-marker">{{ index + 1 }}</span>
              <div><strong>{{ stage.label }}</strong><small>{{ stage.description }}</small></div>
            </li>
          </ol>
          <div v-if="nextTransition" class="workflow-action primary-action">
            <div><strong>案件流转</strong><small>完成当前阶段后，将案件推进到下一处理环节。</small></div>
            <el-button type="primary" :loading="saving" :disabled="agentRunning" @click="transition">
              {{ nextTransition.label }}
            </el-button>
          </div>
          <div v-if="canRunAgent" class="workflow-action agent-action">
            <div><strong>Agent 辅助取证</strong><small>生成候选观察，仍需调查员确认后才能成为正式证据。</small></div>
            <el-button type="success" :loading="agentRunning" :disabled="saving" @click="startAgent">
              {{ agentRunning ? 'Agent 正在取证' : '运行取证 Agent' }}
            </el-button>
          </div>
          <small v-if="!nextTransition && !canRunAgent" class="workflow-empty">当前身份或案件状态没有可执行操作。</small>
        </article>
      </section>

      <section class="panel assignment-panel">
        <div class="section-heading">
          <div><h2>职责分派</h2><p>调查与审核必须由不同人员承担</p></div>
        </div>
        <div v-if="canAssign" class="assignment-form">
          <el-select v-model="assignment.investigatorId" placeholder="选择调查员">
            <el-option v-for="user in investigatorOptions" :key="user.id" :label="`${user.displayName}（${user.username}）`" :value="user.id" />
          </el-select>
          <el-select v-model="assignment.reviewerId" placeholder="选择审核员">
            <el-option v-for="user in reviewerOptions" :key="user.id" :label="`${user.displayName}（${user.username}）`" :value="user.id" />
          </el-select>
          <el-button type="primary" :disabled="!assignment.investigatorId || !assignment.reviewerId" :loading="saving" @click="assignCase">保存分派</el-button>
        </div>
        <div v-else class="assignment-summary">
          <span>调查员：{{ assigneeName(current.assignedInvestigatorId) }}</span>
          <span>审核员：{{ assigneeName(current.assignedReviewerId) }}</span>
        </div>
      </section>

      <section class="panel media-panel">
        <div class="section-heading">
          <div><h2>关联媒体</h2><p>{{ details.assets.length }} 条媒体记录</p></div>
          <div v-if="canEdit && availableAssets.length" class="inline-action">
            <el-select v-model="selectedAssetId" placeholder="选择媒体" filterable>
              <el-option v-for="asset in availableAssets" :key="asset.id" :label="asset.originalFilename" :value="asset.id" />
            </el-select>
            <el-button :disabled="!selectedAssetId" :loading="saving" @click="linkAsset">关联</el-button>
          </div>
        </div>
        <div v-if="details.assets.length" class="asset-card-grid">
          <article v-for="asset in details.assets" :key="asset.id" class="asset-card">
            <strong>{{ asset.originalFilename }}</strong>
            <span>{{ asset.contentType }} · {{ formatBytes(asset.byteSize) }}</span>
            <code>{{ asset.sha256 }}</code>
            <el-button
              v-if="asset.storageStatus === 'STORED'"
              plain
              :loading="previewLoadingId === asset.id"
              @click="previewAsset(asset)"
            >查看原始媒体</el-button>
            <small v-else>旧版仅登记元数据，尚无可预览文件</small>
          </article>
        </div>
        <el-empty v-else description="尚未关联媒体，案件不能进入 READY" />
      </section>

      <section class="panel evidence-panel">
        <div class="section-heading"><div><h2>案件证据</h2><p>人工观察与经调查员确认纳入的 Agent Observation 均为正式证据；Agent 结果不自动形成结论</p></div></div>
        <div v-if="canAddEvidence && workflow.agentEvidenceCandidates.length" class="evidence-list">
          <article v-for="candidate in workflow.agentEvidenceCandidates" :key="candidate.observationId" class="evidence-card">
            <div>
              <strong>{{ evidenceTypeLabel(candidate.evidenceType) }}</strong>
              <el-tag size="small" type="warning">Agent 候选观察</el-tag>
              <el-tag v-if="candidate.promotedEvidenceId" size="small" type="success">已纳入正式证据</el-tag>
            </div>
            <p>{{ candidate.summary }}</p>
            <small>{{ assetName(candidate.assetId) }} · {{ formatDate(candidate.createdAt) }}</small>
            <dl v-if="payloadFields(candidate.payload).length" class="fact-grid compact-facts">
              <template v-for="field in payloadFields(candidate.payload)" :key="field.label">
                <dt>{{ field.label }}</dt><dd>{{ field.value }}</dd>
              </template>
            </dl>
            <el-button
              v-if="!candidate.promotedEvidenceId"
              type="success"
              plain
              :loading="saving"
              @click="promoteAgentObservation(candidate.observationId)"
            >纳入案件证据</el-button>
          </article>
        </div>
        <el-alert
          v-if="canAddEvidence && !workflow.agentEvidenceCandidates.length"
          title="尚无已完成的 Agent Observation，可先运行取证与 RAG Agent"
          type="info"
          :closable="false"
          show-icon
        />
        <el-form v-if="canAddEvidence" class="evidence-form" label-position="top">
          <div class="form-grid">
            <el-form-item label="关联媒体">
              <el-select v-model="evidence.assetId" placeholder="选择案件媒体">
                <el-option v-for="asset in details.assets" :key="asset.id" :label="asset.originalFilename" :value="asset.id" />
              </el-select>
            </el-form-item>
            <el-form-item label="证据标题"><el-input v-model="evidence.title" maxlength="200" /></el-form-item>
            <el-form-item label="判断结论">
              <el-select v-model="evidence.conclusion">
                <el-option label="疑似真实" value="LIKELY_AUTHENTIC" />
                <el-option label="疑似合成" value="LIKELY_SYNTHETIC" />
                <el-option label="无法判断" value="INCONCLUSIVE" />
              </el-select>
            </el-form-item>
            <el-form-item label="置信程度">
              <el-select v-model="evidence.confidence">
                <el-option label="低" value="LOW" /><el-option label="中" value="MEDIUM" /><el-option label="高" value="HIGH" />
              </el-select>
            </el-form-item>
          </div>
          <el-form-item label="观察说明"><el-input v-model="evidence.observation" type="textarea" :rows="4" maxlength="4000" show-word-limit /></el-form-item>
          <el-button type="primary" :disabled="!evidence.assetId || !evidence.title.trim() || !evidence.observation.trim()" :loading="saving" @click="addEvidence">记录人工证据</el-button>
        </el-form>
        <div v-if="workflow.evidence.length" class="evidence-list">
          <article v-for="item in workflow.evidence" :key="item.id" class="evidence-card">
            <div>
              <strong>{{ item.title }}</strong>
              <el-tag size="small" :type="item.evidenceType === 'AGENT_OBSERVATION' ? 'warning' : 'primary'">{{ item.evidenceType === 'AGENT_OBSERVATION' ? 'Agent 观察' : '人工观察' }}</el-tag>
              <el-tag size="small">{{ evidenceConclusionLabel(item.conclusion) }}</el-tag>
              <el-tag size="small" type="info">{{ evidenceConfidenceLabel(item.confidence) }}</el-tag>
            </div>
            <p>{{ item.observation }}</p>
            <small>{{ assetName(item.assetId) }} · {{ formatDate(item.createdAt) }}</small>
            <small v-if="item.sourceObservationId">来源 Observation：{{ item.sourceObservationId }}</small>
          </article>
        </div>
        <el-empty v-else description="进入调查状态后，由分派的调查员记录人工证据或纳入 Agent Observation" />
      </section>

      <section class="panel review-panel">
        <div class="section-heading"><div><h2>审核任务</h2><p>提交审核时自动创建，只能由指定审核员决定</p></div></div>
        <div v-if="canReview && pendingReview" class="review-decision">
          <el-radio-group v-model="review.decision">
            <el-radio-button value="APPROVED">审核通过</el-radio-button>
            <el-radio-button value="REJECTED">驳回案件</el-radio-button>
          </el-radio-group>
          <el-input v-model="review.reason" type="textarea" :rows="3" maxlength="2000" placeholder="审核意见；驳回时必填" />
          <div>
            <strong>引用本次决定所依据的正式证据</strong>
            <el-checkbox-group v-model="review.citedEvidenceIds">
              <el-checkbox v-for="item in workflow.evidence" :key="item.id" :value="item.id">
                {{ item.title }}（{{ item.evidenceType }}）
              </el-checkbox>
            </el-checkbox-group>
          </div>
          <el-button
            type="primary"
            :disabled="!review.citedEvidenceIds.length || (review.decision === 'REJECTED' && !review.reason.trim())"
            :loading="saving"
            @click="decideReview"
          >提交审核决定</el-button>
        </div>
        <ol v-if="workflow.reviewTasks.length" class="review-list">
          <li v-for="task in workflow.reviewTasks" :key="task.id">
            <el-tag :type="task.status === 'APPROVED' ? 'success' : task.status === 'REJECTED' ? 'danger' : 'warning'">{{ task.status }}</el-tag>
            <span>{{ formatDate(task.createdAt) }}</span>
            <p>{{ task.decisionReason || '等待审核决定' }}</p>
            <small v-if="task.citedEvidenceIds.length">引用证据：{{ task.citedEvidenceIds.map(evidenceTitle).join('、') }}</small>
          </li>
        </ol>
        <el-empty v-else description="案件提交 WAITING_REVIEW 后将生成审核任务" />
      </section>

      <section class="panel audit-panel">
        <div class="section-heading"><div><h2>审计时间线</h2><p>分派、证据和审核决定都会留痕</p></div></div>
        <ol class="audit-list">
          <li v-for="entry in audit" :key="entry.id">
            <span>{{ formatDate(entry.createdAt) }}</span>
            <strong>{{ auditActionLabel(entry.action) }}</strong>
            <span class="audit-summary">{{ auditSummary(entry.action, entry.details) }}</span>
          </li>
        </ol>
      </section>

      <el-dialog v-model="previewVisible" :title="previewName" width="min(880px, 92vw)">
        <img v-if="previewUrl" class="media-preview-image" :src="previewUrl" :alt="previewName" />
      </el-dialog>
    </template>
  </main>
</template>
