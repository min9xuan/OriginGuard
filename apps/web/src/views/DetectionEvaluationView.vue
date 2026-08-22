<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, onMounted, reactive, ref } from 'vue'
import { evaluationApi } from '../api/evaluations'
import { ApiRequestError } from '../api/http'
import { mediaApi } from '../api/media'
import { useAuthStore } from '../stores/auth'
import type { MediaAsset } from '../types/business'
import type { EvaluationGroundTruth, EvaluationMediaCategory, EvaluationRun, EvaluationSample } from '../types/evaluation'
import { formatDate } from '../utils/format'

const auth = useAuthStore()
const assets = ref<MediaAsset[]>([])
const samples = ref<EvaluationSample[]>([])
const runs = ref<EvaluationRun[]>([])
const loading = ref(false)
const saving = ref(false)
const running = ref(false)
const form = reactive({
  assetId: '', groundTruth: 'AUTHENTIC' as EvaluationGroundTruth,
  mediaCategory: 'PHOTOGRAPH' as EvaluationMediaCategory, generatorName: '',
})
const evaluationThreshold = ref(0.5)

const latestRun = computed(() => runs.value[0] ?? null)
const availableAssets = computed(() => {
  const selected = new Set(samples.value.map(sample => sample.assetId))
  return assets.value.filter(asset => asset.storageStatus === 'STORED'
    && asset.contentType.startsWith('image/') && !selected.has(asset.id))
})
const classCounts = computed(() => ({
  authentic: samples.value.filter(sample => sample.groundTruth === 'AUTHENTIC').length,
  synthetic: samples.value.filter(sample => sample.groundTruth === 'SYNTHETIC').length,
}))
const canRun = computed(() => classCounts.value.authentic > 0 && classCounts.value.synthetic > 0)

async function load() {
  loading.value = true
  try {
    const [assetResult, sampleResult, runResult] = await Promise.all([
      mediaApi.list(auth.accessToken), evaluationApi.samples(auth.accessToken), evaluationApi.runs(auth.accessToken),
    ])
    assets.value = assetResult
    samples.value = sampleResult
    runs.value = runResult
  } catch (error) { showError(error) } finally { loading.value = false }
}

async function addSample() {
  if (!form.assetId) return ElMessage.warning('请选择媒体资产')
  saving.value = true
  try {
    await evaluationApi.addSample({ ...form }, auth.accessToken)
    form.assetId = ''
    if (form.groundTruth === 'AUTHENTIC') form.generatorName = ''
    ElMessage.success('验证样本已加入')
    await load()
  } catch (error) { showError(error) } finally { saving.value = false }
}

async function removeSample(sample: EvaluationSample) {
  try {
    await ElMessageBox.confirm(`从验证集中移除“${sample.assetFilename}”？`, '移除样本', { type: 'warning' })
  } catch { return }
  saving.value = true
  try {
    await evaluationApi.deleteSample(sample.id, auth.accessToken)
    ElMessage.success('样本已移除')
    await load()
  } catch (error) { showError(error) } finally { saving.value = false }
}

async function runEvaluation() {
  if (!canRun.value) return ElMessage.warning('真实与 AIGC 样本至少各需要 1 张')
  running.value = true
  try {
    const result = await evaluationApi.run(evaluationThreshold.value, auth.accessToken)
    ElMessage.success(`评测完成，推荐阈值 ${result.recommendedThreshold.toFixed(3)}`)
    await load()
  } catch (error) { showError(error) } finally { running.value = false }
}

function percent(value: number) { return `${(value * 100).toFixed(1)}%` }
function truthLabel(value: EvaluationGroundTruth) { return value === 'SYNTHETIC' ? 'AIGC' : '真实' }
function categoryLabel(value: string) {
  return ({ PHOTOGRAPH: '摄影', CARTOON: '卡通', ILLUSTRATION: '插画', OTHER: '其他' } as Record<string, string>)[value] ?? value
}
function showError(error: unknown) {
  ElMessage.error(error instanceof ApiRequestError ? error.message : '模型评测请求失败')
}
onMounted(load)
</script>

