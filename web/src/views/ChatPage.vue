<script setup lang="ts">
import { ChatDotRound, Delete, Plus, Promotion, VideoPause } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { nextTick, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { type ChatMessage, chatApi, type ChatSession } from '@/api/chat'
import ChatAnswer from '@/components/ChatAnswer.vue'
import { applyEvent, EXAMPLES, newAnswer } from '@/chat'
import { errorMessage } from '@/errors'
import { formatTime } from '@/format'

const props = defineProps<{ id?: string }>()

const router = useRouter()
const sessions = ref<ChatSession[]>([])
const messages = ref<ChatMessage[]>([])
const input = ref('')
const loading = ref(false)
const error = ref('')
const asking = ref(false)
const list = ref<HTMLElement>()
let controller: AbortController | undefined
// The session this page created for the question it is answering: not to be reloaded when the URL catches up.
let created: string | undefined

async function loadSessions() {
  try {
    sessions.value = (await chatApi.sessions()).items
    error.value = ''
  } catch (e) {
    error.value = errorMessage(e)
  }
}

async function open(id: string | undefined) {
  stop()
  messages.value = []
  if (!id) {
    return
  }
  loading.value = true
  try {
    messages.value = (await chatApi.session(id)).messages
    error.value = ''
    await scrollDown()
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

async function ask(text = input.value) {
  const content = text.trim()
  if (!content || asking.value) {
    return
  }
  asking.value = true
  input.value = ''
  let sessionId = props.id
  try {
    if (!sessionId) {
      sessionId = (await chatApi.createSession()).session_id
      created = sessionId
      await router.replace(`/chat/${sessionId}`)
    }
    messages.value.push({
      message_id: `user-${Date.now()}`,
      role: 'user',
      content,
      status: 'completed',
      created_at: new Date().toISOString(),
    })
    messages.value.push(newAnswer())
    // The reactive copy, so that every event shows at once.
    const answer = messages.value[messages.value.length - 1]!
    await scrollDown()
    controller = new AbortController()
    await chatApi.ask(
      sessionId,
      content,
      (event, data) => {
        applyEvent(answer, event, data as Record<string, unknown>)
        void scrollDown(true)
      },
      controller.signal,
    )
    if (answer.status === 'streaming') {
      answer.status = controller.signal.aborted ? 'cancelled' : 'failed'
    }
  } catch (e) {
    ElMessage.error(errorMessage(e))
    const last = messages.value[messages.value.length - 1]
    if (last?.role === 'assistant' && last.status === 'streaming') {
      last.status = 'failed'
      last.trace = { ...(last.trace ?? { items: [], citations: [] }), error: errorMessage(e) }
    }
  } finally {
    asking.value = false
    controller = undefined
    void loadSessions()
  }
}

function stop() {
  controller?.abort()
}

async function remove(session: ChatSession) {
  try {
    await chatApi.deleteSession(session.session_id)
    if (session.session_id === props.id) {
      await router.push('/chat')
    }
    await loadSessions()
  } catch (e) {
    ElMessage.error(errorMessage(e))
  }
}

function onKey(event: Event | KeyboardEvent) {
  if (event instanceof KeyboardEvent && event.key === 'Enter' && !event.shiftKey && !event.isComposing) {
    event.preventDefault()
    void ask()
  }
}

/** Follows the answer down, unless the user scrolled up to read. */
async function scrollDown(onlyIfNear = false) {
  await nextTick()
  const el = list.value
  if (el && (!onlyIfNear || el.scrollHeight - el.scrollTop - el.clientHeight < 120)) {
    el.scrollTop = el.scrollHeight
  }
}

watch(
  () => props.id,
  (id) => {
    if (id && id === created) {
      created = undefined
      return
    }
    void open(id)
  },
)

onMounted(() => {
  void loadSessions()
  void open(props.id)
})
onBeforeUnmount(stop)
</script>

<template>
  <div class="chat">
    <aside class="sessions">
      <el-button type="primary" :icon="Plus" class="new" @click="router.push('/chat')">新对话</el-button>
      <div class="session-list">
        <div
          v-for="s in sessions"
          :key="s.session_id"
          class="session"
          :class="{ active: s.session_id === id }"
          @click="router.push(`/chat/${s.session_id}`)"
        >
          <div class="session-title">{{ s.title }}</div>
          <div class="session-time muted">{{ formatTime(s.updated_at) }}</div>
          <el-button
            class="session-delete"
            :icon="Delete"
            link
            size="small"
            title="删除这个对话"
            @click.stop="remove(s)"
          />
        </div>
        <div v-if="!sessions.length" class="muted empty-list">还没有对话</div>
      </div>
    </aside>

    <section class="conversation">
      <el-alert v-if="error" type="error" :title="error" show-icon :closable="false" />
      <div ref="list" v-loading="loading" class="messages">
        <div v-if="!messages.length && !loading" class="welcome">
          <el-icon :size="32"><ChatDotRound /></el-icon>
          <h3>Ops-Agent 值班助手</h3>
          <p class="muted">只读系统的告警和 Incident，回答都附带可以点开核对的证据。</p>
          <div class="examples">
            <el-button v-for="q in EXAMPLES" :key="q" round @click="ask(q)">{{ q }}</el-button>
          </div>
        </div>
        <div v-for="m in messages" :key="m.message_id" class="message" :class="m.role">
          <div v-if="m.role === 'user'" class="bubble">{{ m.content }}</div>
          <ChatAnswer v-else :message="m" />
        </div>
      </div>
      <div class="composer">
        <el-input
          v-model="input"
          type="textarea"
          :autosize="{ minRows: 1, maxRows: 6 }"
          resize="none"
          maxlength="2000"
          placeholder="问问系统的情况，例如：刚才下单为什么失败？（Enter 发送，Shift+Enter 换行）"
          @keydown="onKey"
        />
        <el-button v-if="asking" :icon="VideoPause" @click="stop">停止</el-button>
        <el-button v-else type="primary" :icon="Promotion" :disabled="!input.trim()" @click="ask()">发送</el-button>
      </div>
    </section>
  </div>
</template>

<style scoped>
.chat {
  display: flex;
  gap: 16px;
  height: calc(100vh - 100px);
  min-height: 480px;
}

.sessions {
  width: 240px;
  flex: none;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.new {
  width: 100%;
}

.session-list {
  overflow-y: auto;
  flex: 1;
}

.session {
  position: relative;
  padding: 8px 28px 8px 10px;
  border-radius: 6px;
  cursor: pointer;
}

.session:hover,
.session.active {
  background: var(--el-fill-color-light);
}

.session.active .session-title {
  color: var(--el-color-primary);
}

.session-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  font-size: 14px;
}

.session-time {
  font-size: 12px;
}

.session-delete {
  position: absolute;
  top: 10px;
  right: 6px;
  display: none;
}

.session:hover .session-delete {
  display: inline-flex;
}

.empty-list {
  padding: 8px 10px;
  font-size: 13px;
}

.conversation {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: var(--el-bg-color);
  border: 1px solid var(--el-border-color-light);
  border-radius: 8px;
}

.messages {
  flex: 1;
  overflow-y: auto;
  padding: 20px 24px;
}

.welcome {
  text-align: center;
  margin-top: 12vh;
  color: var(--el-text-color-regular);
}

.welcome h3 {
  margin: 8px 0 4px;
}

.examples {
  display: flex;
  flex-wrap: wrap;
  justify-content: center;
  gap: 8px;
  margin-top: 16px;
}

.examples .el-button + .el-button {
  margin-left: 0;
}

.message {
  margin-bottom: 20px;
}

.message.user {
  display: flex;
  justify-content: flex-end;
}

.bubble {
  max-width: 75%;
  padding: 8px 12px;
  border-radius: 10px;
  background: var(--el-color-primary-light-9);
  white-space: pre-wrap;
  word-break: break-word;
}

.message.assistant {
  max-width: 900px;
}

.composer {
  display: flex;
  align-items: flex-end;
  gap: 8px;
  padding: 12px 16px;
  border-top: 1px solid var(--el-border-color-light);
}

@media (max-width: 768px) {
  .sessions {
    display: none;
  }

  .messages {
    padding: 16px;
  }
}
</style>
