<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { agentApi } from '../api/agents'
import { ApiRequestError } from '../api/http'
import { useAuthStore } from '../stores/auth'
import type { AgentTaskDetails } from '../types/agent'
import { formatDate } from '../utils/format'

const auth = useAuthStore()
const route = useRoute()
const taskId = route.params.taskId as string
const details = ref<AgentTaskDetails | null>(null)
const loading = ref(false)
const mutating = ref(false)
const canOperate = computed(() => Boolean(
  details.value?.task.status === 'PENDING' &&
    details.value.task.createdBy === auth.user?.id &&
    auth.hasPermission('agent:run'),
))

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
    ElMessage.success('M2.1 Agent 任务已完成')
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

function showError(error: unknown) {
  ElMessage.error(error instanceof ApiRequestError ? error.message : 'Agent 任务请求失败')
}

onMounted(load)
</script>

<template>
  <main class="page-shell" v-loading="loading">
    <template v-if="details">
      <header class="page-header split-header">
        <div>
          <p class="eyebrow">TASK {{ details.task.id }}</p>
          <h1>{{ details.task.goal }}</h1>
          <p>案件 {{ details.task.caseId }} · 创建于 {{ formatDate(details.task.createdAt) }}</p>
        </div>
        <div class="status-stack">
          <el-tag size="large" effect="dark">{{ details.task.status }}</el-tag>
          <span>task version {{ details.task.version }}</span>
        </div>
      </header>

      <section class="metric-grid agent-metrics">
        <article class="panel accent-panel"><span>Planner</span><strong>FAKE</strong></article>
        <article class="panel"><span>Skill</span><strong>{{ details.task.selectedSkillCode || 'Pending' }}</strong></article>
        <article class="panel"><span>Checkpoint</span><strong>v{{ details.task.checkpointVersion }}</strong></article>
        <article class="panel"><span>预算剩余</span><strong>{{ details.task.remainingStepBudget }}</strong></article>
      </section>

      <section v-if="canOperate" class="panel agent-actions">
        <div><h2>执行任务</h2><p>本轮同步执行一个确定性 Skill，不调用真实 LLM 或模型。</p></div>
        <div><el-button type="primary" :loading="mutating" @click="run">运行 Agent</el-button><el-button :loading="mutating" @click="cancel">取消</el-button></div>
      </section>

      <section v-if="Object.keys(details.task.conclusion).length" class="panel agent-conclusion">
        <div class="section-heading"><div><h2>结构化调查草稿</h2><p>这不是人工最终裁决</p></div></div>
        <pre>{{ JSON.stringify(details.task.conclusion, null, 2) }}</pre>
      </section>

      <section class="panel agent-trace">
        <div class="section-heading"><div><h2>执行 Trace</h2><p>{{ details.steps.length }} 个持久化步骤</p></div></div>
        <ol class="trace-list">
          <li v-for="step in details.steps" :key="step.id">
            <span class="trace-index">{{ step.sequenceNumber }}</span>
            <div>
              <strong>{{ step.stepType }}</strong>
              <small>{{ formatDate(step.createdAt) }} · {{ step.status }}</small>
              <p v-if="step.skillCode">Skill: {{ step.skillCode }}<template v-if="step.toolCode"> · Tool: {{ step.toolCode }}</template></p>
              <details><summary>输入 / 输出</summary><pre>{{ JSON.stringify({ input: step.input, output: step.output }, null, 2) }}</pre></details>
            </div>
          </li>
        </ol>
        <el-empty v-if="!details.steps.length" description="任务尚未执行" />
      </section>

      <section class="detail-grid agent-detail-grid">
        <article class="panel">
          <div class="section-heading"><div><h2>Observations</h2><p>结构化工具观察</p></div></div>
          <div v-for="item in details.observations" :key="item.id" class="observation-card">
            <el-tag>{{ item.evidenceType }}</el-tag><p>{{ item.summary }}</p><pre>{{ JSON.stringify(item.payload, null, 2) }}</pre>
          </div>
          <el-empty v-if="!details.observations.length" description="暂无 Observation" />
        </article>
        <article class="panel">
          <div class="section-heading"><div><h2>Checkpoints</h2><p>可恢复状态快照</p></div></div>
          <div v-for="item in details.checkpoints" :key="item.id" class="checkpoint-card">
            <strong>Checkpoint v{{ item.checkpointVersion }}</strong><small>{{ formatDate(item.createdAt) }}</small><pre>{{ JSON.stringify(item.state, null, 2) }}</pre>
          </div>
          <el-empty v-if="!details.checkpoints.length" description="暂无 Checkpoint" />
        </article>
      </section>
    </template>
  </main>
</template>
