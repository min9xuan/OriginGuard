<script setup lang="ts">
import { computed } from 'vue'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const capabilities = computed(() => [
  { label: '媒体资产', value: auth.hasPermission('asset:read') ? '可访问' : '不可访问' },
  { label: '创建案件', value: auth.hasPermission('case:create') ? '可执行' : '只读/不可用' },
  { label: '人工审核', value: auth.hasPermission('review:approve') ? '可执行' : '不可执行' },
])
</script>

<template>
  <main class="page-shell">
    <header class="page-header">
      <div>
        <p class="eyebrow">ORIGINGUARD · M1.1</p>
        <h1>调查业务工作台</h1>
        <p>媒体记录与案件骨架已接入身份安全链，所有数据限定在当前租户。</p>
      </div>
    </header>

    <section v-if="auth.user" class="metric-grid">
      <article class="panel accent-panel">
        <span>当前身份</span>
        <strong>{{ auth.user.displayName }}</strong>
        <p>@{{ auth.user.username }} · {{ auth.user.tenantCode }}</p>
      </article>
      <article v-for="item in capabilities" :key="item.label" class="panel metric-card">
        <span>{{ item.label }}</span>
        <strong>{{ item.value }}</strong>
      </article>
    </section>

    <section class="quick-actions">
      <RouterLink v-if="auth.hasPermission('asset:read')" class="action-card" to="/assets">
        <span>01</span><strong>媒体资产</strong><small>登记文件指纹与基础元数据</small>
      </RouterLink>
      <RouterLink v-if="auth.hasPermission('case:read')" class="action-card" to="/cases">
        <span>02</span><strong>调查案件</strong><small>查看案件状态、媒体与审计时间线</small>
      </RouterLink>
      <RouterLink v-if="auth.hasPermission('case:create')" class="action-card" to="/cases/new">
        <span>03</span><strong>创建案件</strong><small>建立调查容器并关联媒体记录</small>
      </RouterLink>
    </section>
  </main>
</template>
