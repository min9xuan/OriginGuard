<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const isAgentResultPage = computed(() => route.path.startsWith('/analyze/agent-tasks/'))

async function logout() {
  await auth.logout()
  await router.replace({ path: '/login', query: { switched: '1' } })
}
</script>

<template>
  <div class="investigator-frame">
    <header class="investigator-header">
      <RouterLink class="public-brand" to="/"><span class="brand-symbol">OG</span><span><strong>OriginGuard</strong><small>Media authenticity research</small></span></RouterLink>
      <nav aria-label="调查员导航">
        <RouterLink to="/analyze">开始检测</RouterLink>
        <RouterLink to="/analyze/history">检测记录</RouterLink>
        <a class="header-github-link" href="https://github.com/min9xuan/OriginGuard" target="_blank" rel="noreferrer" aria-label="在 GitHub 查看 OriginGuard" title="GitHub">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 2C6.48 2 2 6.58 2 12.23c0 4.52 2.87 8.35 6.84 9.7.5.1.68-.22.68-.49 0-.24-.01-1.05-.02-1.9-2.78.62-3.37-1.2-3.37-1.2-.45-1.18-1.11-1.49-1.11-1.49-.91-.63.07-.62.07-.62 1 .08 1.53 1.06 1.53 1.06.9 1.56 2.34 1.11 2.91.85.09-.66.35-1.11.64-1.37-2.22-.26-4.56-1.14-4.56-5.06 0-1.12.39-2.03 1.03-2.75-.1-.26-.45-1.3.1-2.71 0 0 .84-.28 2.75 1.05A9.36 9.36 0 0 1 12 6.11c.85 0 1.7.12 2.5.35 1.91-1.33 2.75-1.05 2.75-1.05.55 1.41.2 2.45.1 2.71.64.72 1.03 1.63 1.03 2.75 0 3.93-2.34 4.8-4.57 5.05.36.32.68.94.68 1.9 0 1.37-.01 2.47-.01 2.81 0 .27.18.59.69.49A10.24 10.24 0 0 0 22 12.23C22 6.58 17.52 2 12 2Z"/></svg>
        </a>
        <span v-if="auth.user" class="session-user" :title="auth.user.username">已登录：{{ auth.user.displayName }}</span>
        <button type="button" @click="logout">退出并切换账号</button>
      </nav>
    </header>
    <section class="investigator-content" :class="{ 'agent-result-surface': isAgentResultPage }"><RouterView /></section>
  </div>
</template>
