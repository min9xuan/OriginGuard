<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { computed, onBeforeUnmount, ref } from 'vue'
import { useRouter } from 'vue-router'
import { agentApi } from '../api/agents'
import { caseApi } from '../api/cases'
import { ApiRequestError } from '../api/http'
import { mediaApi } from '../api/media'
import { useAuthStore } from '../stores/auth'
import type { MediaAsset } from '../types/business'
import { formatBytes } from '../utils/format'
import { detectImageFileType, type SupportedImageType } from '../utils/image-signature'
import { sha256Hex } from '../utils/sha256'

const auth = useAuthStore()
const router = useRouter()
const fileInput = ref<HTMLInputElement | null>(null)
const selectedFile = ref<File | null>(null)
const selectedContentType = ref<SupportedImageType | null>(null)
const previewUrl = ref('')
const hashing = ref(false)
const starting = ref(false)
const sha256 = ref('')
const dragActive = ref(false)

const canStart = computed(() => Boolean(
  selectedFile.value && selectedContentType.value && sha256.value && !hashing.value && !starting.value,
))

function openPicker() {
  fileInput.value?.click()
}

async function onFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (file) await selectFile(file)
  input.value = ''
}

async function onDrop(event: DragEvent) {
  dragActive.value = false
  const file = event.dataTransfer?.files?.[0]
  if (file) await selectFile(file)
}

async function selectFile(file: File) {
  const detectedContentType = await detectImageFileType(file)
  if (!detectedContentType) {
    ElMessage.warning('当前仅支持 JPEG、PNG 和 WebP 图片，请确认文件真实格式')
    return
  }
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
  selectedFile.value = file
  selectedContentType.value = detectedContentType
  previewUrl.value = URL.createObjectURL(file)
  sha256.value = ''
  hashing.value = true
  try {
    sha256.value = await sha256Hex(file)
  } catch {
    ElMessage.error('浏览器无法读取该图片，请重新选择')
  } finally {
    hashing.value = false
  }
}

function clearSelection() {
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
  previewUrl.value = ''
  selectedFile.value = null
  selectedContentType.value = null
  sha256.value = ''
}

async function startDetection() {
  if (!selectedFile.value || !selectedContentType.value || !sha256.value) return
  starting.value = true
  try {
    const existing = (await mediaApi.list(auth.accessToken))
      .find((asset) => asset.sha256 === sha256.value)
    const asset: MediaAsset = existing ?? await mediaApi.upload(
      selectedFile.value,
      sha256.value,
      auth.accessToken,
      selectedContentType.value,
    )
    const created = await caseApi.create({
      title: `图像真实性检测 · ${selectedFile.value.name}`,
      description: '通过 OriginGuard Agent 自动执行媒体类型识别、完整性检查、AIGC 检测与取证知识检索。',
      priority: 'NORMAL',
      assetIds: [asset.id],
    }, auth.accessToken)
    const caseId = created.investigationCase.id
    await caseApi.transition(caseId, 'READY', 0, auth.accessToken)
    await caseApi.transition(caseId, 'INVESTIGATING', 1, auth.accessToken)
    const task = await agentApi.create(
      caseId,
      '识别媒体类型并综合文件完整性、元数据、生成内容鉴别、CLIP 与 RAG 取证知识，给出可解释的 AIGC 初步判断',
      13,
      auth.accessToken,
    )
    await router.push({ path: `/analyze/agent-tasks/${task.task.id}`, query: { autorun: '1' } })
  } catch (error) {
    ElMessage.error(error instanceof ApiRequestError ? error.message : '无法启动检测，请稍后重试')
  } finally {
    starting.value = false
  }
}

onBeforeUnmount(() => {
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
})
</script>

<template>
  <main class="detector-home">
    <section class="detector-hero">
      <p class="detector-kicker">ORIGINGUARD IMAGE FORENSICS</p>
      <h1>判断一张图片，<br />是否由 AI 生成。</h1>
      <p class="detector-lead">上传图片后，Agent 会选择合适的取证能力，整合模型检测、媒体事实与知识依据，并展示完整分析过程。</p>
    </section>

    <section id="detector" class="detector-demo">
      <div class="detector-demo-heading">
        <span>在线检测</span>
        <h2>{{ selectedFile ? '确认图片并开始分析' : '选择一张需要检测的图片' }}</h2>
        <p>图片仅用于本次真实性分析，检测结果需要由你最终确认。</p>
      </div>

      <input ref="fileInput" class="visually-hidden" type="file" accept=".jpg,.jpeg,.png,.webp,image/jpeg,image/png,image/webp" @change="onFileChange" />
      <button
        v-if="!selectedFile"
        type="button"
        class="detector-dropzone"
        :class="{ active: dragActive }"
        @click="openPicker"
        @dragenter.prevent="dragActive = true"
        @dragover.prevent="dragActive = true"
        @dragleave.prevent="dragActive = false"
        @drop.prevent="onDrop"
      >
        <span class="dropzone-icon">＋</span>
        <strong>选择图片</strong>
        <small>支持 JPEG、PNG、WebP · 最大 25 MB</small>
      </button>

      <div v-else class="detector-selection">
        <div class="detector-preview"><img :src="previewUrl" :alt="selectedFile.name" /></div>
        <div class="detector-file-info">
          <span>待检测图片</span>
          <strong>{{ selectedFile.name }}</strong>
          <small>{{ selectedContentType }} · {{ formatBytes(selectedFile.size) }}</small>
          <p>{{ hashing ? '正在读取图片指纹…' : '图片准备完成。点击下方按钮后将自动建立调查并运行 Agent。' }}</p>
          <div class="detector-actions">
            <button type="button" class="detector-primary" :disabled="!canStart" @click="startDetection">
              {{ starting ? '正在建立分析任务…' : '开始检测' }} <span aria-hidden="true">→</span>
            </button>
            <button type="button" class="detector-secondary" :disabled="starting" @click="clearSelection">重新选择</button>
          </div>
        </div>
      </div>
    </section>

    <section class="detector-method">
      <span>分析流程</span>
      <div><strong>01</strong><p>理解图片类型与调查上下文</p></div>
      <div><strong>02</strong><p>运行生成内容鉴别、完整性和元数据分析</p></div>
      <div><strong>03</strong><p>由 LLM 综合证据并解释初步结论</p></div>
    </section>
  </main>
</template>
