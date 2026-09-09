<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { assistantApi } from '../api/assistant'
import { agentApi } from '../api/agents'
import { ApiRequestError } from '../api/http'
import { mediaApi } from '../api/media'
import { useAuthStore } from '../stores/auth'
import type {
  AssistantConversation,
  AssistantConversationDetails,
  AssistantGroundingSource,
  AssistantMessage,
  WebSecurityInvestigationReport,
} from '../types/assistant'
import type { MediaAsset } from '../types/business'
import { formatBytes, formatDate } from '../utils/format'
import { detectImageFileType, type SupportedImageType } from '../utils/image-signature'
import { renderAssistantMarkdown } from '../utils/assistant-markdown'
import { sha256Hex } from '../utils/sha256'

const auth = useAuthStore()
const conversations = ref<AssistantConversation[]>([])
const current = ref<AssistantConversationDetails | null>(null)
const prompt = ref('')
const sending = ref(false)
const loading = ref(true)
const fileInput = ref<HTMLInputElement | null>(null)
const messageList = ref<HTMLElement | null>(null)
interface SelectedAttachment {
  file: File
  contentType: SupportedImageType
  preview: string
  sha256: string
}
const selectedAttachments = ref<SelectedAttachment[]>([])
const preparingFile = ref(false)
let taskProgressAbort: AbortController | null = null

const canSend = computed(() => Boolean(
  current.value && prompt.value.trim() && !sending.value && !preparingFile.value,
))

onMounted(loadWorkbench)
onBeforeUnmount(() => {
  clearAttachment()
  taskProgressAbort?.abort()
})

async function loadWorkbench() {
  loading.value = true
  try {
    conversations.value = await assistantApi.list(auth.accessToken)
    if (conversations.value.length) await openConversation(conversations.value[0].id)
    else await createConversation()
  } catch (error) {
    ElMessage.error(error instanceof ApiRequestError ? error.message : '无法加载对话工作台')
  } finally {
    loading.value = false
  }
}

async function refreshConversations() {
  conversations.value = await assistantApi.list(auth.accessToken)
}

async function createConversation() {
  if (sending.value) return
  const created = await assistantApi.create(auth.accessToken)
  current.value = created
  await refreshConversations()
  await scrollToBottom()
}

async function openConversation(id: string) {
  if (sending.value || current.value?.conversation.id === id) return
  current.value = await assistantApi.get(id, auth.accessToken)
  clearAttachment()
  await scrollToBottom()
}

async function deleteConversation(id: string) {
  if (sending.value) return
  try {
    await ElMessageBox.confirm('删除后，该对话及其消息将无法恢复。关联的 Agent 任务不会随对话一起删除。', '删除对话', {
      confirmButtonText: '删除', cancelButtonText: '取消', type: 'warning',
    })
    await assistantApi.remove(id, auth.accessToken)
    const wasCurrent = current.value?.conversation.id === id
    await refreshConversations()
    if (wasCurrent) {
      if (conversations.value.length) await openConversation(conversations.value[0].id)
      else await createConversation()
    }
    ElMessage.success('对话已删除')
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(error instanceof ApiRequestError ? error.message : '删除对话失败')
  }
}

function openPicker() {
  if (!sending.value) fileInput.value?.click()
}

async function onFileChange(event: Event) {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  if (files.length) await prepareAttachments(files)
  input.value = ''
}

async function prepareAttachments(files: File[]) {
  const remaining = Math.max(0, 8 - selectedAttachments.value.length)
  if (!remaining) {
    ElMessage.warning('一次联合分析最多选择 8 张图片')
    return
  }
  preparingFile.value = true
  try {
    const additions: SelectedAttachment[] = []
    for (const file of files.slice(0, remaining)) {
      const contentType = await detectImageFileType(file)
      if (!contentType) {
        ElMessage.warning(`已跳过 ${file.name}：仅支持真实的 JPEG、PNG 和 WebP 图片`)
        continue
      }
      const sha256 = await sha256Hex(file)
      if (selectedAttachments.value.some(item => item.sha256 === sha256) || additions.some(item => item.sha256 === sha256)) continue
      additions.push({ file, contentType, preview: URL.createObjectURL(file), sha256 })
    }
    selectedAttachments.value.push(...additions)
    if (files.length > remaining) ElMessage.info('一次最多分析 8 张图片，超出的文件未加入')
    if (!prompt.value.trim() && selectedAttachments.value.length) {
      prompt.value = selectedAttachments.value.length > 1
        ? '请联合分析这些图片是否由 AI 生成，比较它们的相似关系、C2PA 来源凭证，并逐图说明依据与局限。'
        : '请分析这张图片是否由 AI 生成，并说明判断依据与局限。'
    }
  } catch {
    ElMessage.error('浏览器无法读取部分图片，请重新选择')
  } finally {
    preparingFile.value = false
  }
}

