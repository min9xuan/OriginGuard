<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import { agentEvaluationApi } from '../api/agent-evaluations'
import { agentApi } from '../api/agents'
import { ApiRequestError } from '../api/http'
import { useAuthStore } from '../stores/auth'
import type { AgentEvaluationCase, AgentEvaluationRun } from '../types/agent-evaluation'
import type { AgentTask } from '../types/agent'
import { formatDate } from '../utils/format'

const auth = useAuthStore()
const cases = ref<AgentEvaluationCase[]>([])
const runs = ref<AgentEvaluationRun[]>([])
const tasks = ref<AgentTask[]>([])
const loading = ref(false)
const saving = ref(false)
const running = ref(false)
const selectedCaseId = ref('')
const selectedTaskId = ref('')
const form = reactive({
  name: '单图基础取证链路',
  description: '验证主检测任务是否完成必要规划、保留证据边界并在预算内结束。',
  requiredSkillCodes: ['inspect_media_integrity', 'detect_aigc_with_aide'] as string[],
  forbiddenSkillCodes: [] as string[],
  requiredEvidenceTypes: ['FILE_INTEGRITY', 'AIGC_DETECTION'] as string[],
  forbiddenEvidenceTypes: [] as string[],
  maxToolCalls: 8,
  maxReplans: 6,
  maxDurationSeconds: 600,
  minimumScore: 80,
  requireCompleted: true,
  requireHumanReview: true,
})

const canManage = computed(() => auth.hasPermission('model:manage'))
const terminalTasks = computed(() => tasks.value.filter(task => !['PENDING', 'RUNNING'].includes(task.status)))
const latestRun = computed(() => runs.value[0] ?? null)
const passRate = computed(() => runs.value.length
  ? Math.round(runs.value.filter(run => run.passed).length / runs.value.length * 100)
  : 0)
const selectedCase = computed(() => cases.value.find(item => item.id === selectedCaseId.value))

async function load() {
  loading.value = true
  try {
    const [caseResult, runResult, taskResult] = await Promise.all([
      agentEvaluationApi.cases(auth.accessToken),
      agentEvaluationApi.runs(auth.accessToken),
      agentApi.list(auth.accessToken),
    ])
    cases.value = caseResult
    runs.value = runResult
    tasks.value = taskResult
    if (!selectedCaseId.value && cases.value.length) selectedCaseId.value = cases.value[0].id
    if (!selectedTaskId.value && terminalTasks.value.length) selectedTaskId.value = terminalTasks.value[0].id
  } catch (error) { showError(error) } finally { loading.value = false }
}

async function createCase() {
  if (!form.name.trim()) return ElMessage.warning('请输入评测规则名称')
  saving.value = true
  try {
    const created = await agentEvaluationApi.createCase({
      name: form.name.trim(),
      description: form.description.trim(),
      requiredSkillCodes: form.requiredSkillCodes,
      forbiddenSkillCodes: form.forbiddenSkillCodes,
      requiredEvidenceTypes: form.requiredEvidenceTypes,
      forbiddenEvidenceTypes: form.forbiddenEvidenceTypes,
      maxToolCalls: form.maxToolCalls,
      maxReplans: form.maxReplans,
      maxDurationMilliseconds: form.maxDurationSeconds * 1000,
      minimumScore: form.minimumScore,
      requireCompleted: form.requireCompleted,
      requireHumanReview: form.requireHumanReview,
    }, auth.accessToken)
    selectedCaseId.value = created.id
    ElMessage.success('Agent 评测规则已创建')
    await load()
  } catch (error) { showError(error) } finally { saving.value = false }
}

async function deleteCase(item: AgentEvaluationCase) {
  try {
    await ElMessageBox.confirm(`删除评测规则“${item.name}”及其历史运行？`, '删除规则', { type: 'warning' })
  } catch { return }
  saving.value = true
  try {
    await agentEvaluationApi.deleteCase(item.id, auth.accessToken)
    if (selectedCaseId.value === item.id) selectedCaseId.value = ''
    ElMessage.success('评测规则已删除')
    await load()
  } catch (error) { showError(error) } finally { saving.value = false }
}

async function evaluate() {
  if (!selectedCaseId.value || !selectedTaskId.value) return ElMessage.warning('请选择评测规则和已结束的 Agent 任务')
  running.value = true
  try {
    const result = await agentEvaluationApi.evaluate(selectedCaseId.value, selectedTaskId.value, auth.accessToken)
    ElMessage.success(`评测完成：${result.totalScore.toFixed(1)} 分`)
    await load()
  } catch (error) { showError(error) } finally { running.value = false }
}

