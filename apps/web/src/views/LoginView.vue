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
    if (auth.user?.roles.includes('ADMIN')) {
      await auth.logout()
      errorMessage.value = '该入口仅供普通用户登录。'
      return
    }
    const defaultRoute = '/analyze'
    const redirect = typeof route.query.redirect === 'string' && !route.query.redirect.startsWith('/admin')
      ? route.query.redirect : defaultRoute
    const resolved = router.resolve(redirect)
    const safeRedirect = resolved.matched.length > 0 && !resolved.matched.some((item) => item.meta.fallback)
    await router.replace(safeRedirect ? redirect : defaultRoute)
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
      <RouterLink class="auth-brand" to="/"><span>OG</span><strong>OriginGuard</strong></RouterLink>
      <p class="eyebrow">MEDIA AUTHENTICITY AGENT</p>
      <h1>从一张图片，<br />开始真实性分析。</h1>
      <p>登录后上传媒体，查看 Agent 的检测步骤、证据依据与可解释初步判断。</p>
    </section>
    <el-form class="login-card" label-position="top" @submit.prevent="submit">
      <div>
        <p class="eyebrow">CONTINUE TO ANALYSIS</p>
        <h2>登录</h2>
      </div>
      <el-form-item label="账号">
        <el-input v-model="form.username" autocomplete="username" />
      </el-form-item>
      <el-form-item label="密码">
        <el-input v-model="form.password" type="password" show-password autocomplete="current-password" />
      </el-form-item>
      <el-alert v-if="errorMessage" :title="errorMessage" type="error" :closable="false" />
      <el-button type="primary" native-type="submit" :loading="submitting">继续分析</el-button>
      <RouterLink class="back-home" to="/">返回产品首页</RouterLink>
    </el-form>
  </main>
</template>

<style scoped>
.auth-shell { position: relative; color: #222b39; background: #f5f6f7; font-family: "Helvetica Neue", Arial, "Microsoft YaHei", sans-serif; }
.auth-shell::before { position: absolute; inset: 0 50% 0 0; content: ""; opacity: .28; background-image: linear-gradient(#aeb7be 1px, transparent 1px), linear-gradient(90deg, #aeb7be 1px, transparent 1px); background-size: 64px 64px; }
.auth-intro, .login-card { position: relative; z-index: 1; min-height: 100vh; }
.auth-intro { display: flex; justify-content: center; flex-direction: column; padding: clamp(48px, 7vw, 112px); }
.auth-brand { display: inline-flex; align-items: center; gap: 12px; margin-bottom: 88px; }
.auth-brand span { display: grid; width: 38px; height: 38px; place-items: center; color: #fff; background: #2d3947; font-size: 11px; }
.auth-intro h1 { color: #202938; font-size: clamp(48px, 5vw, 76px); font-weight: 520; letter-spacing: -.025em; line-height: 1.1; }
.auth-intro > p:last-child { color: #68727e; }
.login-card { align-content: center; color: #222b39; border: 0; border-left: 1px solid #d3d8de; border-radius: 0; padding: clamp(48px, 8vw, 124px); background: #eef0f3; box-shadow: none; }
.login-card .eyebrow { color: #697586; font-size: 11px; }
.login-card h2 { font-weight: 560; }
.login-card :deep(.el-form-item__label) { color: #536071; }
.login-card :deep(.el-input__wrapper) { min-height: 48px; color: #222b39; background: #fff !important; box-shadow: 0 0 0 1px #b9c1ca inset !important; }
.login-card :deep(.el-input__inner) { color: #222b39 !important; }
.login-card :deep(.el-input__wrapper.is-focus) { box-shadow: 0 0 0 2px #263143 inset !important; }
.login-card :deep(.el-button--primary) { min-height: 52px; border-color: #263143; border-radius: 0; background: #263143; }
.login-card :deep(.el-button--primary:hover) { background: #131b27; }
.back-home { margin-top: 12px; color: #647080; font-size: 12px; text-align: center; }
.back-home:hover { color: #202938; }
@media (max-width: 760px) {
  .auth-shell { grid-template-columns: 1fr; gap: 0; padding: 0; }.auth-shell::before { inset: 0; }.auth-intro, .login-card { min-height: auto; padding: 52px 24px; }.auth-brand { margin-bottom: 54px; }
}
</style>
