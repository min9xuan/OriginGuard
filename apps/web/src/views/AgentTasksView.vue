<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { agentApi } from '../api/agents'
import { ApiRequestError } from '../api/http'
import { useAuthStore } from '../stores/auth'
import type { AgentTask } from '../types/agent'
import { formatDate } from '../utils/format'
import { agentStatusLabel } from '../utils/presentation'

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

function planLabel(task: AgentTask) {
  if (!task.selectedSkillCode) return '等待生成调查方案'
  if (task.selectedSkillCode.includes('qwen3_vl')) return '多模态模型动态规划'
  return '固定流程规划'
}

function resultHint(task: AgentTask) {
  if (task.status === 'COMPLETED') return '查看方案、候选观察与知识依据'
  if (task.status === 'FAILED') return '查看失败原因和已完成步骤'
  if (task.status === 'RUNNING') return '正在执行受控取证工具'
  if (task.status === 'CANCELLED') return '任务已终止'
  return '等待调查员启动'
}

onMounted(load)
</script>

<template>
  <main class="page-shell">
    <header class="page-header split-header">
      <div>
        <p class="eyebrow">AGENT HARNESS</p>
        <h1>Agent 取证任务</h1>
        <p>每条任务记录一次“模型规划 → 受控工具取证 → 调查员核验”的完整过程。</p>
      </div>
      <el-tag type="success" effect="plain">结果需人工核验</el-tag>
    </header>

    <section class="panel table-panel">
      <div class="section-heading">
        <div><h2>当前租户任务</h2><p>共 {{ tasks.length }} 个任务</p></div>
        <el-button plain :loading="loading" @click="load">刷新</el-button>
      </div>
      <el-table :data="tasks" v-loading="loading" empty-text="还没有 Agent 任务" row-class-name="clickable-row" @row-click="open">
        <el-table-column prop="goal" label="任务内容" min-width="280" />
        <el-table-column label="状态" width="130">
          <template #default="scope"><span class="status-pill" :data-status="scope.row.status">{{ agentStatusLabel(scope.row.status) }}</span></template>
        </el-table-column>
        <el-table-column label="调查方式" min-width="190">
          <template #default="scope">{{ planLabel(scope.row) }}</template>
        </el-table-column>
        <el-table-column label="任务产出" min-width="240">
          <template #default="scope"><span class="task-result-hint">{{ resultHint(scope.row) }}</span></template>
        </el-table-column>
        <el-table-column label="创建时间" width="180">
          <template #default="scope">{{ formatDate(scope.row.createdAt) }}</template>
        </el-table-column>
      </el-table>
    </section>
  </main>
</template>
