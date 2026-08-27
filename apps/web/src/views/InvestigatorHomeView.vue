<script setup lang="ts">
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, nextTick, onBeforeUnmount, onMounted, ref } from 'vue'
import { assistantApi } from '../api/assistant'
import { ApiRequestError } from '../api/http'
import { mediaApi } from '../api/media'
import { useAuthStore } from '../stores/auth'
import type {
  AssistantConversation,
  AssistantConversationDetails,
  AssistantGroundingSource,
  AssistantMessage,
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
const selectedFile = ref<File | null>(null)
const selectedContentType = ref<SupportedImageType | null>(null)
const selectedPreview = ref('')
const selectedSha256 = ref('')
const preparingFile = ref(false)

const canSend = computed(() => Boolean(
  current.value && prompt.value.trim() && !sending.value && !preparingFile.value,
))

onMounted(loadWorkbench)
onBeforeUnmount(clearAttachment)

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
  const file = input.files?.[0]
  if (file) await prepareAttachment(file)
  input.value = ''
}

async function prepareAttachment(file: File) {
  const contentType = await detectImageFileType(file)
  if (!contentType) {
    ElMessage.warning('当前仅支持 JPEG、PNG 和 WebP 图片，请确认文件真实格式')
    return
  }
  clearAttachment()
  selectedFile.value = file
  selectedContentType.value = contentType
  selectedPreview.value = URL.createObjectURL(file)
  preparingFile.value = true
  try {
    selectedSha256.value = await sha256Hex(file)
    if (!prompt.value.trim()) prompt.value = '请分析这张图片是否由 AI 生成，并说明判断依据与局限。'
  } catch {
    clearAttachment()
    ElMessage.error('浏览器无法读取该图片，请重新选择')
  } finally {
    preparingFile.value = false
  }
}

function clearAttachment() {
  if (selectedPreview.value) URL.revokeObjectURL(selectedPreview.value)
  selectedFile.value = null
  selectedContentType.value = null
  selectedPreview.value = ''
  selectedSha256.value = ''
}

async function uploadAttachment(): Promise<string | null> {
  if (!selectedFile.value || !selectedContentType.value || !selectedSha256.value) return null
  const existing = (await mediaApi.list(auth.accessToken)).find(asset => asset.sha256 === selectedSha256.value)
  const asset: MediaAsset = existing ?? await mediaApi.upload(
    selectedFile.value,
    selectedSha256.value,
    auth.accessToken,
    selectedContentType.value,
  )
  return asset.id
}

async function sendMessage() {
  if (!canSend.value || !current.value) return
  const text = prompt.value.trim()
  sending.value = true
  try {
    const assetId = await uploadAttachment()
    prompt.value = ''
    const conversationId = current.value.conversation.id
    current.value.messages.push({
      id: `pending-${Date.now()}`,
      tenantId: '',
      conversationId,
      role: 'USER',
      messageType: assetId ? 'AGENT_REQUEST' : 'CHAT',
      content: text,
      assetId,
      agentTaskId: null,
      grounding: selectedFile.value ? { attachmentName: selectedFile.value.name } : {},
      createdAt: new Date().toISOString(),
    })
    clearAttachment()
    await scrollToBottom()
    current.value = await assistantApi.send(conversationId, text, assetId, auth.accessToken)
    await refreshConversations()
    await scrollToBottom()
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

function modeLabel(mode: string) {
  return ({
    MODEL_KNOWLEDGE: '模型知识',
    CONVERSATION_CONTEXT: '会话上下文',
    PUBLISHED_KNOWLEDGE_BASE: '已发布知识库',
    LIVE_WEB_SEARCH: '实时网络',
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
          <div><strong>{{ current?.conversation.title ?? '分析助手' }}</strong><small>先理解问题，必要时才运行取证 Agent</small></div>
        </div>
        <RouterLink to="/analyze/history">查看检测记录</RouterLink>
      </header>

      <div ref="messageList" class="assistant-messages">
        <section v-if="!current?.messages.length" class="assistant-empty">
          <span class="assistant-mark">OG</span>
          <h1>你想了解什么，或者要分析哪张图片？</h1>
          <p>常识问题由大模型结合知识直接回答；涉及具体媒体真实性时，我会建立 Agent 任务并保留完整证据链。</p>
          <div class="assistant-suggestions">
            <button type="button" @click="applySuggestion('RAG 在 AIGC 图像检测中主要解决什么问题？')">解释一个常识问题</button>
            <button type="button" @click="applySuggestion('最近有哪些针对插画或卡通图像的 AIGC 检测研究？')">检索近期研究</button>
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
            <div v-if="message.grounding.attachmentName" class="assistant-message-attachment">
              <span>图片附件</span><strong>{{ message.grounding.attachmentName }}</strong>
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
        <div v-if="selectedFile" class="assistant-selected-file">
          <img :src="selectedPreview" :alt="selectedFile.name" />
          <span><strong>{{ selectedFile.name }}</strong><small>{{ selectedContentType }} · {{ formatBytes(selectedFile.size) }}</small></span>
          <button type="button" aria-label="移除附件" @click="clearAttachment">×</button>
        </div>
        <div class="assistant-composer">
          <input ref="fileInput" class="visually-hidden" type="file" accept=".jpg,.jpeg,.png,.webp,image/jpeg,image/png,image/webp" @change="onFileChange" />
          <button type="button" class="assistant-attach" :disabled="sending" aria-label="上传图片" title="上传图片" @click="openPicker">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M7.5 12.5 14 6a4 4 0 0 1 5.7 5.6l-8.2 8.2a6 6 0 0 1-8.5-8.5l8.1-8.1" /></svg>
          </button>
          <textarea
            v-model="prompt"
            rows="1"
            maxlength="8000"
            placeholder="询问常识，或上传图片后描述你希望 Agent 调查的问题…"
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
