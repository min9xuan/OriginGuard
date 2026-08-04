<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { onMounted, ref } from 'vue'
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
  if (!draft.value) return
  registering.value = true
  try {
    await mediaApi.register(draft.value, auth.accessToken)
    ElMessage.success('媒体记录已登记')
    selectedFile.value = null
    draft.value = null
    await load()
  } catch (error) {
    showError(error)
  } finally {
    registering.value = false
  }
}

function showError(error: unknown) {
  ElMessage.error(error instanceof ApiRequestError ? error.message : '请求失败，请稍后重试')
}

onMounted(load)
</script>

<template>
  <main class="page-shell">
    <header class="page-header split-header">
      <div>
        <p class="eyebrow">MEDIA REGISTRY</p>
        <h1>媒体资产记录</h1>
        <p>M1.1 只登记文件指纹与元数据，不会上传或保存文件内容。</p>
      </div>
      <el-tag type="warning" effect="plain">Metadata only</el-tag>
    </header>

    <section v-if="auth.hasPermission('asset:upload')" class="panel register-panel">
      <div>
        <h2>选择本地图片</h2>
        <p>SHA-256 在浏览器本地计算，后端按租户去重。</p>
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
      </el-table>
    </section>
  </main>
</template>
