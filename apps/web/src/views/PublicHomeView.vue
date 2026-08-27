<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const router = useRouter()
const actionLabel = computed(() => auth.authenticated ? '进入工作台' : '登录后分析')

async function logout() {
  await auth.logout()
  await router.push({ path: '/login', query: { switched: '1' } })
}

async function startAnalysis() {
  if (!auth.authenticated) {
    await router.push({ path: '/login', query: { redirect: '/analyze' } })
    return
  }
  await router.push(auth.user?.roles.includes('ADMIN') ? '/admin' : '/analyze')
}
</script>

<template>
  <main class="public-site">
    <header class="public-nav">
      <RouterLink class="public-logo" to="/">
        <span class="public-logo-mark">OG</span>
        <span><strong>OriginGuard</strong><small>Media authenticity research</small></span>
      </RouterLink>
      <nav aria-label="网站导航">
        <a href="#capabilities">能力</a>
        <a href="#architecture">架构</a>
        <a href="#workflow">工作方式</a>
        <a class="github-link" href="https://github.com/min9xuan/OriginGuard" target="_blank" rel="noreferrer" aria-label="在 GitHub 查看 OriginGuard" title="GitHub">
          <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M12 2C6.48 2 2 6.58 2 12.23c0 4.52 2.87 8.35 6.84 9.7.5.1.68-.22.68-.49 0-.24-.01-1.05-.02-1.9-2.78.62-3.37-1.2-3.37-1.2-.45-1.18-1.11-1.49-1.11-1.49-.91-.63.07-.62.07-.62 1 .08 1.53 1.06 1.53 1.06.9 1.56 2.34 1.11 2.91.85.09-.66.35-1.11.64-1.37-2.22-.26-4.56-1.14-4.56-5.06 0-1.12.39-2.03 1.03-2.75-.1-.26-.45-1.3.1-2.71 0 0 .84-.28 2.75 1.05A9.36 9.36 0 0 1 12 6.11c.85 0 1.7.12 2.5.35 1.91-1.33 2.75-1.05 2.75-1.05.55 1.41.2 2.45.1 2.71.64.72 1.03 1.63 1.03 2.75 0 3.93-2.34 4.8-4.57 5.05.36.32.68.94.68 1.9 0 1.37-.01 2.47-.01 2.81 0 .27.18.59.69.49A10.24 10.24 0 0 0 22 12.23C22 6.58 17.52 2 12 2Z"/></svg>
        </a>
        <span v-if="auth.authenticated" class="public-session-state">{{ auth.user?.displayName }} · 已登录</span>
        <button type="button" @click="startAnalysis">{{ actionLabel }}</button>
        <button v-if="auth.authenticated" type="button" class="public-switch-account" @click="logout">切换账号</button>
      </nav>
    </header>

    <section class="public-hero">
      <div class="public-hero-art" aria-hidden="true">
        <div class="hero-frame frame-one"></div>
        <div class="hero-frame frame-two"></div>
        <div class="hero-scan-line"></div>
        <span class="hero-coordinate coordinate-one">SEMANTIC / 0.84</span>
        <span class="hero-coordinate coordinate-two">FORENSIC SIGNAL / 0.61</span>
      </div>
      <div class="public-hero-copy">
        <p>MEDIA AUTHENTICITY AGENT</p>
        <h1>理解一张图像，<br />也追问它从何而来。</h1>
        <p class="hero-summary">OriginGuard 调度多种受控检测能力，整理媒体事实、模型信号与知识依据，给出可解释且可追溯的初步判断。</p>
        <button type="button" class="hero-action" @click="startAnalysis">
          {{ actionLabel }}<span aria-hidden="true">→</span>
        </button>
      </div>
    </section>

    <section id="capabilities" class="public-intro">
      <p class="section-label">核心能力</p>
      <div>
        <h2>不是让一个模型直接猜真假。</h2>
        <p>系统先理解媒体类型，再由 Agent 选择生成内容鉴别、文件完整性、元数据与取证知识等能力。每项结果都保留来源和限制，最终判断仍由使用者确认。</p>
      </div>
    </section>

    <section class="capability-grid" aria-label="系统能力">
      <article>
        <span>01</span>
        <h3>内容理解</h3>
        <p>识别摄影、插画、卡通、渲染图或界面截图，为后续检测选择正确的解释边界。</p>
      </article>
      <article>
        <span>02</span>
        <h3>受控取证</h3>
        <p>按声明式 Skill 调用检测模型和媒体工具，Observation 驱动后续重规划。</p>
      </article>
      <article>
        <span>03</span>
        <h3>证据解释</h3>
        <p>综合概率、质量门控、注意力图与知识依据，同时明确当前结论的能力边界。</p>
      </article>
    </section>

    <section id="architecture" class="public-architecture">
      <div class="architecture-diagram" role="img" aria-label="OriginGuard 从图片输入、媒体理解、Agent 路由、多模型检测到证据融合与人工确认的系统架构">
        <div class="architecture-column architecture-input">
          <article class="architecture-node input-node">
            <svg viewBox="0 0 40 40" aria-hidden="true"><rect x="5" y="7" width="30" height="25" rx="2"/><circle cx="27" cy="14" r="3"/><path d="M8 28l9-9 7 7 4-4 7 7"/></svg>
            <strong>图片输入</strong><small>原始媒体</small>
          </article>
          <span class="architecture-arrow">↓</span>
          <article class="architecture-node understanding-node">
            <span class="node-index">01</span><strong>媒体理解</strong><small>识别摄影、插画、渲染或界面</small>
          </article>
        </div>

        <span class="architecture-arrow horizontal">→</span>

        <div class="architecture-column architecture-agent">
          <article class="architecture-node agent-node">
            <span class="node-index">02</span>
            <svg viewBox="0 0 44 44" aria-hidden="true"><circle cx="22" cy="8" r="4"/><circle cx="9" cy="32" r="4"/><circle cx="35" cy="32" r="4"/><path d="M22 12v9M9 28v-7h26v7M22 21H9"/></svg>
            <strong>Agent 规划与路由</strong><small>Plan → Act → Observe → Replan / Stop</small>
          </article>
        </div>

        <span class="architecture-arrow horizontal">→</span>

        <div class="architecture-model-stack">
          <article class="architecture-node model-node"><span>受控能力 A</span><strong>通用生成内容鉴别</strong></article>
          <article class="architecture-node model-node active"><span>受控能力 B</span><strong>插画 / 卡通专用检测</strong></article>
          <article class="architecture-node model-node"><span>受控能力 C</span><strong>扩散重建复核</strong></article>
          <article class="architecture-node model-node"><span>事实与知识</span><strong>完整性 · 元数据 · RAG</strong></article>
        </div>

        <span class="architecture-arrow horizontal">→</span>

        <div class="architecture-column architecture-output">
          <article class="architecture-node fusion-node"><span class="node-index">03</span><strong>证据融合与解释</strong><small>保留概率、依据、限制与可视化</small></article>
          <span class="architecture-arrow">↓</span>
          <article class="architecture-node result-node"><strong>可核验的初步判断</strong><small>由使用者完成最终确认</small></article>
        </div>
      </div>
      <div class="architecture-copy">
        <p class="section-label">系统架构</p>
        <h2>按图片类型，选择真正合适的检测能力。</h2>
        <p>OriginGuard 不把所有图片交给同一个分类器。Agent 先理解内容域，再调用匹配的专用模型；必要时追加独立复核，最后由本地多模态模型解释不同信号为何支持或反对当前判断。</p>
        <a href="https://github.com/min9xuan/OriginGuard" target="_blank" rel="noreferrer"><span>→</span> 查看实现与运行说明</a>
      </div>
    </section>

    <section id="workflow" class="public-workflow">
      <div class="workflow-heading">
        <p class="section-label">工作方式</p>
        <h2>从媒体输入到可核验结论</h2>
      </div>
      <ol>
        <li><span>01</span><div><strong>上传媒体</strong><p>登记原始文件并校验内容指纹。</p></div></li>
        <li><span>02</span><div><strong>Agent 规划与执行</strong><p>Plan → Act → Observe → Replan / Stop。</p></div></li>
        <li><span>03</span><div><strong>确认分析结果</strong><p>查看依据、限制与运行过程，确认或返回补充调查。</p></div></li>
      </ol>
    </section>

    <section class="public-demo">
      <p class="section-label">在线分析</p>
      <h2>选择一张图片，查看它留下的线索。</h2>
      <button type="button" @click="startAnalysis">前往分析<span aria-hidden="true">→</span></button>
    </section>

    <footer class="public-footer">
      <strong>OriginGuard</strong>
      <span>面向 AIGC 媒体真实性调查的多模态 Agent 系统</span>
    </footer>
  </main>
