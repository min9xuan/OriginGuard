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
const form = reactive({ tenantCode: 'demo', username: '', password: '' })

async function submit() {
  submitting.value = true
  errorMessage.value = ''
  try {
    await auth.login(form)
    if (!auth.user?.roles.includes('ADMIN')) {
      await auth.logout()
      errorMessage.value = '该账号没有系统管理权限。'
      return
    }
    const requested = typeof route.query.redirect === 'string' ? route.query.redirect : '/admin'
    await router.replace(requested.startsWith('/admin') && requested !== '/admin/login' ? requested : '/admin')
  } catch (error) {
    errorMessage.value = error instanceof ApiRequestError ? error.message : '暂时无法登录管理端，请稍后重试。'
  } finally {
    submitting.value = false
  }
}
</script>

<template>
  <main class="admin-auth-shell">
    <section class="admin-auth-card">
      <div class="admin-auth-brand"><span>OG</span><strong>OriginGuard</strong></div>
      <p>ADMINISTRATION</p>
      <h1>系统管理登录</h1>
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="管理员账号"><el-input v-model="form.username" autocomplete="username" /></el-form-item>
        <el-form-item label="密码"><el-input v-model="form.password" type="password" show-password autocomplete="current-password" /></el-form-item>
        <el-alert v-if="errorMessage" :title="errorMessage" type="error" :closable="false" />
        <el-button type="primary" native-type="submit" :loading="submitting">进入管理端</el-button>
      </el-form>
    </section>
  </main>
</template>

<style scoped>
.admin-auth-shell { display: grid; min-height: 100vh; place-items: center; padding: 28px; color: #dce8ee; background: #0d1319; }
.admin-auth-card { width: min(420px, 100%); padding: 42px; border: 1px solid #2a3640; background: #151c23; }
.admin-auth-brand { display: flex; align-items: center; gap: 12px; margin-bottom: 64px; }
.admin-auth-brand span { display: grid; width: 38px; height: 38px; place-items: center; border: 1px solid #586773; font-size: 11px; }
.admin-auth-card > p { color: #788995; font-size: 11px; font-weight: 750; letter-spacing: .16em; }
.admin-auth-card h1 { margin: 12px 0 34px; font-size: 34px; font-weight: 560; }
.admin-auth-card :deep(.el-button) { width: 100%; min-height: 46px; margin-top: 10px; }
</style>
