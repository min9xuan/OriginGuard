<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { mediaApi } from '../api/media'
import { ApiRequestError } from '../api/http'
import { useAuthStore } from '../stores/auth'
import type { MediaAsset, RegisterAssetRequest } from '../types/business'
import { formatBytes, formatDate } from '../utils/format'
import { sha256Hex } from '../utils/sha256'

const auth = useAuthStore()
const assets = ref<MediaAsset[]>([])
const loading = ref(false)
const hashing = ref(false)
const registering = ref(false)
const selectedFile = ref<File | null>(null)
const draft = ref<RegisterAssetRequest | null>(null)
const previewUrl = ref('')
const previewName = ref('')
const previewVisible = ref(false)
const previewLoadingId = ref('')

async function load() {
  loading.value = true
  try {
    assets.value = await mediaApi.list(auth.accessToken)
  } catch (error) {
    showError(error)
  } finally {
    loading.value = false
  }
}

async function selectFile(event: Event) {
  const input = event.target as HTMLInputElement
  const file = input.files?.[0]
  if (!file) return
  selectedFile.value = file
  draft.value = null
  hashing.value = true
  try {
    draft.value = {
      originalFilename: file.name,
      contentType: file.type || 'application/octet-stream',
      byteSize: file.size,
      sha256: await sha256Hex(file),
    }
  } catch {
    ElMessage.error('浏览器无法计算该文件的 SHA-256')
  } finally {
    hashing.value = false
  }
}

async function register() {
  if (!draft.value || !selectedFile.value) return
  registering.value = true
  try {
    await mediaApi.upload(selectedFile.value, draft.value.sha256, auth.accessToken)
    ElMessage.success('媒体文件已安全存储并完成服务端基础检查')
    selectedFile.value = null
    draft.value = null
    await load()
  } catch (error) {
    showError(error)
  } finally {
    registering.value = false
  }
}

async function preview(asset: MediaAsset) {
  if (asset.storageStatus !== 'STORED' || previewLoadingId.value) return
  previewLoadingId.value = asset.id
  try {
    if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
    previewUrl.value = URL.createObjectURL(await mediaApi.content(asset.id, auth.accessToken))
    previewName.value = asset.originalFilename
    previewVisible.value = true
  } catch (error) {
    showError(error)
  } finally {
    previewLoadingId.value = ''
  }
}

function showError(error: unknown) {
  ElMessage.error(error instanceof ApiRequestError ? error.message : '请求失败，请稍后重试')
}

onMounted(load)
onBeforeUnmount(() => {
  if (previewUrl.value) URL.revokeObjectURL(previewUrl.value)
})
</script>

<template>
  <main class="page-shell">
    <header class="page-header split-header">
      <div>
        <p class="eyebrow">MEDIA REGISTRY</p>
        <h1>媒体资产记录</h1>
        <p>M3.1 将 JPEG/PNG 原文件存入 MinIO，并由服务端复核文件签名、解码与 SHA-256。</p>
      </div>
      <el-tag type="success" effect="plain">Stored & verified</el-tag>
    </header>

    <section v-if="auth.hasPermission('asset:upload')" class="panel register-panel">
      <div>
        <h2>选择本地图片</h2>
        <p>浏览器预计算 SHA-256；后端读取真实字节再次计算并校验，单文件最大 25 MB。</p>
      </div>
      <label class="file-picker">
        <input type="file" accept="image/*" @change="selectFile" />
        <span>{{ selectedFile?.name ?? '选择图片文件' }}</span>
      </label>
      <div v-if="hashing" class="muted">正在计算 SHA-256…</div>
      <div v-if="draft" class="fingerprint-preview">
        <div><span>类型</span><strong>{{ draft.contentType }}</strong></div>
        <div><span>大小</span><strong>{{ formatBytes(draft.byteSize) }}</strong></div>
        <div class="hash-row"><span>SHA-256</span><code>{{ draft.sha256 }}</code></div>
        <el-button type="primary" :loading="registering" @click="register">登记媒体记录</el-button>
      </div>
    </section>

    <section class="panel table-panel">
      <div class="section-heading">
        <div><h2>当前租户资产</h2><p>共 {{ assets.length }} 条记录</p></div>
        <el-button plain :loading="loading" @click="load">刷新</el-button>
      </div>
      <el-table :data="assets" v-loading="loading" empty-text="还没有媒体记录">
        <el-table-column prop="originalFilename" label="文件名" min-width="180" />
        <el-table-column prop="contentType" label="MIME" width="150" />
        <el-table-column label="大小" width="110">
          <template #default="scope">{{ formatBytes(scope.row.byteSize) }}</template>
        </el-table-column>
        <el-table-column label="SHA-256" min-width="230">
          <template #default="scope"><code class="hash-short">{{ scope.row.sha256 }}</code></template>
        </el-table-column>
        <el-table-column label="登记时间" width="180">
          <template #default="scope">{{ formatDate(scope.row.createdAt) }}</template>
        </el-table-column>
        <el-table-column label="内容" width="100">
          <template #default="scope">
            <el-button
              plain
              type="primary"
              :disabled="scope.row.storageStatus !== 'STORED'"
              :loading="previewLoadingId === scope.row.id"
              @click.stop="preview(scope.row)"
            >{{ scope.row.storageStatus === 'STORED' ? '预览' : '无原文件' }}</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-dialog v-model="previewVisible" :title="previewName" width="min(880px, 92vw)">
      <img v-if="previewUrl" class="media-preview-image" :src="previewUrl" :alt="previewName" />
    </el-dialog>
  </main>
</template>
