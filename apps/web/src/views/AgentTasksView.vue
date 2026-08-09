<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { agentApi } from '../api/agents'
import { ApiRequestError } from '../api/http'
import { useAuthStore } from '../stores/auth'
import type { AgentTask } from '../types/agent'
import { formatDate } from '../utils/format'

const auth = useAuthStore()
const router = useRouter()
const tasks = ref<AgentTask[]>([])
const loading = ref(false)

async function load() {
  loading.value = true
  try {
    tasks.value = await agentApi.list(auth.accessToken)
  } catch (error) {
    ElMessage.error(error instanceof ApiRequestError ? error.message : 'Agent 任务加载失败')
  } finally {
    loading.value = false
  }
}

function open(task: AgentTask) {
  void router.push(`/agent-tasks/${task.id}`)
}

onMounted(load)
</script>

<template>
  <main class="page-shell">
    <header class="page-header split-header">
      <div>
        <p class="eyebrow">AGENT HARNESS</p>
        <h1>Agent 任务与 Trace</h1>
        <p>M3.1 保留 Fake Planner，通过受控 Tool 读取 MinIO 中的真实媒体并保存完整 Trace。</p>
      </div>
      <el-tag type="success" effect="plain">Deterministic + Real Tool</el-tag>
    </header>

    <section class="panel table-panel">
      <div class="section-heading">
        <div><h2>当前租户任务</h2><p>共 {{ tasks.length }} 个任务</p></div>
        <el-button plain :loading="loading" @click="load">刷新</el-button>
      </div>
      <el-table :data="tasks" v-loading="loading" empty-text="还没有 Agent 任务" @row-click="open">
        <el-table-column prop="goal" label="目标" min-width="260" />
        <el-table-column label="状态" width="130">
          <template #default="scope"><el-tag>{{ scope.row.status }}</el-tag></template>
        </el-table-column>
        <el-table-column label="Skill" min-width="190">
          <template #default="scope">{{ scope.row.selectedSkillCode || '尚未选择' }}</template>
        </el-table-column>
        <el-table-column label="剩余预算" width="100" prop="remainingStepBudget" />
        <el-table-column label="创建时间" width="180">
          <template #default="scope">{{ formatDate(scope.row.createdAt) }}</template>
        </el-table-column>
      </el-table>
    </section>
  </main>
</template>
