<script setup lang="ts">
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const router = useRouter()

async function logout() {
  await auth.logout()
  await router.replace('/login')
}
</script>

<template>
  <main class="workspace-shell">
    <header class="workspace-header">
      <div>
        <p class="eyebrow">ORIGINGUARD · WORKSPACE</p>
        <h1>身份与权限基础已连接</h1>
      </div>
      <el-button plain @click="logout">退出登录</el-button>
    </header>

    <section v-if="auth.user" class="identity-grid">
      <article class="identity-card identity-primary">
        <span>当前身份</span>
        <strong>{{ auth.user.displayName }}</strong>
        <p>@{{ auth.user.username }} · {{ auth.user.tenantCode }}</p>
      </article>
      <article class="identity-card">
        <span>角色</span>
        <div class="tag-list">
          <el-tag v-for="role in auth.user.roles" :key="role" effect="dark">{{ role }}</el-tag>
        </div>
      </article>
      <article class="identity-card permission-card">
        <span>后端授予的权限</span>
        <div class="permission-list">
          <code v-for="permission in auth.user.permissions" :key="permission">{{ permission }}</code>
        </div>
      </article>
    </section>
  </main>
</template>

