<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const props = withDefaults(defineProps<{
  logoutRedirect?: string
  dark?: boolean
  showWorkspace?: boolean
}>(), {
  logoutRedirect: '/login',
  dark: false,
  showWorkspace: false,
})

const auth = useAuthStore()
const router = useRouter()
const initials = computed(() => (auth.user?.displayName || auth.user?.username || 'U').trim().slice(0, 1).toUpperCase())
const roleLabel = computed(() => auth.user?.roles.includes('ADMIN') ? '系统管理员' : '已登录用户')

async function handleCommand(command: string) {
  if (command === 'workspace') {
    await router.push(auth.user?.roles.includes('ADMIN') ? '/admin' : '/analyze')
    return
  }
  if (command !== 'logout') return
  try {
    await auth.logout()
  } finally {
    await router.replace({ path: props.logoutRedirect, query: props.logoutRedirect === '/login' ? { switched: '1' } : {} })
  }
}
</script>

<template>
  <el-dropdown
    v-if="auth.authenticated && auth.user"
    trigger="click"
    placement="bottom-end"
    popper-class="account-dropdown-popper"
    @command="handleCommand"
  >
    <button
      type="button"
      class="account-menu-trigger"
      :class="{ dark }"
      aria-label="打开个人账户菜单"
      title="个人账户"
    >
      <span class="account-menu-avatar">{{ initials }}</span>
      <span class="account-menu-copy"><strong>{{ auth.user.displayName }}</strong><small>已登录</small></span>
      <svg viewBox="0 0 16 16" aria-hidden="true"><path d="m4 6 4 4 4-4" /></svg>
    </button>
    <template #dropdown>
      <el-dropdown-menu>
        <el-dropdown-item disabled class="account-dropdown-identity">
          <span><strong>{{ auth.user.displayName }}</strong><small>@{{ auth.user.username }} · {{ roleLabel }}</small></span>
        </el-dropdown-item>
        <el-dropdown-item v-if="showWorkspace" command="workspace">进入工作台</el-dropdown-item>
        <el-dropdown-item divided command="logout">退出登录</el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>
