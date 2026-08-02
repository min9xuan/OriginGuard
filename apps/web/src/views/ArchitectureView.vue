<script setup lang="ts">
const modules = ['身份与权限', '媒体资产', '调查案件', '证据', '知识与 RAG', 'Agent Runtime', '人工复核', '报告与审计']
</script>

<template>
  <main class="shell">
    <p class="eyebrow">ORIGINGUARD · M0</p>
    <h1>内容真实性调查平台</h1>
    <p class="lead">当前为架构骨架。业务能力、模型能力和 Agent 工作流将在后续里程碑逐步接入。</p>
    <section class="grid" aria-label="模块规划">
      <article v-for="module in modules" :key="module" class="card">{{ module }}</article>
    </section>
  </main>
</template>