<template>
  <main class="page-shell" v-loading="loading">
    <header class="page-header split-header">
      <div><p class="eyebrow">M5.3 / MODEL EVALUATION</p><h1>检测模型评测</h1>
        <p>使用带真实标签的媒体验证 AIDE，记录模型版本、错误样本并推荐可复现阈值。</p></div>
      <el-tag type="warning" effect="plain">推荐阈值不会自动替换生产策略</el-tag>
    </header>

    <section class="panel evaluation-setup">
      <div class="section-heading"><div><h2>验证集</h2><p>真实 {{ classCounts.authentic }} 张 · AIGC {{ classCounts.synthetic }} 张</p></div></div>
      <div class="evaluation-form-grid">
        <el-select v-model="form.assetId" filterable placeholder="选择已存储的图像资产">
          <el-option v-for="asset in availableAssets" :key="asset.id" :label="asset.originalFilename" :value="asset.id" />
        </el-select>
        <el-radio-group v-model="form.groundTruth">
          <el-radio-button value="AUTHENTIC">真实图像</el-radio-button>
          <el-radio-button value="SYNTHETIC">AIGC 图像</el-radio-button>
        </el-radio-group>
        <el-select v-model="form.mediaCategory">
          <el-option label="摄影图像" value="PHOTOGRAPH" /><el-option label="卡通" value="CARTOON" />
          <el-option label="插画" value="ILLUSTRATION" /><el-option label="其他" value="OTHER" />
        </el-select>
        <el-input v-if="form.groundTruth === 'SYNTHETIC'" v-model="form.generatorName" maxlength="100" placeholder="生成器（可选）" />
        <el-button type="primary" :loading="saving" :disabled="!form.assetId" @click="addSample">加入验证集</el-button>
      </div>
      <el-table :data="samples" empty-text="尚未添加验证样本">
        <el-table-column prop="assetFilename" label="文件" min-width="190" />
        <el-table-column label="真实标签" width="100"><template #default="scope"><el-tag :type="scope.row.groundTruth === 'SYNTHETIC' ? 'warning' : 'success'">{{ truthLabel(scope.row.groundTruth) }}</el-tag></template></el-table-column>
        <el-table-column label="图像类型" width="100"><template #default="scope">{{ categoryLabel(scope.row.mediaCategory) }}</template></el-table-column>
        <el-table-column prop="generatorName" label="生成器" min-width="150" />
        <el-table-column label="加入时间" width="180"><template #default="scope">{{ formatDate(scope.row.createdAt) }}</template></el-table-column>
        <el-table-column width="90"><template #default="scope"><el-button text type="danger" :loading="saving" @click="removeSample(scope.row)">移除</el-button></template></el-table-column>
      </el-table>
    </section>

    <section class="panel evaluation-run-panel">
      <div><h2>运行 AIDE 评测</h2><p>当前阈值生成混淆矩阵；系统另外遍历样本分数推荐 F1 更优的阈值。</p></div>
      <div class="evaluation-run-action">
        <label>当前评测阈值 <el-input-number v-model="evaluationThreshold" :min="0" :max="1" :step="0.01" :precision="2" /></label>
        <el-button type="success" :disabled="!canRun" :loading="running" @click="runEvaluation">批量运行评测</el-button>
      </div>
      <el-alert v-if="!canRun" type="warning" :closable="false" title="真实与 AIGC 样本至少各需要 1 张，才能计算阈值。" />
    </section>

    <template v-if="latestRun">
      <section class="metric-grid evaluation-metrics">
        <article class="panel"><span>推荐阈值</span><strong>{{ latestRun.recommendedThreshold.toFixed(3) }}</strong></article>
        <article class="panel"><span>Accuracy</span><strong>{{ percent(latestRun.metrics.accuracy) }}</strong></article>
        <article class="panel"><span>Precision</span><strong>{{ percent(latestRun.metrics.precision) }}</strong></article>
        <article class="panel"><span>Recall / F1</span><strong>{{ percent(latestRun.metrics.recall) }} / {{ percent(latestRun.metrics.f1) }}</strong></article>
      </section>
      <section class="panel">
        <div class="section-heading"><div><h2>最近一次评测</h2><p>{{ latestRun.modelCode }} · {{ latestRun.modelVersion }} · {{ formatDate(latestRun.createdAt) }}</p></div></div>
        <div class="confusion-grid">
          <div><span>TP</span><strong>{{ latestRun.metrics.truePositive }}</strong></div><div><span>TN</span><strong>{{ latestRun.metrics.trueNegative }}</strong></div>
          <div><span>FP</span><strong>{{ latestRun.metrics.falsePositive }}</strong></div><div><span>FN</span><strong>{{ latestRun.metrics.falseNegative }}</strong></div>
        </div>
        <h3 v-if="Object.keys(latestRun.categoryMetrics).length">分类型推荐阈值</h3>
        <div class="category-thresholds">
          <article v-for="(item, category) in latestRun.categoryMetrics" :key="category">
            <span>{{ categoryLabel(category) }}</span><strong>{{ item.recommendedThreshold.toFixed(3) }}</strong><small>{{ item.metrics.sampleCount }} 个样本 · F1 {{ percent(item.metrics.f1) }}</small>
          </article>
        </div>
        <el-table :data="latestRun.results">
          <el-table-column prop="assetFilename" label="样本" min-width="190" />
          <el-table-column label="真实/预测" width="150"><template #default="scope">{{ truthLabel(scope.row.groundTruth) }} / {{ truthLabel(scope.row.predictedLabel) }}</template></el-table-column>
          <el-table-column label="AIGC 概率" width="120"><template #default="scope">{{ percent(scope.row.syntheticProbability) }}</template></el-table-column>
          <el-table-column prop="qualityStatus" label="质量门控" width="110" />
          <el-table-column label="结果" width="100"><template #default="scope"><el-tag :type="scope.row.correct ? 'success' : 'danger'">{{ scope.row.correct ? '正确' : '误判' }}</el-tag></template></el-table-column>
        </el-table>
      </section>
    </template>
    <el-empty v-else description="完成首次评测后，这里会展示指标、推荐阈值和误判样本" />
  </main>
</template>