function clearAttachment() {
  selectedAttachments.value.forEach(item => URL.revokeObjectURL(item.preview))
  selectedAttachments.value = []
}

function removeAttachment(index: number) {
  const [removed] = selectedAttachments.value.splice(index, 1)
  if (removed) URL.revokeObjectURL(removed.preview)
}

async function uploadAttachments(): Promise<string[]> {
  if (!selectedAttachments.value.length) return []
  const existing = await mediaApi.list(auth.accessToken)
  return Promise.all(selectedAttachments.value.map(async item => {
    const matched = existing.find(asset => asset.sha256 === item.sha256)
    const asset: MediaAsset = matched ?? await mediaApi.upload(
      item.file, item.sha256, auth.accessToken, item.contentType,
    )
    return asset.id
  }))
}

async function sendMessage() {
  if (!canSend.value || !current.value) return
  const text = prompt.value.trim()
  sending.value = true
  try {
    const assetIds = await uploadAttachments()
    const attachmentNames = selectedAttachments.value.map(item => item.file.name)
    prompt.value = ''
    const conversationId = current.value.conversation.id
    current.value.messages.push({
      id: `pending-${Date.now()}`,
      tenantId: '',
      conversationId,
      role: 'USER',
      messageType: assetIds.length ? 'AGENT_REQUEST' : 'CHAT',
      content: text,
      assetId: assetIds[0] ?? null,
      agentTaskId: null,
      grounding: attachmentNames.length ? { attachmentName: attachmentNames[0], attachmentNames, attachmentCount: attachmentNames.length, assetIds } : {},
      createdAt: new Date().toISOString(),
    })
    clearAttachment()
    await scrollToBottom()
    current.value = await assistantApi.send(conversationId, text, assetIds, auth.accessToken)
    await refreshConversations()
    await scrollToBottom()
    const queued = [...current.value.messages].reverse().find(message =>
      message.agentTaskId && String(message.grounding.agentStatus || '') === 'PENDING',
    )
    if (queued?.agentTaskId) void followQueuedTask(conversationId, queued.agentTaskId)
  } catch (error) {
    prompt.value = text
    if (current.value) {
      current.value = await assistantApi.get(current.value.conversation.id, auth.accessToken).catch(() => current.value)
    }
    ElMessage.error(error instanceof ApiRequestError ? error.message : '无法发送消息，请稍后重试')
  } finally {
    sending.value = false
  }
}

async function followQueuedTask(conversationId: string, taskId: string) {
  taskProgressAbort?.abort()
  const controller = new AbortController()
  taskProgressAbort = controller
  try {
    await agentApi.events(taskId, auth.accessToken, async () => {
      if (controller.signal.aborted) return
      const task = await agentApi.get(taskId, auth.accessToken)
      current.value = await assistantApi.get(conversationId, auth.accessToken)
      await scrollToBottom()
      const terminalMessage = current.value.messages.some(message =>
        message.agentTaskId === taskId && ['AGENT_RESULT', 'ERROR'].includes(message.messageType),
      )
      if (terminalMessage || task.task.status === 'CANCELLED') controller.abort()
    }, controller.signal)
  } catch {
    if (!controller.signal.aborted) {
      current.value = await assistantApi.get(conversationId, auth.accessToken).catch(() => current.value)
    }
  }
}

function onComposerKeydown(event: KeyboardEvent) {
  if (event.key === 'Enter' && !event.shiftKey) {
    event.preventDefault()
    void sendMessage()
  }
}

