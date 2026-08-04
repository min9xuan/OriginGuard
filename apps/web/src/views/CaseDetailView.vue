<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { caseApi } from '../api/cases'
import { ApiRequestError } from '../api/http'
import { mediaApi } from '../api/media'
import { useAuthStore } from '../stores/auth'
import type { AuditEntry, CaseDetails, CasePriority, CaseStatus, MediaAsset } from '../types/business'
import { nextM11Transition } from '../utils/case-workflow'
import { formatBytes, formatDate } from '../utils/format'

const auth = useAuthStore()
const route = useRoute()
const caseId = route.params.caseId as string
const details = ref<CaseDetails | null>(null)
const allAssets = ref<MediaAsset[]>([])
const audit = ref<AuditEntry[]>([])
const loading = ref(false)
const saving = ref(false)
const selectedAssetId = ref('')
const edit = reactive({ title: '', description: '', priority: 'NORMAL' as CasePriority })

const current = computed(() => details.value?.investigationCase ?? null)
const canOperate = computed(() => {
  const item = current.value
  return Boolean(
    item &&
      auth.hasPermission('case:update') &&
      (item.createdBy === auth.user?.id || item.assignedInvestigatorId === auth.user?.id),
  )
})
const canEdit = computed(() => Boolean(canOperate.value && current.value && ['DRAFT', 'REJECTED'].includes(current.value.status)))
const availableAssets = computed(() => {
  const linked = new Set(details.value?.assets.map((asset) => asset.id) ?? [])
  return allAssets.value.filter((asset) => !linked.has(asset.id))
})
const nextTransition = computed<{ target: CaseStatus; label: string } | null>(() => {
  const status = current.value?.status
  return status
    ? nextM11Transition(status, canOperate.value, auth.hasPermission('case:submit'))
    : null
})

async function load() {
  loading.value = true
  try {
    const [caseResult, assetsResult, auditResult] = await Promise.all([
      caseApi.get(caseId, auth.accessToken),
      mediaApi.list(auth.accessToken),
      caseApi.audit(caseId, auth.accessToken),
    ])
    setDetails(caseResult)
    allAssets.value = assetsResult
    audit.value = auditResult
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
}

async function save() {
  if (!current.value) return
  saving.value = true
  try {
    setDetails(await caseApi.update(
      caseId,
      { ...edit, version: current.value.version },
      auth.accessToken,
    ))
    ElMessage.success('案件信息已更新')
    await reloadAudit()
  } catch (error) {
    await handleMutationError(error)
  } finally {
    saving.value = false
  }
}

async function linkAsset() {
  if (!current.value || !selectedAssetId.value) return
  saving.value = true
  try {
    setDetails(await caseApi.linkAsset(
      caseId,
      selectedAssetId.value,
      current.value.version,
      auth.accessToken,
    ))
    selectedAssetId.value = ''
    ElMessage.success('媒体已关联到案件')
    await reloadAudit()
  } catch (error) {
    await handleMutationError(error)
  } finally {
    saving.value = false
  }
}

async function transition() {
  if (!current.value || !nextTransition.value) return
  saving.value = true
  try {
    setDetails(await caseApi.transition(
      caseId,
      nextTransition.value.target,
      current.value.version,
      auth.accessToken,
    ))
    ElMessage.success(`案件状态已更新为 ${nextTransition.value.target}`)
    await reloadAudit()
  } catch (error) {
    await handleMutationError(error)
  } finally {
    saving.value = false
  }
}

async function reloadAudit() {
  audit.value = await caseApi.audit(caseId, auth.accessToken)
}

async function handleMutationError(error: unknown) {
  showError(error)
  if (error instanceof ApiRequestError && error.code === 'CASE_VERSION_CONFLICT') await load()
}

function showError(error: unknown) {
  ElMessage.error(error instanceof ApiRequestError ? error.message : '请求失败，请稍后重试')
}

onMounted(load)
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
          <el-tag size="large" effect="dark">{{ current.status }}</el-tag>
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
                <el-option v-for="item in ['LOW', 'NORMAL', 'HIGH', 'CRITICAL']" :key="item" :label="item" :value="item" />
              </el-select>
            </el-form-item>
            <el-button v-if="canEdit" type="primary" :loading="saving" @click="save">保存修改</el-button>
          </el-form>
        </article>

        <article class="panel workflow-panel">
          <div><span>当前状态</span><strong>{{ current.status }}</strong></div>
          <p>DRAFT → READY → INVESTIGATING → WAITING_REVIEW</p>
          <el-button v-if="nextTransition" type="primary" :loading="saving" @click="transition">
            {{ nextTransition.label }}
          </el-button>
          <small v-else>M1.1 不处理人工审核决定和报告归档。</small>
        </article>
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
          </article>
        </div>
        <el-empty v-else description="尚未关联媒体，案件不能进入 READY" />
      </section>

      <section class="panel audit-panel">
        <div class="section-heading"><div><h2>审计时间线</h2><p>关键业务变化只追加、不覆盖</p></div></div>
        <ol class="audit-list">
          <li v-for="entry in audit" :key="entry.id">
            <span>{{ formatDate(entry.createdAt) }}</span>
            <strong>{{ entry.action }}</strong>
            <code>{{ JSON.stringify(entry.details) }}</code>
          </li>
        </ol>
      </section>
    </template>
  </main>
</template>
