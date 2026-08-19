<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'
import { knowledgeApi } from '../api/knowledge'
import { ApiRequestError } from '../api/http'
import { useAuthStore } from '../stores/auth'
import type {
  KnowledgeDocument, KnowledgeDocumentType, KnowledgeSearchResult, RagEvaluationCase, RagEvaluationRun,
  RagExternalKnowledgeCandidate, RagKnowledgeVenue,
} from '../types/knowledge'
import { formatDate } from '../utils/format'

const auth = useAuthStore()
const documents = ref<KnowledgeDocument[]>([])
const loading = ref(false)
const saving = ref(false)
const form = reactive({ title: '', documentType: 'FORENSIC_GUIDE' as KnowledgeDocumentType, content: '' })
const debug = reactive({ query: '', topK: 5 })
const searchResults = ref<KnowledgeSearchResult[]>([])
const evaluationCases = ref<RagEvaluationCase[]>([])
const evaluationRun = ref<RagEvaluationRun | null>(null)
const expansionVenues = ref<RagKnowledgeVenue[]>([])
const expansionCandidates = ref<RagExternalKnowledgeCandidate[]>([])
const expansionSearching = ref(false)
const draftingCandidateId = ref('')
const currentYear = new Date().getFullYear()
const expansion = reactive({
  query: 'AI-generated image detection media forgery content provenance',
  venueCodes: ['CVPR', 'ICCV', 'ECCV'],
  fromYear: currentYear - 3,
  toYear: currentYear,
  limit: 10,
})

async function load() {
  loading.value = true
  try {
    documents.value = await knowledgeApi.list(auth.accessToken)
    evaluationCases.value = await knowledgeApi.listEvaluationCases(auth.accessToken)
    if (auth.hasPermission('knowledge:upload')) {
      expansionVenues.value = await knowledgeApi.listExpansionVenues(auth.accessToken)
    }
  }
  catch (error) { showError(error) }
  finally { loading.value = false }
}
async function search() {
  if (!debug.query.trim()) return ElMessage.warning('请输入检索问题')
  saving.value = true
  try { searchResults.value = (await knowledgeApi.debugSearch(debug.query, debug.topK, auth.accessToken)).results }
  catch (error) { showError(error) } finally { saving.value = false }
}
async function addEvaluationCase(result: KnowledgeSearchResult) {
  saving.value = true
  try {
    await knowledgeApi.createEvaluationCase(
      debug.query.slice(0, 200), debug.query, result.documentId, result.chunkId, auth.accessToken,
    )
    ElMessage.success('已把当前问题和期望 Chunk 保存为评测用例'); await load()
  } catch (error) { showError(error) } finally { saving.value = false }
}
async function runEvaluation() {
  saving.value = true
  try {
    evaluationRun.value = await knowledgeApi.runEvaluation(debug.topK, auth.accessToken)
    ElMessage.success('RAG 评测完成')
  } catch (error) { showError(error) } finally { saving.value = false }
}
async function create() {
  if (!form.title.trim() || !form.content.trim()) return ElMessage.warning('请填写标题和知识正文')
  saving.value = true
  try {
    await knowledgeApi.create(form.title, form.documentType, form.content, auth.accessToken)
    Object.assign(form, { title: '', documentType: 'FORENSIC_GUIDE', content: '' })
    ElMessage.success('知识草稿已创建'); await load()
  } catch (error) { showError(error) } finally { saving.value = false }
}
async function publish(document: KnowledgeDocument) {
  saving.value = true
  try {
    const result = await knowledgeApi.publish(document.id, document.version, auth.accessToken)
    ElMessage.success(`已发布并生成 ${result.chunkCount} 个 Chunk`); await load()
  } catch (error) { showError(error) } finally { saving.value = false }
}
async function reindex() {
  saving.value = true
  try {
    const result = await knowledgeApi.reindex(auth.accessToken)
    ElMessage.success(`已使用 ${result.embeddingProvider} 重新向量化 ${result.chunkCount} 个 Chunk`)
  } catch (error) { showError(error) } finally { saving.value = false }
}
async function discoverKnowledge() {
  if (!expansion.query.trim()) return ElMessage.warning('请输入需要扩展的 RAG 知识主题')
  if (!expansion.venueCodes.length) return ElMessage.warning('请至少选择一个会议或期刊')
  expansionSearching.value = true
  try {
    const result = await knowledgeApi.discoverKnowledge(
      expansion.query, expansion.venueCodes, expansion.fromYear, expansion.toYear,
      expansion.limit, auth.accessToken,
    )
    expansionCandidates.value = result.candidates
    ElMessage.success(`检索完成，获得 ${result.candidates.length} 条 RAG 候选知识`)
  } catch (error) { showError(error) } finally { expansionSearching.value = false }
}
async function createExternalDraft(candidate: RagExternalKnowledgeCandidate) {
  draftingCandidateId.value = candidate.sourceIdentifier
  try {
    await knowledgeApi.createExternalDraft(candidate, auth.accessToken)
    expansionCandidates.value = expansionCandidates.value.filter(
      item => item.sourceIdentifier !== candidate.sourceIdentifier,
    )
    ElMessage.success('已生成待审核知识草稿，审核发布后才会进入 Agent RAG')
    await load()
  } catch (error) { showError(error) } finally { draftingCandidateId.value = '' }
}
function sourceScopeLabel(scope: KnowledgeDocument['sourceScope']) {
  return ({ TENANT: '租户知识', BUILTIN: '系统内置', EXTERNAL: '外部学术来源' } as const)[scope]
}
function showError(error: unknown) {
  ElMessage.error(error instanceof ApiRequestError ? error.message : '知识库请求失败')
}
onMounted(load)
</script>