function localSources(message: AssistantMessage): AssistantGroundingSource[] {
  return Array.isArray(message.grounding.localKnowledgeSources)
    ? message.grounding.localKnowledgeSources as AssistantGroundingSource[]
    : []
}

function webSources(message: AssistantMessage): AssistantGroundingSource[] {
  return Array.isArray(message.grounding.liveWebSources)
    ? message.grounding.liveWebSources as AssistantGroundingSource[]
    : []
}

function groundingModes(message: AssistantMessage): string[] {
  return Array.isArray(message.grounding.groundingModes)
    ? message.grounding.groundingModes.map(String)
    : []
}

function webSecurityReport(message: AssistantMessage): WebSecurityInvestigationReport | null {
  const report = message.grounding.webSecurityInvestigation
  return report && typeof report === 'object' ? report as WebSecurityInvestigationReport : null
}

function securityRiskLabel(level: string) {
  return ({ LOW: '低风险', MEDIUM: '中风险', HIGH: '高风险' } as Record<string, string>)[level] ?? level
}

function securityStageLabel(stage: string) {
  return ({
    TARGET_POLICY: '目标策略', DNS_RESOLUTION: 'DNS 解析', TLS_INSPECTION: 'TLS 核验',
    PUBLIC_THREAT_SEARCH: '公开威胁检索', RISK_SYNTHESIS: '风险融合',
  } as Record<string, string>)[stage] ?? stage
}

function tlsValue(report: WebSecurityInvestigationReport | null, key: string) {
  const value = report?.tls?.[key]
  return value == null || value === '' ? '—' : String(value)
}

function attachmentNames(message: AssistantMessage): string[] {
  const names = message.grounding.attachmentNames
  if (Array.isArray(names)) return names.map(String)
  return message.grounding.attachmentName ? [String(message.grounding.attachmentName)] : []
}

function modeLabel(mode: string) {
  return ({
    MODEL_KNOWLEDGE: '模型知识',
    CONVERSATION_CONTEXT: '会话上下文',
    PUBLISHED_KNOWLEDGE_BASE: '已发布知识库',
    LIVE_WEB_SEARCH: '实时网络',
    NETWORK_OBSERVATION: '网络观察',
  } as Record<string, string>)[mode] ?? mode
}

function applySuggestion(text: string) {
  prompt.value = text
}

async function scrollToBottom() {
  await nextTick()
  if (messageList.value) messageList.value.scrollTop = messageList.value.scrollHeight
}
</script>

