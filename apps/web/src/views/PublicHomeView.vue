<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const router = useRouter()
const actionLabel = computed(() => auth.authenticated ? '继续分析' : '开始分析')

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
        <a href="#workflow">工作方式</a>
        <a class="github-link" href="https://github.com/min9xuan/OriginGuard" target="_blank" rel="noreferrer">GitHub ↗</a>
        <button type="button" @click="startAnalysis">{{ actionLabel }}</button>
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
.public-site { min-height: 100vh; color: #222b39; background: #f5f6f7; font-family: "Helvetica Neue", Arial, "Microsoft YaHei", sans-serif; }
.public-nav { position: absolute; z-index: 5; top: 0; left: 0; display: flex; width: 100%; height: 82px; align-items: center; justify-content: space-between; padding: 0 clamp(24px, 5vw, 76px); color: #222b39; border-bottom: 1px solid #d6dbe1; }
.public-logo { display: flex; align-items: center; gap: 12px; }
.public-logo-mark { display: grid; width: 38px; height: 38px; place-items: center; color: #fff; background: #263143; font-size: 11px; font-weight: 800; letter-spacing: .08em; }
.public-logo > span:last-child { display: grid; gap: 2px; }
.public-logo strong { font-size: 16px; }
.public-logo small { color: #7b8490; font-size: 10px; letter-spacing: .04em; }
.public-nav nav { display: flex; align-items: center; gap: 28px; font-size: 13px; }
.public-nav nav a { color: #596474; }
.public-nav nav a:hover { color: #182230; }
.public-nav nav .github-link { padding-left: 28px; border-left: 1px solid #cfd5db; }
.public-nav nav button { min-height: 42px; padding: 0 20px; color: #fff; border: 1px solid #263143; background: #263143; font-weight: 700; cursor: pointer; }
.public-nav nav button:hover { background: #131b27; }
.public-hero { position: relative; display: grid; min-height: 780px; align-items: end; overflow: hidden; padding: 150px clamp(24px, 7vw, 112px) 88px; color: #222b39; background: #eef0f3; }
.public-hero::before { position: absolute; inset: 0; content: ""; opacity: .8; background: radial-gradient(circle at 78% 42%, rgba(188,200,207,.72), transparent 28%); }
.public-hero::after { position: absolute; inset: 0; content: ""; opacity: .4; background-image: linear-gradient(rgba(156,166,176,.25) 1px, transparent 1px), linear-gradient(90deg, rgba(156,166,176,.25) 1px, transparent 1px); background-size: 72px 72px; }
.public-hero-art { position: absolute; z-index: 1; top: 132px; right: max(4vw, 38px); width: min(44vw, 640px); height: 500px; }
.hero-frame { position: absolute; border: 1px solid rgba(71,85,101,.48); background: rgba(255,255,255,.28); backdrop-filter: blur(2px); }
.frame-one { inset: 34px 96px 72px 44px; transform: rotate(-3deg); }
.frame-two { inset: 88px 30px 18px 130px; border-color: rgba(77,121,116,.52); transform: rotate(4deg); }
.hero-scan-line { position: absolute; top: 48%; right: 0; left: 0; height: 1px; background: rgba(63,103,99,.62); }
.hero-coordinate { position: absolute; z-index: 2; padding: 4px 7px; color: #52606d; background: rgba(238,240,243,.9); font: 10px/1.4 monospace; letter-spacing: .08em; }
.coordinate-one { top: 10px; left: 36px; }.coordinate-two { right: 20px; bottom: 20px; }
.public-hero-copy { position: relative; z-index: 2; max-width: min(720px, 56vw); }
.public-hero-copy > p:first-child, .section-label { margin: 0; color: #677383; font-size: 12px; font-weight: 700; letter-spacing: .14em; text-transform: uppercase; }
.public-hero-copy h1 { max-width: 760px; margin: 24px 0 28px; font-size: clamp(52px, 6.2vw, 88px); font-weight: 520; letter-spacing: -.025em; line-height: 1.08; }
.hero-summary { max-width: 680px; color: #606c7b; font-size: 18px; line-height: 1.75; }
.hero-action, .public-demo button { display: inline-flex; min-width: 188px; min-height: 58px; align-items: center; justify-content: space-between; gap: 34px; margin-top: 34px; padding: 0 22px; color: #fff; border: 1px solid #263143; background: #263143; font-weight: 750; cursor: pointer; }
.hero-action:hover, .public-demo button:hover { background: #131b27; }
.hero-action span, .public-demo button span { font-size: 23px; font-weight: 400; }
.public-intro { display: grid; max-width: 1220px; margin: 0 auto; padding: 124px 34px 88px; grid-template-columns: 1fr 3fr; gap: 60px; background: #f5f6f7; }
.public-intro .section-label, .public-workflow .section-label, .public-demo .section-label { color: #65707d; }
.public-intro h2, .public-workflow h2, .public-demo h2 { max-width: 920px; margin: 0; font-size: clamp(42px, 5.2vw, 72px); font-weight: 520; letter-spacing: -.015em; line-height: 1.12; }
.public-intro div > p { max-width: 780px; margin: 34px 0 0; color: #68717c; font-size: 18px; line-height: 1.8; }
.capability-grid { display: grid; max-width: 1220px; margin: 0 auto; padding: 0 34px 132px; grid-template-columns: repeat(3, 1fr); }
.capability-grid article { min-height: 286px; padding: 28px 34px; border-top: 1px solid #aeb6bf; border-right: 1px solid #d2d7dc; background: #f8f9fa; }
.capability-grid article:last-child { border-right: 0; }
.capability-grid span { color: #7b848e; font: 12px monospace; }
.capability-grid h3 { margin: 76px 0 18px; font-size: 27px; font-weight: 560; }
.capability-grid p { color: #68717c; line-height: 1.75; }
.public-workflow { padding: 120px max(34px, calc((100vw - 1150px) / 2)); color: #222b39; border-top: 1px solid #d5dae0; border-bottom: 1px solid #d5dae0; background: #eceff2; }
.workflow-heading { display: grid; grid-template-columns: 1fr 3fr; gap: 60px; }
.public-workflow h2 { color: #222b39; }
.public-workflow .section-label { color: #677383; }
.public-workflow ol { margin: 90px 0 0 25%; padding: 0; list-style: none; }
.public-workflow li { display: grid; padding: 27px 0; grid-template-columns: 80px 1fr; border-top: 1px solid #b8c0c8; }
.public-workflow li > span { color: #778391; font: 12px monospace; }
.public-workflow li strong { font-size: 22px; font-weight: 560; }
.public-workflow li p { margin: 8px 0 0; color: #687482; }
.public-demo { padding: 130px 28px 142px; text-align: center; background: #f5f6f7; }
.public-demo h2 { margin: 24px auto 0; }
.public-demo button { color: #fff; background: #263143; }
.public-footer { display: flex; align-items: center; justify-content: space-between; gap: 30px; padding: 36px clamp(24px, 5vw, 76px); color: #7b838c; border-top: 1px solid #d5d8da; font-size: 13px; }
.public-footer strong { color: #28323f; font-size: 16px; }
@media (max-width: 760px) {
  .public-nav { height: 72px; }.public-logo small, .public-nav nav a:not(.github-link) { display: none; }.public-nav nav { gap: 12px; }.public-nav nav .github-link { padding-left: 0; border-left: 0; }
  .public-hero { min-height: 720px; padding-bottom: 64px; }.public-hero-art { top: 110px; right: -80px; width: 430px; height: 380px; opacity: .62; }
  .public-hero-copy { max-width: 100%; }.public-hero-copy h1 { font-size: clamp(44px, 13vw, 66px); line-height: 1.1; }.hero-summary { font-size: 16px; }
  .public-intro, .workflow-heading { grid-template-columns: 1fr; gap: 28px; }.public-intro { padding-top: 88px; }
  .capability-grid { grid-template-columns: 1fr; }.capability-grid article { min-height: 220px; padding-inline: 0; border-right: 0; }.capability-grid h3 { margin-top: 50px; }
  .public-workflow { padding-block: 88px; }.public-workflow ol { margin: 64px 0 0; }.public-workflow li { grid-template-columns: 54px 1fr; }
  .public-footer { align-items: flex-start; flex-direction: column; }
}
</style>
