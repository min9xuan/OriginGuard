<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ApiRequestError } from '../api/http'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const router = useRouter()
const route = useRoute()
const submitting = ref(false)
const errorMessage = ref('')
const form = reactive({ tenantCode: 'demo', username: 'investigator', password: 'OriginGuard@123' })

async function submit() {
  submitting.value = true
  errorMessage.value = ''
  try {
    await auth.login(form)
    const redirect = typeof route.query.redirect === 'string' ? route.query.redirect : '/workspace'
    const resolved = router.resolve(redirect)
    const safeRedirect = resolved.matched.length > 0 && !resolved.matched.some((item) => item.meta.fallback)
    await router.replace(safeRedirect ? redirect : '/workspace')
  } catch (error) {
    errorMessage.value = error instanceof ApiRequestError ? error.message : '暂时无法登录，请稍后重试。'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <main class="auth-shell">
    <section class="auth-intro">
      <p class="eyebrow">ORIGINGUARD · IDENTITY</p>
      <h1>让每一次调查都有明确的责任边界。</h1>
      <p>调查、人工复核和系统配置使用不同权限。Agent 只继承发起用户已经拥有的能力。</p>
    </section>
    <el-form class="login-card" label-position="top" @submit.prevent="submit">
      <div>
        <p class="eyebrow">SECURE ACCESS</p>
        <h2>登录调查平台</h2>
      </div>
      <el-form-item label="租户代码">
        <el-input v-model="form.tenantCode" autocomplete="organization" />
      </el-form-item>
      <el-form-item label="用户名">
        <el-input v-model="form.username" autocomplete="username" />
      </el-form-item>
      <el-form-item label="密码">
        <el-input v-model="form.password" type="password" show-password autocomplete="current-password" />
      </el-form-item>
      <el-alert v-if="errorMessage" :title="errorMessage" type="error" :closable="false" />
      <el-button type="primary" native-type="submit" :loading="submitting">进入工作台</el-button>
      <p class="login-hint">本地账号：investigator / reviewer / admin</p>
    </el-form>
  </main>
</template>
