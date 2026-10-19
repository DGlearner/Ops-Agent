<script setup lang="ts">
import { CircleCheck, CircleClose, Loading, WarningFilled } from '@element-plus/icons-vue'
import { computed, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import type { ChatMessage, StepItem } from '@/api/chat'
import { citationLink, citationTitle, METHOD_LABEL, metricsLine, renderAnswer, seconds } from '@/chat'

const props = defineProps<{ message: ChatMessage }>()

const router = useRouter()
const trace = computed(() => props.message.trace ?? { route: null, items: [], citations: [] })
const streaming = computed(() => props.message.status === 'streaming')
const steps = computed(() => trace.value.items.filter((i): i is StepItem => i.type === 'step'))

// Open while the assistant is still working things out, closed once the answer starts; a kept answer starts closed.
const open = ref(streaming.value)
watch(
  () => props.message.content.length > 0,
  (answering) => {
    if (answering && streaming.value) {
      open.value = false
    }
  },
)

const html = computed(() => renderAnswer(props.message.content, trace.value.citations))
// The refs under the answer: the unverified ones first (they matter most), a few in all unless asked for more.
const CHIPS = 6
const allCitations = ref(false)
const citations = computed(() => {
  const sorted = [...trace.value.citations].sort((a, b) => Number(a.verified) - Number(b.verified))
  return allCitations.value ? sorted : sorted.slice(0, CHIPS)
})
const summary = computed(() => {
  if (streaming.value) {
    const running = [...steps.value].reverse().find((s) => s.status === 'running')
    return running ? `${running.title}…` : props.message.content ? '正在回答…' : '思考中…'
  }
  const n = steps.value.length
  return `思考过程 · ${n ? `${n} 步` : '没有调用工具'}${trace.value.metrics ? ` · 用时 ${seconds(trace.value.metrics.duration_ms)}` : ''}`
})
const statusNote = computed(
  () => ({ cancelled: '已停止', failed: '回答失败' } as Record<string, string>)[props.message.status],
)

/** Links in the answer go through the router instead of reloading the page. */
function follow(event: MouseEvent) {
  const target = (event.target as HTMLElement).closest('a[data-route]')
  if (target && !event.metaKey && !event.ctrlKey) {
    event.preventDefault()
    void router.push(target.getAttribute('data-route')!)
  }
}
</script>

<template>
  <div class="answer">
    <div v-if="trace.route" class="route">
      <el-tag size="small" :type="trace.route.route.startsWith('ops') ? 'primary' : 'info'" effect="plain">
        {{ trace.route.label }}
      </el-tag>
      <span class="muted">{{ METHOD_LABEL[trace.route.method] }} · {{ trace.route.reason }}</span>
    </div>

    <div v-if="trace.items.length || streaming" class="thinking">
      <button type="button" class="thinking-head" @click="open = !open">
        <el-icon v-if="streaming" class="is-loading"><Loading /></el-icon>
        <span>{{ summary }}</span>
        <span class="toggle">{{ open ? '收起' : '展开' }}</span>
      </button>
      <ol v-show="open" class="trace">
        <li v-for="(item, i) in trace.items" :key="item.type === 'step' ? item.id : `t${i}`" :class="item.type">
          <template v-if="item.type === 'step'">
            <el-icon class="icon" :class="item.status">
              <Loading v-if="item.status === 'running'" class="is-loading" />
              <CircleCheck v-else-if="item.status === 'done'" />
              <WarningFilled v-else />
            </el-icon>
            <div>
              <div>
                {{ item.title }}
                <span v-if="item.duration_ms !== undefined" class="muted">{{ seconds(item.duration_ms) }}</span>
              </div>
              <div v-if="item.detail" class="muted mono detail">{{ item.detail }}</div>
              <div v-if="item.result" class="result">{{ item.result }}</div>
            </div>
          </template>
          <template v-else>
            <div class="thought-text">
              <span v-if="item.interim" class="muted">（模型的中间说明）</span>{{ item.text }}
            </div>
          </template>
        </li>
      </ol>
    </div>

    <!-- v-html: renderAnswer escapes the model's raw HTML; the only markup it adds is the refs' links -->
    <div v-if="message.content" class="markdown" :class="{ streaming }" @click="follow" v-html="html" />

    <el-alert v-if="trace.error" type="error" :title="trace.error" show-icon :closable="false" class="error" />

    <div v-if="trace.citations.length" class="citations">
      <span class="muted">引用</span>
      <template v-for="c in citations" :key="c.ref">
        <router-link v-if="citationLink(c)" :to="citationLink(c)!" class="chip" :title="citationTitle(c)">
          <el-icon><CircleCheck /></el-icon>{{ c.ref }} {{ c.label }}
        </router-link>
        <span v-else class="chip" :class="{ unverified: !c.verified }" :title="citationTitle(c)">
          <el-icon><CircleCheck v-if="c.verified" /><CircleClose v-else /></el-icon>
          {{ c.ref }} {{ c.verified ? c.label : '不在查到的数据里' }}
        </span>
      </template>
      <el-button v-if="trace.citations.length > CHIPS" link type="primary" size="small" @click="allCitations = !allCitations">
        {{ allCitations ? '收起' : `还有 ${trace.citations.length - CHIPS} 处` }}
      </el-button>
    </div>

    <div v-if="!streaming" class="meta muted">
      <span v-if="statusNote" class="status">{{ statusNote }}</span>
      {{ metricsLine(trace.metrics, trace.route) }}
    </div>
  </div>
</template>

<style scoped>
.route {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 12px;
  margin-bottom: 8px;
}

.thinking {
  border-left: 2px solid var(--el-border-color);
  padding-left: 12px;
  margin-bottom: 10px;
}

.thinking-head {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 0;
  border: none;
  background: none;
  color: var(--el-text-color-secondary);
  font-size: 13px;
  cursor: pointer;
}

.toggle {
  color: var(--el-color-primary);
}

.trace {
  list-style: none;
  margin: 8px 0 0;
  padding: 0;
  font-size: 13px;
}

.trace li {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
}

.icon {
  margin-top: 2px;
  flex: none;
}

.icon.done {
  color: var(--el-color-success);
}

.icon.error {
  color: var(--el-color-warning);
}

.detail {
  word-break: break-all;
}

.result {
  color: var(--el-text-color-regular);
}

.thought-text {
  white-space: pre-wrap;
  color: var(--el-text-color-secondary);
  line-height: 1.6;
}

.markdown {
  line-height: 1.7;
  word-break: break-word;
}

.markdown :deep(p) {
  margin: 0 0 8px;
}

.markdown :deep(ul),
.markdown :deep(ol) {
  margin: 0 0 8px;
  padding-left: 20px;
}

.markdown :deep(table) {
  border-collapse: collapse;
  margin-bottom: 8px;
}

.markdown :deep(th),
.markdown :deep(td) {
  border: 1px solid var(--el-border-color);
  padding: 4px 8px;
}

.markdown :deep(code) {
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  background: var(--el-fill-color-light);
  padding: 1px 4px;
  border-radius: 3px;
}

.markdown :deep(blockquote) {
  margin: 8px 0;
  padding-left: 10px;
  border-left: 3px solid var(--el-border-color);
  color: var(--el-text-color-secondary);
}

.markdown.streaming :deep(> :last-child)::after {
  content: '▍';
  animation: blink 1s steps(1) infinite;
  color: var(--el-color-primary);
}

@keyframes blink {
  50% {
    opacity: 0;
  }
}

.markdown :deep(.cite) {
  display: inline-block;
  margin: 0 1px;
  padding: 0 4px;
  font-size: 11px;
  line-height: 16px;
  border-radius: 3px;
  vertical-align: 1px;
  background: var(--el-color-primary-light-9);
  color: var(--el-color-primary);
  text-decoration: none;
}

.markdown :deep(.cite.unverified) {
  background: var(--el-color-danger-light-9);
  color: var(--el-color-danger);
  text-decoration: line-through;
}

.markdown :deep(.cite.pending) {
  background: var(--el-fill-color);
  color: var(--el-text-color-secondary);
}

.citations {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 6px;
  margin-top: 8px;
  font-size: 12px;
}

.chip {
  display: inline-flex;
  align-items: center;
  gap: 3px;
  max-width: 320px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  padding: 1px 6px;
  border-radius: 3px;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-regular);
  text-decoration: none;
}

a.chip:hover {
  color: var(--el-color-primary);
}

.chip.unverified {
  color: var(--el-color-danger);
  background: var(--el-color-danger-light-9);
}

.meta {
  margin-top: 8px;
  font-size: 12px;
}

.status {
  color: var(--el-color-warning);
  margin-right: 4px;
}

.error {
  margin-top: 8px;
}
</style>