<template>
  <main class="assistant-workbench" :class="{ loading }">
    <aside class="assistant-history" aria-label="对话列表">
      <div class="assistant-history-head">
        <div><span>ORIGINGUARD</span><strong>分析工作台</strong></div>
        <button type="button" aria-label="新建对话" title="新建对话" @click="createConversation">＋</button>
      </div>
      <div class="assistant-thread-list">
        <div
          v-for="item in conversations"
          :key="item.id"
          class="assistant-thread-item"
          type="button"
          :class="{ active: current?.conversation.id === item.id }"
        >
          <button type="button" class="assistant-thread-open" @click="openConversation(item.id)">
            <strong>{{ item.title }}</strong><small>{{ formatDate(item.updatedAt) }}</small>
          </button>
          <button type="button" class="assistant-thread-delete" aria-label="删除对话" title="删除对话" @click="deleteConversation(item.id)">×</button>
        </div>
      </div>
      <div class="assistant-source-legend">
        <strong>回答来源</strong>
        <p>模型知识 · 会话上下文 · 已发布知识库 · 按需实时检索</p>
        <small>知识用于增强解释，不会替代媒体检测证据。</small>
      </div>
    </aside>

    <section class="assistant-chat">
      <header class="assistant-chat-head">
        <div>
          <span class="canvas-status"></span>
          <div><strong>{{ current?.conversation.title ?? '分析助手' }}</strong><small>理解问题后选择问答、媒体取证或受控 Web 安全调查</small></div>
        </div>
        <RouterLink to="/analyze/history">查看检测记录</RouterLink>
      </header>

      <div ref="messageList" class="assistant-messages">
        <section v-if="!current?.messages.length" class="assistant-empty">
          <span class="assistant-mark">OG</span>
          <h1>你想了解什么，或者要调查什么内容？</h1>
          <p>常识问题直接回答；媒体真实性进入取证 Agent；具体可疑 URL 则执行受控的 DNS、TLS 与公开威胁线索调查。</p>
          <div class="assistant-suggestions">
            <button type="button" @click="applySuggestion('RAG 在 AIGC 图像检测中主要解决什么问题？')">解释一个常识问题</button>
            <button type="button" @click="applySuggestion('最近有哪些针对插画或卡通图像的 AIGC 检测研究？')">检索近期研究</button>
            <button type="button" @click="applySuggestion('请检查 https://example.com/login 这个网站是否存在钓鱼或证书风险')">调查可疑 URL</button>
            <button type="button" @click="openPicker">上传图片并询问</button>
          </div>
        </section>

        <article
          v-for="message in current?.messages ?? []"
          :key="message.id"
          class="assistant-message"
          :class="[message.role.toLowerCase(), message.messageType.toLowerCase()]"
        >
          <div class="assistant-avatar">{{ message.role === 'USER' ? '你' : 'OG' }}</div>
          <div class="assistant-message-body">
            <div v-if="attachmentNames(message).length" class="assistant-message-attachment">
              <span>{{ attachmentNames(message).length > 1 ? `${attachmentNames(message).length} 张联合分析` : '图片附件' }}</span>
              <div><strong v-for="name in attachmentNames(message)" :key="name">{{ name }}</strong></div>
            </div>
            <div
              v-if="message.role === 'ASSISTANT'"
              class="assistant-markdown"
              v-html="renderAssistantMarkdown(message.content)"
            ></div>
            <p v-else>{{ message.content }}</p>

            <RouterLink
              v-if="message.agentTaskId"
              class="assistant-task-link"
              :to="`/analyze/agent-tasks/${message.agentTaskId}`"
            >
              <span><small>AGENT TASK</small><strong>查看运行过程与证据</strong></span>
              <span aria-hidden="true">→</span>
            </RouterLink>

            <section v-if="webSecurityReport(message)" class="web-security-report" :data-risk="webSecurityReport(message)?.riskLevel">
              <header>
                <div>
                  <small>WEB SECURITY INVESTIGATION</small>
                  <strong>{{ webSecurityReport(message)?.host }}</strong>
                  <span>{{ webSecurityReport(message)?.targetUrl }}</span>
                </div>
                <div class="web-security-score">
                  <strong>{{ webSecurityReport(message)?.riskScore }}</strong><span>/ 100</span>
                  <small>{{ securityRiskLabel(webSecurityReport(message)?.riskLevel ?? '') }}</small>
                </div>
              </header>
              <div class="web-security-facts">
                <article><span>协议</span><strong>{{ webSecurityReport(message)?.scheme }} : {{ webSecurityReport(message)?.port }}</strong></article>
                <article><span>公网地址</span><strong>{{ webSecurityReport(message)?.resolvedAddresses.join(' · ') }}</strong></article>
                <article><span>TLS</span><strong>{{ tlsValue(webSecurityReport(message), 'status') }}</strong></article>
                <article><span>证书到期</span><strong>{{ tlsValue(webSecurityReport(message), 'notAfter') }}</strong></article>
                <article><span>公开线索</span><strong>{{ webSecurityReport(message)?.threatIntelSourceCount }} 条</strong></article>
                <article><span>任务耗时</span><strong>{{ webSecurityReport(message)?.durationMilliseconds }} ms</strong></article>
              </div>
              <div class="web-security-signals">
                <strong>风险信号</strong>
                <p v-if="!webSecurityReport(message)?.signals.length">当前受控检查未发现明显风险信号</p>
                <div v-for="signal in webSecurityReport(message)?.signals ?? []" :key="signal.code">
                  <span :data-severity="signal.severity">{{ signal.severity }}</span>
                  <p>{{ signal.message }}</p><strong>+{{ signal.points }}</strong>
                </div>
              </div>
              <details class="web-security-trace">
                <summary>查看受控调查过程与能力边界</summary>
                <ol>
                  <li v-for="event in webSecurityReport(message)?.trace ?? []" :key="event.stage">
                    <span>{{ securityStageLabel(event.stage) }}</span><strong>{{ event.status }}</strong><p>{{ event.summary }}</p>
                  </li>
                </ol>
                <ul><li v-for="item in webSecurityReport(message)?.limitations ?? []" :key="item">{{ item }}</li></ul>
              </details>
            </section>

            <div v-if="groundingModes(message).length" class="assistant-grounding-modes">
              <span v-for="mode in groundingModes(message)" :key="mode">{{ modeLabel(mode) }}</span>
            </div>

            <details v-if="localSources(message).length || webSources(message).length" class="assistant-sources">
              <summary>查看本次回答使用的 {{ localSources(message).length + webSources(message).length }} 个来源</summary>
              <div v-for="source in localSources(message)" :key="`${message.id}-${source.label}`" class="assistant-source-item">
                <span>[{{ source.label }}] 本地知识 · v{{ source.documentVersion }}</span>
                <strong>{{ source.title }}</strong>
                <p>{{ source.quote }}</p>
              </div>
              <a
                v-for="source in webSources(message)"
                :key="`${message.id}-${source.label}`"
                class="assistant-source-item web"
                :href="source.url"
                target="_blank"
                rel="noreferrer"
              >
                <span>[{{ source.label }}] 实时来源 · {{ source.provider }}</span>
                <strong>{{ source.title }}</strong>
                <small v-if="source.venue || source.qualityReason">{{ source.venue }}<template v-if="source.publicationYear"> · {{ source.publicationYear }}</template><template v-if="source.qualityReason"> · {{ source.qualityReason }}</template></small>
                <p>{{ source.snippet }}</p>
              </a>
            </details>
            <div v-if="message.grounding.retrievalInfluenceSummary" class="assistant-retrieval-influence">
              <strong>检索如何影响本次回答</strong><p>{{ message.grounding.retrievalInfluenceSummary }}</p>
            </div>
            <small class="assistant-message-time">{{ formatDate(message.createdAt) }}</small>
          </div>
        </article>

        <article v-if="sending" class="assistant-message assistant thinking">
          <div class="assistant-avatar">OG</div>
          <div class="assistant-message-body">
            <p><span class="thinking-dots"><i></i><i></i><i></i></span>正在理解问题并组织回答…</p>
            <small>涉及具体媒体时，完整 Agent 流程可能需要几分钟。</small>
          </div>
        </article>
      </div>

      <footer class="assistant-composer-wrap">
        <div v-if="selectedAttachments.length" class="assistant-selected-files">
          <div class="assistant-selected-files-head">
            <strong>待联合分析 {{ selectedAttachments.length }} 张</strong><small>最多 8 张 · 将逐图检测并比较相似关系</small>
            <button type="button" @click="clearAttachment">全部移除</button>
          </div>
          <div class="assistant-selected-files-grid">
            <article v-for="(item, index) in selectedAttachments" :key="item.sha256" class="assistant-selected-file">
              <img :src="item.preview" :alt="item.file.name" />
              <span><strong>{{ item.file.name }}</strong><small>{{ item.contentType }} · {{ formatBytes(item.file.size) }}</small></span>
              <button type="button" aria-label="移除附件" @click="removeAttachment(index)">×</button>
            </article>
          </div>
        </div>
        <div class="assistant-composer">
          <input ref="fileInput" class="visually-hidden" type="file" multiple accept=".jpg,.jpeg,.png,.webp,image/jpeg,image/png,image/webp" @change="onFileChange" />
          <button type="button" class="assistant-attach" :disabled="sending" aria-label="上传图片" title="上传图片" @click="openPicker">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M7.5 12.5 14 6a4 4 0 0 1 5.7 5.6l-8.2 8.2a6 6 0 0 1-8.5-8.5l8.1-8.1" /></svg>
          </button>
          <textarea
            v-model="prompt"
            rows="1"
            maxlength="8000"
            placeholder="询问常识、粘贴可疑 URL，或上传图片后描述调查问题…"
            :disabled="sending"
            @keydown="onComposerKeydown"
          ></textarea>
          <button type="button" class="assistant-send" :disabled="!canSend" aria-label="发送" @click="sendMessage">↑</button>
        </div>
        <small class="assistant-composer-note">Enter 发送 · Shift + Enter 换行 · Agent 结果需要人工核验</small>
      </footer>
    </section>
  </main>
</template>