</template>

<style scoped>
.public-site { min-height: 100vh; overflow-x: clip; color: #222b39; background: #f5f6f7; font-family: "Helvetica Neue", Arial, "Microsoft YaHei", sans-serif; }
.public-nav { position: absolute; z-index: 5; top: 0; left: 0; display: flex; width: 100%; height: 82px; align-items: center; justify-content: space-between; padding: 0 clamp(24px, 5vw, 76px); color: #222b39; border-bottom: 1px solid #d6dbe1; }
.public-logo { display: flex; align-items: center; gap: 12px; }
.public-logo-mark { display: grid; width: 40px; height: 40px; place-items: center; color: #fff; background: #263143; font-size: 13px; font-weight: 800; letter-spacing: .08em; }
.public-logo > span:last-child { display: grid; gap: 2px; }
.public-logo strong { font-size: 16px; }
.public-logo small { color: #626e7b; font-size: 12px; letter-spacing: .03em; }
.public-nav nav { display: flex; align-items: center; gap: 28px; font-size: 15px; }
.public-nav nav a { color: #596474; }
.public-nav nav a:hover { color: #182230; }
.public-nav nav .github-link { display: grid; width: 44px; height: 44px; place-items: center; color: #263143; border-radius: 50%; }
.public-nav nav .github-link:hover { background: #e5e8eb; }
.github-link svg { width: 29px; height: 29px; fill: currentColor; }
.public-nav nav button { min-height: 42px; padding: 0 20px; color: #fff; border: 1px solid #263143; background: #263143; font-weight: 700; cursor: pointer; }
.public-nav nav button:hover { background: #131b27; }
.public-session-state { color: #52606f; font-size: 12px; white-space: nowrap; }
.public-nav nav .public-switch-account { min-height: 38px; padding: 0 14px; color: #3e4a59; border-color: #aeb7c1; background: transparent; font-size: 12px; }
.public-nav nav .public-switch-account:hover { color: #fff; border-color: #263143; background: #263143; }
.public-hero { position: relative; display: grid; min-height: 710px; align-items: end; overflow: hidden; padding: 140px clamp(24px, 7vw, 112px) 78px; color: #222b39; background: #eef0f3; }
.public-hero::before { position: absolute; inset: 0; content: ""; opacity: .8; background: radial-gradient(circle at 78% 42%, rgba(188,200,207,.72), transparent 28%); }
.public-hero::after { position: absolute; inset: 0; content: ""; opacity: .4; background-image: linear-gradient(rgba(156,166,176,.25) 1px, transparent 1px), linear-gradient(90deg, rgba(156,166,176,.25) 1px, transparent 1px); background-size: 72px 72px; }
.public-hero-art { position: absolute; z-index: 1; top: 132px; right: max(4vw, 38px); width: min(44vw, 640px); height: 500px; }
.hero-frame { position: absolute; border: 1px solid rgba(71,85,101,.48); background: rgba(255,255,255,.28); backdrop-filter: blur(2px); }
.frame-one { inset: 34px 96px 72px 44px; transform: rotate(-3deg); }
.frame-two { inset: 88px 30px 18px 130px; border-color: rgba(77,121,116,.52); transform: rotate(4deg); }
.hero-scan-line { position: absolute; top: 48%; right: 0; left: 0; height: 1px; background: rgba(63,103,99,.62); }
.hero-coordinate { position: absolute; z-index: 2; padding: 5px 8px; color: #43515f; background: rgba(238,240,243,.94); font: 12px/1.4 monospace; letter-spacing: .06em; }
.coordinate-one { top: 10px; left: 36px; }.coordinate-two { right: 20px; bottom: 20px; }
.public-hero-copy { position: relative; z-index: 2; max-width: min(720px, 56vw); }
.public-hero-copy > p:first-child, .section-label { margin: 0; color: #4f5d6b; font-size: 15px; font-weight: 750; letter-spacing: .1em; text-transform: uppercase; }
.public-hero-copy h1 { max-width: 700px; margin: 22px 0 26px; font-size: clamp(44px, 5vw, 64px); font-weight: 520; letter-spacing: -.025em; line-height: 1.12; }
.hero-summary { max-width: 680px; color: #606c7b; font-size: 18px; line-height: 1.75; }
.hero-action, .public-demo button { display: inline-flex; min-width: 188px; min-height: 58px; align-items: center; justify-content: space-between; gap: 34px; margin-top: 34px; padding: 0 22px; color: #fff; border: 1px solid #263143; background: #263143; font-weight: 750; cursor: pointer; }
.hero-action:hover, .public-demo button:hover { background: #131b27; }
.hero-action span, .public-demo button span { font-size: 23px; font-weight: 400; }
.public-intro { display: grid; max-width: 1180px; margin: 0 auto; padding: 104px 34px 72px; grid-template-columns: 1fr 3fr; gap: 54px; background: #f5f6f7; }
.public-intro .section-label, .public-workflow .section-label, .public-demo .section-label { color: #4f5c69; }
.public-intro h2, .public-workflow h2, .public-demo h2 { max-width: 820px; margin: 0; font-size: clamp(34px, 3.8vw, 52px); font-weight: 520; letter-spacing: -.015em; line-height: 1.18; }
.public-intro div > p { max-width: 780px; margin: 34px 0 0; color: #68717c; font-size: 18px; line-height: 1.8; }
.capability-grid { display: grid; max-width: 1180px; margin: 0 auto; padding: 0 34px 108px; grid-template-columns: repeat(3, 1fr); }
.capability-grid article { min-height: 286px; padding: 28px 34px; border-top: 1px solid #aeb6bf; border-right: 1px solid #d2d7dc; background: #f8f9fa; }
.capability-grid article:last-child { border-right: 0; }
.capability-grid span { color: #536170; font: 15px monospace; }
.capability-grid h3 { margin: 68px 0 16px; font-size: 24px; font-weight: 600; }
.capability-grid p { color: #566372; font-size: 16px; line-height: 1.75; }
.public-architecture { display: grid; min-height: 700px; align-items: center; padding: 96px clamp(28px, 5vw, 80px); grid-template-columns: minmax(560px, 1.25fr) minmax(320px, .75fr); gap: clamp(42px, 5vw, 84px); border-top: 1px solid #d5dae0; background: #fafaf9; }
.architecture-diagram { display: grid; align-items: center; grid-template-columns: minmax(130px, .85fr) 34px minmax(150px, 1fr) 34px minmax(180px, 1.1fr) 34px minmax(150px, 1fr); gap: 10px; }
.architecture-column { display: flex; min-width: 0; align-items: center; flex-direction: column; }
.architecture-node { box-sizing: border-box; width: 100%; padding: 18px; border: 1px solid #b9c4cd; background: #f1f3f6; text-align: center; }
.architecture-node strong, .architecture-node small { display: block; }
.architecture-node strong { color: #263140; font-size: 15px; line-height: 1.35; }
.architecture-node small { margin-top: 7px; color: #5d6976; font-size: 12px; line-height: 1.5; }
.architecture-node svg { width: 42px; height: 42px; margin-bottom: 10px; fill: none; stroke: #697989; stroke-width: 1.5; }
.input-node { border-color: #86b7ad; background: #f4f8f7; }.input-node svg { stroke: #5f998d; }
.understanding-node { min-height: 164px; padding-top: 48px; border-color: #8998aa; }
.agent-node { min-height: 260px; padding-top: 48px; border: 2px solid #596b80; background: #edf0f4; }
.architecture-model-stack { display: grid; gap: 10px; }
.model-node { padding: 15px 12px; text-align: left; }
.model-node > span { display: block; margin-bottom: 6px; color: #667380; font: 12px monospace; text-transform: uppercase; }
.model-node.active { border-color: #70a397; background: #edf6f3; }
.fusion-node { min-height: 164px; padding-top: 45px; border-color: #78899b; }
.result-node { border: 2px solid #263143; color: #fff; background: #263143; }
.result-node strong, .result-node small { color: #fff; }.result-node small { opacity: .86; }
.node-index { display: block; margin-bottom: 9px; color: #5f6c79; font: 12px monospace; }
.architecture-arrow { display: block; padding: 12px 0; color: #475565; font-size: 23px; font-weight: 300; text-align: center; }.architecture-arrow.horizontal { padding: 0; }
.architecture-copy h2 { max-width: 620px; margin: 18px 0 22px; font-size: clamp(33px, 3vw, 46px); font-weight: 520; letter-spacing: -.02em; line-height: 1.17; }
.architecture-copy > p:not(.section-label) { max-width: 620px; color: #66717e; font-size: 17px; line-height: 1.8; }
.architecture-copy > a { display: inline-flex; align-items: center; gap: 14px; margin-top: 28px; color: #273342; font-weight: 700; }.architecture-copy > a span { display: grid; width: 34px; height: 34px; place-items: center; border: 1px solid #aab3bc; border-radius: 50%; }
.public-workflow { padding: 88px max(34px, calc((100vw - 1120px) / 2)); color: #222b39; border-top: 1px solid #d5dae0; border-bottom: 1px solid #d5dae0; background: #eceff2; }
.workflow-heading { display: block; }
.workflow-heading .section-label { margin-bottom: 18px; }
.public-workflow h2 { color: #222b39; }
.public-workflow .section-label { color: #4f5d6b; }
.public-workflow ol { width: min(900px, 100%); margin: 52px 0 0; padding: 0; list-style: none; }
.public-workflow li { display: grid; padding: 24px 0; grid-template-columns: 56px minmax(0, 1fr); border-top: 1px solid #b8c0c8; }
.public-workflow li > span { color: #536170; font: 14px monospace; }
.public-workflow li strong { font-size: 20px; font-weight: 600; }
.public-workflow li p { margin: 8px 0 0; color: #586574; font-size: 16px; }
.public-demo { padding: 92px 28px 102px; text-align: center; background: #f5f6f7; }
.public-demo h2 { margin: 24px auto 0; }
.public-demo h2 { max-width: 760px; font-size: clamp(34px, 3.6vw, 48px); }
.public-demo button { color: #fff; background: #263143; }
.public-footer { display: flex; align-items: center; justify-content: space-between; gap: 30px; padding: 36px clamp(24px, 5vw, 76px); color: #5f6b77; border-top: 1px solid #d5d8da; font-size: 14px; }
.public-footer strong { color: #28323f; font-size: 16px; }
@media (max-width: 1180px) {
  .public-architecture { min-height: auto; grid-template-columns: minmax(0, 1fr); gap: 64px; }
  .architecture-diagram { width: 100%; max-width: 900px; margin: 0 auto; }
  .architecture-copy { width: min(760px, 100%); }
}
@media (max-width: 760px) {
  .public-nav { height: 72px; }.public-logo small, .public-nav nav a:not(.github-link) { display: none; }.public-nav nav { gap: 12px; }.public-nav nav .github-link { padding-left: 0; border-left: 0; }
  .public-hero { min-height: 680px; padding-bottom: 58px; }.public-hero-art { top: 110px; right: -80px; width: 430px; height: 380px; opacity: .55; }
  .public-hero-copy { max-width: 100%; }.public-hero-copy h1 { font-size: clamp(40px, 11.5vw, 54px); line-height: 1.12; }.hero-summary { font-size: 16px; }
  .public-intro { grid-template-columns: 1fr; gap: 28px; padding-top: 88px; }
  .capability-grid { grid-template-columns: 1fr; }.capability-grid article { min-height: 220px; padding-inline: 0; border-right: 0; }.capability-grid h3 { margin-top: 50px; }
  .public-architecture { padding: 78px 24px; gap: 54px; }.architecture-diagram { grid-template-columns: 1fr; }.architecture-arrow.horizontal { transform: rotate(90deg); }.architecture-node { max-width: 360px; }.architecture-model-stack { width: 100%; max-width: 360px; }.architecture-agent, .architecture-input, .architecture-output { width: 100%; }.agent-node, .understanding-node, .fusion-node { min-height: auto; padding-top: 24px; }.architecture-copy h2 { font-size: clamp(34px, 10vw, 46px); }
  .public-workflow { padding-block: 78px; }.public-workflow ol { margin-top: 44px; }.public-workflow li { grid-template-columns: 46px 1fr; }
  .public-footer { align-items: flex-start; flex-direction: column; }
}
</style>
