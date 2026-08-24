<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const navigation = computed(() => [
  { label: '管理概览', path: '/admin' },
  { label: '媒体资产', path: '/admin/assets', permission: 'asset:read' },
  { label: '调查记录', path: '/admin/cases', permission: 'case:read' },
  { label: '取证知识库', path: '/admin/knowledge', permission: 'knowledge:read' },
  { label: '模型评测', path: '/admin/model-evaluation', permission: 'model:read' },
  { label: 'Agent 任务', path: '/admin/agent-tasks', permission: 'agent:trace:read' },
].filter((item) => !item.permission || auth.hasPermission(item.permission)))

async function logout() {
  await auth.logout()
  await router.replace('/admin/login')
}
</script>

<template>
  <div class="app-frame">
    <aside class="app-sidebar">
      <RouterLink class="brand" to="/admin"><span class="brand-mark">OG</span><span>OriginGuard</span></RouterLink>
      <span class="admin-context">ADMINISTRATION</span>
      <nav class="app-nav" aria-label="管理导航">
        <RouterLink v-for="item in navigation" :key="item.path" :to="item.path" :class="{ active: route.path === item.path || (item.path !== '/admin' && route.path.startsWith(item.path)) }">{{ item.label }}</RouterLink>
      </nav>
      <div v-if="auth.user" class="sidebar-user">
        <strong>{{ auth.user.displayName }}</strong><span>{{ auth.user.tenantCode }} · ADMIN</span>
        <el-button text @click="logout">退出登录</el-button>
      </div>
    </aside>
    <section class="app-content"><RouterView /></section>
  </div>
</template>
