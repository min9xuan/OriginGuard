<script setup lang="ts">
import { ElMessage } from 'element-plus'
import { onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { caseApi } from '../api/cases'
import { ApiRequestError } from '../api/http'
import { mediaApi } from '../api/media'
import { useAuthStore } from '../stores/auth'
import type { CasePriority, MediaAsset } from '../types/business'
import { formatBytes } from '../utils/format'

const auth = useAuthStore()
const router = useRouter()
const assets = ref<MediaAsset[]>([])
const submitting = ref(false)
const form = reactive({
  title: '',
  description: '',
  priority: 'NORMAL' as CasePriority,
  assetIds: [] as string[],
})

async function loadAssets() {
  try {
    assets.value = await mediaApi.list(auth.accessToken)
  } catch (error) {
    ElMessage.error(error instanceof ApiRequestError ? error.message : '无法加载媒体资产')
  }
}

async function submit() {
  if (!form.title.trim()) {
    ElMessage.warning('请填写案件标题')
    return
  }
  submitting.value = true
  try {
    const created = await caseApi.create({ ...form }, auth.accessToken)
    await router.replace(`/cases/${created.investigationCase.id}`)
  } catch (error) {
    ElMessage.error(error instanceof ApiRequestError ? error.message : '创建案件失败')
  } finally {
    submitting.value = false
  }
}

onMounted(loadAssets)
</script>

<template>
  <main class="page-shell narrow-page">
    <header class="page-header">
      <p class="eyebrow">NEW CASE</p>
      <h1>创建调查案件</h1>
      <p>案件创建后处于 DRAFT，可继续编辑和关联媒体。</p>
    </header>

    <el-form class="panel form-panel" label-position="top" @submit.prevent="submit">
      <el-form-item label="案件标题" required>
        <el-input v-model="form.title" maxlength="200" show-word-limit />
      </el-form-item>
      <el-form-item label="调查说明">
        <el-input v-model="form.description" type="textarea" :rows="5" maxlength="2000" show-word-limit />
      </el-form-item>
      <el-form-item label="优先级">
        <el-select v-model="form.priority">
          <el-option v-for="item in ['LOW', 'NORMAL', 'HIGH', 'CRITICAL']" :key="item" :label="item" :value="item" />
        </el-select>
      </el-form-item>
      <el-form-item label="关联媒体记录">
        <el-select v-model="form.assetIds" multiple filterable placeholder="可暂不关联">
          <el-option
            v-for="asset in assets"
            :key="asset.id"
            :label="`${asset.originalFilename} · ${formatBytes(asset.byteSize)}`"
            :value="asset.id"
          />
        </el-select>
      </el-form-item>
      <div class="form-actions">
        <el-button @click="router.back()">取消</el-button>
        <el-button type="primary" native-type="submit" :loading="submitting">创建案件</el-button>
      </div>
    </el-form>
  </main>
</template>
