<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { agentApi } from '../api/agents'
import { caseApi } from '../api/cases'
import { ApiRequestError } from '../api/http'
import { mediaApi } from '../api/media'
import { useAuthStore } from '../stores/auth'
import type { AgentTask } from '../types/agent'
import type { InvestigationCase } from '../types/business'
import { formatDate } from '../utils/format'
import { agentStatusLabel, verdictLabel } from '../utils/presentation'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const routePrefix = () => route.path.startsWith('/admin') ? '/admin' : '/analyze'
const tasks = ref<AgentTask[]>([])
const cases = ref<InvestigationCase[]>([])
const loading = ref(false)
const previewingTaskId = ref('')
const previewVisible = ref(false)
const previewUrl = ref('')
const previewName = ref('')
const casesById = computed(() => new Map(cases.value.map((item) => [item.id, item])))

async function load() {
  loading.value = true
  try {
    const [taskResult, caseResult] = await Promise.all([
      agentApi.list(auth.accessToken),
      caseApi.list(auth.accessToken),
    ])
    tasks.value = taskResult
    cases.value = caseResult
  } catch (error) {
    ElMessage.error(error instanceof ApiRequestError ? error.message : 'Agent 任务加载失败')
  } finally {
    loading.value = false
  }
}

function open(task: AgentTask) {
  void router.push(`${routePrefix()}/agent-tasks/${task.id}`)
}

async function preview(task: AgentTask) {
  previewingTaskId.value = task.id
  try {
    const details = await caseApi.get(task.caseId, auth.accessToken)
    const asset = details.assets[0]
    if (!asset) {
      ElMessage.warning('这条检测记录没有可预览的图片')
      return
    }
    const blob = await mediaApi.content(asset.id, auth.accessToken)
    if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
    previewUrl.value = URL.createObjectURL(blob)
    previewName.value = asset.originalFilename
    previewVisible.value = true
  } catch (error) {
    ElMessage.error(error instanceof ApiRequestError ? error.message : '图片预览加载失败')
  } finally {
    previewingTaskId.value = ''
  }
}

function sourceCase(task: AgentTask) {
  return casesById.value.get(task.caseId)
}

function resultHint(task: AgentTask) {
  if (task.status === 'COMPLETED') return verdictLabel(String(task.conclusion.verdict || 'INCONCLUSIVE'))
  if (task.status === 'FAILED') return '分析失败'
  if (task.status === 'RUNNING') return '正在分析'
  if (task.status === 'CANCELLED') return '已取消'
  return '等待开始'
}

onMounted(load)
onBeforeUnmount(() => {
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
})
</script>

<template>
  <main class="page-shell detection-history-page">
    <header class="page-header split-header">
      <div>
        <p class="eyebrow">DETECTION HISTORY</p>
        <h1>检测记录</h1>
        <p>查看你上传过的图片、Agent 初步判断以及最终人工核验结果。</p>
      </div>
    </header>

    <section class="panel detection-record-panel">
      <div class="section-heading">
        <div><h2>全部记录</h2><p>共 {{ tasks.length }} 次检测</p></div>
        <el-button plain :loading="loading" @click="load">刷新</el-button>
      </div>
      <div v-loading="loading" class="detection-record-list">
        <article v-for="task in tasks" :key="task.id" class="detection-record">
          <div class="detection-record-main">
            <small>{{ sourceCase(task)?.caseNumber || '检测记录' }}</small>
            <strong>{{ sourceCase(task)?.title || '图片真实性检测' }}</strong>
            <span>{{ formatDate(task.createdAt) }}</span>
          </div>
          <div class="detection-record-result">
            <small>Agent 初步判断</small>
            <strong>{{ resultHint(task) }}</strong>
          </div>
          <span class="status-pill" :data-status="task.status">{{ agentStatusLabel(task.status) }}</span>
          <div class="detection-record-actions">
            <el-button plain :loading="previewingTaskId === task.id" @click="preview(task)">预览图片</el-button>
            <button type="button" class="record-open" aria-label="查看检测详情" @click="open(task)">查看结果 <span aria-hidden="true">→</span></button>
          </div>
        </article>
        <el-empty v-if="!loading && !tasks.length" description="还没有检测记录" />
      </div>
    </section>

    <el-dialog v-model="previewVisible" class="image-preview-dialog" :title="previewName" width="min(900px, 92vw)" destroy-on-close>
      <img v-if="previewUrl" :src="previewUrl" :alt="previewName" />
    </el-dialog>
  </main>
</template>