function dimensionLabel(value: string) {
  return ({
    TOOL_SELECTION: '工具选择', PLANNING_REPLAN: '规划与重规划', GOAL_COVERAGE: '目标覆盖',
    EVIDENCE_FIDELITY: '证据忠实度', RESILIENCE: '鲁棒性', PERFORMANCE: '性能',
  } as Record<string, string>)[value] ?? value
}
function dimensionMax(value: string) {
  return ({ TOOL_SELECTION: 20, PLANNING_REPLAN: 20, GOAL_COVERAGE: 10,
    EVIDENCE_FIDELITY: 25, RESILIENCE: 15, PERFORMANCE: 10 } as Record<string, number>)[value] ?? 100
}
function metric(value: unknown) { return typeof value === 'number' ? value : 0 }
function violationSummary(value: unknown) {
  if (!Array.isArray(value) || !value.length) return '无'
  return value.map(item => String((item as { message?: unknown }).message || '未知违规')).join('；')
}
function taskLabel(task: AgentTask) { return `${task.goal} · ${task.status} · ${formatDate(task.createdAt)}` }
function showError(error: unknown) {
  ElMessage.error(error instanceof ApiRequestError ? error.message : 'Agent 评测请求失败')
}
onMounted(load)
</script>

<template>
  <main class="page-shell agent-evaluation-page" v-loading="loading">
    <header class="page-header split-header">
      <div><p class="eyebrow">AGENT EVALUATION</p><h1>Agent 行为评测</h1>
        <p>使用真实任务的 Trace、Observation 与 Checkpoint 验证工具选择、重规划、证据忠实度、鲁棒性和耗时。</p></div>
      <el-tag type="info" effect="plain">规则评分优先，不使用 LLM 自评</el-tag>
    </header>

    <section class="metric-grid agent-evaluation-overview">
      <article class="panel"><span>评测规则</span><strong>{{ cases.length }}</strong></article>
      <article class="panel"><span>历史运行</span><strong>{{ runs.length }}</strong></article>
      <article class="panel"><span>通过率</span><strong>{{ passRate }}%</strong></article>
      <article class="panel"><span>最近得分</span><strong>{{ latestRun ? latestRun.totalScore.toFixed(1) : '—' }}</strong></article>
    </section>

    <section class="panel">
      <div class="section-heading"><div><h2>运行真实任务评测</h2><p>只读取已经结束的任务，不修改原任务及证据。</p></div></div>
      <div class="agent-evaluation-run-form">
        <el-select v-model="selectedCaseId" filterable placeholder="选择评测规则">
          <el-option v-for="item in cases" :key="item.id" :label="item.name" :value="item.id" />
        </el-select>
        <el-select v-model="selectedTaskId" filterable placeholder="选择已结束的 Agent 任务">
          <el-option v-for="task in terminalTasks" :key="task.id" :label="taskLabel(task)" :value="task.id" />
        </el-select>
        <el-button type="primary" :disabled="!selectedCaseId || !selectedTaskId || !canManage" :loading="running" @click="evaluate">执行评测</el-button>
      </div>
      <p v-if="selectedCase" class="evaluation-rule-summary">
        上限：{{ selectedCase.maxToolCalls }} 次工具调用、{{ selectedCase.maxReplans }} 次重规划、
        {{ Math.round(selectedCase.maxDurationMilliseconds / 1000) }} 秒；通过线 {{ selectedCase.minimumScore }} 分。
      </p>
    </section>

    <section v-if="canManage" class="panel">
      <div class="section-heading"><div><h2>新建评测规则</h2><p>定义必须/禁止行为以及资源预算，不约束自然语言措辞。</p></div></div>
      <div class="agent-evaluation-case-form">
        <el-input v-model="form.name" maxlength="160" placeholder="规则名称" />
        <el-input v-model="form.description" maxlength="1000" placeholder="适用场景说明" />
        <label>必须调用的 Skill<el-select v-model="form.requiredSkillCodes" multiple filterable allow-create default-first-option placeholder="输入 Skill code" /></label>
        <label>禁止调用的 Skill<el-select v-model="form.forbiddenSkillCodes" multiple filterable allow-create default-first-option placeholder="输入 Skill code" /></label>
        <label>必须产生的 Observation<el-select v-model="form.requiredEvidenceTypes" multiple filterable allow-create default-first-option placeholder="输入 evidence type" /></label>
        <label>禁止产生的 Observation<el-select v-model="form.forbiddenEvidenceTypes" multiple filterable allow-create default-first-option placeholder="输入 evidence type" /></label>
        <div class="evaluation-budget-grid">
          <label>工具调用上限<el-input-number v-model="form.maxToolCalls" :min="1" :max="100" /></label>
          <label>重规划上限<el-input-number v-model="form.maxReplans" :min="0" :max="50" /></label>
          <label>耗时上限（秒）<el-input-number v-model="form.maxDurationSeconds" :min="1" :max="86400" /></label>
          <label>最低通过分<el-input-number v-model="form.minimumScore" :min="0" :max="100" /></label>
        </div>
        <div class="evaluation-switches">
          <el-switch v-model="form.requireCompleted" active-text="要求任务完成" />
          <el-switch v-model="form.requireHumanReview" active-text="必须保留人工核验" />
        </div>
        <el-button type="primary" :loading="saving" @click="createCase">保存评测规则</el-button>
      </div>
    </section>

    <section class="panel">
      <div class="section-heading"><div><h2>评测规则</h2><p>删除规则会同时清理其历史运行。</p></div></div>
      <el-table :data="cases" empty-text="尚未创建 Agent 评测规则">
        <el-table-column prop="name" label="规则" min-width="180" />
        <el-table-column label="必要 Skill" min-width="210"><template #default="scope">{{ scope.row.requiredSkillCodes.join('、') || '无硬性要求' }}</template></el-table-column>
        <el-table-column label="必要证据" min-width="210"><template #default="scope">{{ scope.row.requiredEvidenceTypes.join('、') || '无硬性要求' }}</template></el-table-column>
        <el-table-column label="预算" width="170"><template #default="scope">{{ scope.row.maxToolCalls }} 工具 / {{ scope.row.maxReplans }} 重规划</template></el-table-column>
        <el-table-column label="通过线" width="90"><template #default="scope">{{ scope.row.minimumScore }} 分</template></el-table-column>
        <el-table-column v-if="canManage" width="90"><template #default="scope"><el-button text type="danger" @click="deleteCase(scope.row)">删除</el-button></template></el-table-column>
      </el-table>
    </section>

    <section class="panel">
      <div class="section-heading"><div><h2>历史评测</h2><p>相同规则可用于不同版本任务，观察回归变化。</p></div></div>
      <div v-if="latestRun" class="latest-agent-score">
        <div><span>最近一次</span><strong>{{ latestRun.totalScore.toFixed(1) }}</strong><small>/ 100</small></div>
        <el-tag :type="latestRun.passed ? 'success' : 'danger'">{{ latestRun.passed ? '通过' : '未通过' }}</el-tag>
        <el-tag v-if="latestRun.criticalFailure" type="danger" effect="dark">存在关键失败</el-tag>
      </div>
      <div v-if="latestRun" class="agent-dimension-grid">
        <article v-for="(score, dimension) in latestRun.dimensionScores" :key="dimension">
          <span>{{ dimensionLabel(dimension) }}</span><strong>{{ score.toFixed(1) }} / {{ dimensionMax(dimension) }}</strong>
          <el-progress :percentage="Math.round(score / dimensionMax(dimension) * 100)" :show-text="false" />
        </article>
      </div>
      <el-table :data="runs" empty-text="尚未运行 Agent 评测">
        <el-table-column prop="evaluationCaseName" label="规则" min-width="170" />
        <el-table-column label="任务" min-width="170"><template #default="scope"><RouterLink v-if="scope.row.agentTaskId" :to="`/admin/agent-tasks/${scope.row.agentTaskId}`">查看任务</RouterLink></template></el-table-column>
        <el-table-column label="得分" width="90"><template #default="scope"><strong>{{ scope.row.totalScore.toFixed(1) }}</strong></template></el-table-column>
        <el-table-column label="结果" width="100"><template #default="scope"><el-tag :type="scope.row.passed ? 'success' : 'danger'">{{ scope.row.passed ? '通过' : '失败' }}</el-tag></template></el-table-column>
        <el-table-column label="工具 / 重规划 / 耗时" width="220"><template #default="scope">{{ metric(scope.row.metrics.toolCallCount) }} / {{ metric(scope.row.metrics.replanCount) }} / {{ (metric(scope.row.metrics.durationMilliseconds) / 1000).toFixed(1) }}s</template></el-table-column>
        <el-table-column label="违规" min-width="240"><template #default="scope">{{ violationSummary(scope.row.violations) }}</template></el-table-column>
        <el-table-column label="时间" width="180"><template #default="scope">{{ formatDate(scope.row.createdAt) }}</template></el-table-column>
      </el-table>
    </section>
  </main>
