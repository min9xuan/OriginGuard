<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { caseApi } from '../api/cases'
import { ApiRequestError } from '../api/http'
import { useAuthStore } from '../stores/auth'
import type { InvestigationCase } from '../types/business'
import { formatDate } from '../utils/format'
import { caseStatusLabel, priorityLabel } from '../utils/presentation'

const auth = useAuthStore()
const router = useRouter()
const cases = ref<InvestigationCase[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    cases.value = await caseApi.list(auth.accessToken)
  } catch (error) {
    ElMessage.error(error instanceof ApiRequestError ? error.message : '无法加载案件')
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <main class="page-shell">
    <header class="page-header split-header">
      <div>
        <p class="eyebrow">INVESTIGATION CASES</p>
        <h1>调查案件</h1>
        <p>案件是媒体、证据、模型任务、人工审核和报告的统一业务容器。</p>
      </div>
      <el-button v-if="auth.hasPermission('case:create')" type="primary" @click="router.push('/cases/new')">
        创建案件
      </el-button>
    </header>

    <section class="panel table-panel">
      <el-table
        :data="cases"
        v-loading="loading"
        empty-text="当前租户还没有案件"
        row-class-name="clickable-row"
        @row-click="(row: InvestigationCase) => router.push(`/cases/${row.id}`)"
      >
        <el-table-column prop="caseNumber" label="案件编号" width="190" />
        <el-table-column prop="title" label="标题" min-width="220" />
        <el-table-column label="状态" width="150">
          <template #default="scope"><span class="status-pill" :data-status="scope.row.status">{{ caseStatusLabel(scope.row.status) }}</span></template>
        </el-table-column>
        <el-table-column label="优先级" width="110">
          <template #default="scope">{{ priorityLabel(scope.row.priority) }}</template>
        </el-table-column>
        <el-table-column label="更新时间" width="180">
          <template #default="scope">{{ formatDate(scope.row.updatedAt) }}</template>
        </el-table-column>
      </el-table>
    </section>
  </main>
</template>
