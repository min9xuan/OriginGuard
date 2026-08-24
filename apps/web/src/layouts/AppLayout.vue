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
  await router.replace('/')
}
</script>

<template>
  <div class="investigator-frame">
    <header class="investigator-header">
      <RouterLink class="public-brand" to="/"><span class="brand-symbol">OG</span><span><strong>OriginGuard</strong><small>Media authenticity research</small></span></RouterLink>
      <nav aria-label="调查员导航">
        <RouterLink to="/analyze">开始检测</RouterLink>
        <RouterLink to="/analyze/history">检测记录</RouterLink>
        <a class="header-github-link" href="https://github.com/min9xuan/OriginGuard" target="_blank" rel="noreferrer">GitHub ↗</a>
        <button type="button" @click="logout">退出</button>
      </nav>
    </header>
    <section class="investigator-content" :class="{ 'agent-result-surface': isAgentResultPage }"><RouterView /></section>
  </div>
</template>
