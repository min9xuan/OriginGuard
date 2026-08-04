<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()

const navigation = computed(() =>
  [
    { label: '工作台', path: '/workspace', permission: '' },
    { label: '媒体资产', path: '/assets', permission: 'asset:read' },
    { label: '调查案件', path: '/cases', permission: 'case:read' },
  ].filter((item) => !item.permission || auth.hasPermission(item.permission)),
)

async function logout() {
  await auth.logout()
  await router.replace('/login')
}
</script>

<template>
  <div class="app-frame">
    <aside class="app-sidebar">
      <RouterLink class="brand" to="/workspace">
        <span class="brand-mark">OG</span>
        <span>OriginGuard</span>
      </RouterLink>
      <nav class="app-nav" aria-label="主导航">
        <RouterLink
          v-for="item in navigation"
          :key="item.path"
          :to="item.path"
          :class="{ active: route.path === item.path || (item.path !== '/workspace' && route.path.startsWith(item.path)) }"
        >
          {{ item.label }}
        </RouterLink>
      </nav>
      <div v-if="auth.user" class="sidebar-user">
        <strong>{{ auth.user.displayName }}</strong>
        <span>{{ auth.user.tenantCode }} · {{ auth.user.roles.join(' / ') }}</span>
        <el-button text @click="logout">退出登录</el-button>
      </div>
    </aside>
    <section class="app-content">
      <RouterView />
    </section>
  </div>
</template>
