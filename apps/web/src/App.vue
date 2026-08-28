<script setup lang="ts">
import { computed } from 'vue'
import { RouterView } from 'vue-router'
import { useAuthStore } from './stores/auth'

const auth = useAuthStore()
const initialPath = window.location.pathname
const showBootstrap = computed(() => !auth.initialized && initialPath !== '/')
</script>

<template>
  <RouterView />
  <Transition name="bootstrap-fade">
    <div v-if="showBootstrap" class="app-bootstrap" role="status" aria-live="polite">
      <div class="app-bootstrap-brand"><span>OG</span><strong>OriginGuard</strong></div>
      <div class="app-bootstrap-progress"><i></i></div>
      <p>正在恢复工作区状态…</p>
    </div>
  </Transition>
</template>