</template>

<style scoped>
.agent-evaluation-overview { grid-template-columns: repeat(4, minmax(0, 1fr)); }
.agent-evaluation-run-form { display: grid; grid-template-columns: minmax(220px, .8fr) minmax(320px, 1.6fr) auto; gap: 12px; align-items: center; }
.evaluation-rule-summary { margin: 14px 0 0; color: var(--text-muted); }
.agent-evaluation-case-form { display: grid; gap: 14px; }
.agent-evaluation-case-form > label { display: grid; gap: 7px; color: var(--text-muted); }
.evaluation-budget-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 12px; }
.evaluation-budget-grid label { display: grid; gap: 7px; color: var(--text-muted); }
.evaluation-switches { display: flex; gap: 28px; }
.latest-agent-score { display: flex; gap: 12px; align-items: center; margin-bottom: 18px; }
.latest-agent-score > div { display: flex; align-items: baseline; gap: 6px; }
.latest-agent-score strong { font-size: 2.2rem; }
.agent-dimension-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; margin-bottom: 22px; }
.agent-dimension-grid article { padding: 15px; border: 1px solid var(--line); background: var(--surface-soft); }
.agent-dimension-grid span, .agent-dimension-grid strong { display: block; }
.agent-dimension-grid strong { margin: 7px 0 11px; }
@media (max-width: 900px) {
  .agent-evaluation-overview, .evaluation-budget-grid, .agent-dimension-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .agent-evaluation-run-form { grid-template-columns: 1fr; }
}
</style>