<template>
  <main class="page-shell" v-loading="loading">
    <header class="page-header"><p class="eyebrow">M4.1 / RAG KNOWLEDGE</p><h1>取证知识库</h1>
      <p>只有已发布版本会进入租户内的全文检索与 pgvector 混合召回。</p></header>
    <section v-if="auth.hasPermission('knowledge:upload')" class="panel">
      <div class="section-heading"><div><h2>新建知识草稿</h2><p>支持 Markdown 或纯文本；发布后自动切片与向量化</p></div></div>
      <el-button v-if="auth.hasPermission('knowledge:publish')" plain :loading="saving" @click="reindex">
        使用当前 Embedding 重新向量化已发布知识
      </el-button>
      <el-form label-position="top">
        <el-form-item label="标题"><el-input v-model="form.title" maxlength="200" /></el-form-item>
        <el-form-item label="类型"><el-select v-model="form.documentType">
          <el-option label="取证指引" value="FORENSIC_GUIDE" /><el-option label="政策规范" value="POLICY" />
          <el-option label="模型卡" value="MODEL_CARD" /><el-option label="其他" value="OTHER" />
        </el-select></el-form-item>
        <el-form-item label="正文"><el-input v-model="form.content" type="textarea" :rows="10" /></el-form-item>
        <el-button type="primary" :loading="saving" @click="create">保存草稿</el-button>
      </el-form>
    </section>
    <section v-if="auth.hasPermission('knowledge:upload')" class="panel">
      <div class="section-heading">
        <div>
          <h2>RAG 知识扩展</h2>
          <p>点击时只检索权威学术元数据与摘要，不下载论文 PDF；候选内容生成草稿后需人工审核发布。</p>
        </div>
      </div>
      <el-form label-position="top">
        <el-form-item label="知识主题">
          <el-input v-model="expansion.query" maxlength="300"
            placeholder="例如：AIGC 图像检测、媒体篡改定位、内容溯源与生成模型归因" />
        </el-form-item>
        <el-form-item label="权威会议与期刊（最多 6 个）">
          <el-select v-model="expansion.venueCodes" multiple collapse-tags :max-collapse-tags="4"
            placeholder="选择 RAG 知识来源">
            <el-option v-for="venue in expansionVenues" :key="venue.code"
              :label="`${venue.code} · ${venue.name}`" :value="venue.code"
              :disabled="!expansion.venueCodes.includes(venue.code) && expansion.venueCodes.length >= 6" />
          </el-select>
        </el-form-item>
        <div class="knowledge-expansion-filters">
          <el-form-item label="起始年份"><el-input-number v-model="expansion.fromYear" :min="2000" :max="currentYear + 1" /></el-form-item>
          <el-form-item label="截止年份"><el-input-number v-model="expansion.toYear" :min="2000" :max="currentYear + 1" /></el-form-item>
          <el-form-item label="候选数量"><el-input-number v-model="expansion.limit" :min="1" :max="30" /></el-form-item>
        </div>
        <el-button type="primary" :loading="expansionSearching" @click="discoverKnowledge">检索候选知识</el-button>
      </el-form>
      <p v-if="expansionCandidates.length" class="knowledge-expansion-summary">
        本次获得 {{ expansionCandidates.length }} 条候选知识。以下内容尚未进入正式 RAG。
      </p>
      <article v-for="candidate in expansionCandidates" :key="candidate.sourceIdentifier" class="observation-card">
        <div>
          <strong>{{ candidate.title }}</strong>
          <el-tag>{{ candidate.venueCode }} {{ candidate.publicationYear }}</el-tag>
          <el-tag type="warning">候选知识</el-tag>
        </div>
        <p>{{ candidate.abstractText }}</p>
        <small>
          {{ candidate.authors.slice(0, 5).join('、') || '作者信息缺失' }} · 被引 {{ candidate.citedByCount }} 次 ·
          <a :href="candidate.sourceUrl" target="_blank" rel="noopener noreferrer">核对来源</a>
        </small>
        <div>
          <el-button type="success" plain
            :loading="draftingCandidateId === candidate.sourceIdentifier"
            @click="createExternalDraft(candidate)">生成待审核知识草稿</el-button>
        </div>
      </article>
      <el-empty v-if="!expansionCandidates.length && !expansionSearching" description="设置主题后检索 RAG 候选知识" />
    </section>
    <section class="panel">
      <div class="section-heading"><div><h2>RAG 检索调试</h2><p>直接查看 Top-K、引用和各路分数，不需要运行 Agent</p></div></div>
      <el-form label-position="top">
        <el-form-item label="测试问题"><el-input v-model="debug.query" placeholder="例如：EXIF 缺失能否证明图片由 AI 生成？" /></el-form-item>
        <el-form-item label="Top-K"><el-input-number v-model="debug.topK" :min="1" :max="20" /></el-form-item>
        <el-button type="primary" :loading="saving" @click="search">执行混合检索</el-button>
        <el-button v-if="auth.hasPermission('knowledge:upload')" :disabled="!evaluationCases.length" :loading="saving" @click="runEvaluation">运行评测集</el-button>
      </el-form>
      <article v-for="(result, index) in searchResults" :key="result.chunkId" class="observation-card">
        <div><strong>#{{ index + 1 }} {{ result.documentTitle }}</strong>
          <el-tag>v{{ result.documentVersion }} / Chunk {{ result.chunkIndex }}</el-tag></div>
        <p>{{ result.quote }}</p>
        <small>Hybrid {{ result.hybridScore.toFixed(4) }} · Vector {{ result.semanticScore.toFixed(4) }} · FTS {{ result.keywordScore.toFixed(4) }}</small>
        <div v-if="auth.hasPermission('knowledge:upload')">
          <el-button text type="success" :loading="saving" @click="addEvaluationCase(result)">将此项设为期望答案</el-button>
        </div>
      </article>
      <el-empty v-if="!searchResults.length" description="输入问题后查看实际召回结果" />
    </section>
    <section v-if="evaluationCases.length || evaluationRun" class="panel">
      <div class="section-heading"><div><h2>RAG 评测基线</h2><p>{{ evaluationCases.length }} 条评测用例</p></div></div>
      <div v-if="evaluationRun" class="metric-grid">
        <article class="panel"><span>Recall@{{ evaluationRun.topK }}</span><strong>{{ evaluationRun.recallAtK.toFixed(3) }}</strong></article>
        <article class="panel"><span>MRR</span><strong>{{ evaluationRun.mrr.toFixed(3) }}</strong></article>
        <article class="panel"><span>租户 / 草稿过滤</span><strong>{{ evaluationRun.tenantIsolationPassed && evaluationRun.draftExclusionPassed ? 'PASS' : 'FAIL' }}</strong></article>
        <article class="panel"><span>Citation 完整性</span><strong>{{ evaluationRun.citationIntegrityPassed ? 'PASS' : 'FAIL' }}</strong></article>
      </div>
      <article v-for="item in evaluationRun?.caseResults ?? []" :key="item.evaluationCaseId" class="checkpoint-card">
        <strong>{{ item.name }}</strong><p>{{ item.query }}</p>
        <small>{{ item.recalled ? `命中，排名 ${item.firstRelevantRank}` : 'Top-K 未命中' }}</small>
      </article>
      <article v-for="item in evaluationRun ? [] : evaluationCases" :key="item.id" class="checkpoint-card">
        <strong>{{ item.name }}</strong><p>{{ item.query }}</p><small>等待运行评测</small>
      </article>
    </section>
    <section class="panel">
      <div class="section-heading"><div><h2>知识文档</h2><p>{{ documents.length }} 份当前租户文档</p></div></div>
      <div v-for="document in documents" :key="document.id" class="observation-card">
        <div><strong>{{ document.title }}</strong> <el-tag>{{ document.documentType }}</el-tag>
          <el-tag type="info">{{ sourceScopeLabel(document.sourceScope) }} · 优先级 {{ document.sourcePriority }}</el-tag>
          <el-tag :type="document.status === 'PUBLISHED' ? 'success' : 'warning'">{{ document.status }}</el-tag></div>
        <p>发布版本 v{{ document.publishedVersion }} · 数据版本 {{ document.version }} · {{ formatDate(document.updatedAt) }}
          <template v-if="document.sourceVenue"> · {{ document.sourceVenue }} {{ document.sourceYear }}</template>
          <template v-if="document.sourceUrl"> · <a :href="document.sourceUrl" target="_blank" rel="noopener noreferrer">来源页面</a></template>
        </p>
        <pre>{{ document.content }}</pre>
        <el-button v-if="document.status === 'DRAFT' && auth.hasPermission('knowledge:publish')" type="success"
          plain :loading="saving" @click="publish(document)">发布并建立索引</el-button>
      </div><el-empty v-if="!documents.length" description="尚无知识文档" />
    </section>
  </main>
</template>

<style scoped>
.knowledge-expansion-filters { display: flex; flex-wrap: wrap; gap: 16px; }
.knowledge-expansion-summary { margin: 20px 0 8px; color: #8da4ae; }
.observation-card a { color: #48d8b0; }
</style>
