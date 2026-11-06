<script setup lang="ts">
// One run from the request to the verdict (docs/agent-design.md §15): what it spent and where the time went, the cards
// it proposed, what core-service did with them, whether that worked, and what it wrote on the case.
import { computed, onMounted, ref, watch } from 'vue'
import { chatApi, type RunChain, type RunStatus, type UsageSpan } from '@/api/chat'
import { CASE_STATUS, ENTRY_KIND, OUTCOME, VERDICT } from '@/casework'
import { ACTION_STATUS, seconds, spanBar, tokens } from '@/chat'
import { errorMessage } from '@/errors'
import { formatTime } from '@/format'

const props = defineProps<{ id: string }>()

const RUN_STATUS: Record<RunStatus, { label: string; type: 'info' | 'success' | 'warning' | 'danger' }> = {
  running: { label: '进行中', type: 'info' },
  answered: { label: '已回答', type: 'success' },
  paused_question: { label: '等用户回答问卷', type: 'warning' },
  paused_action: { label: '等用户确认操作', type: 'warning' },
  degraded: { label: '已降级', type: 'warning' },
  failed: { label: '失败', type: 'danger' },
  cancelled: { label: '已取消', type: 'info' },
}

const chain = ref<RunChain | null>(null)
const error = ref('')
const loading = ref(false)

const usage = computed(() => chain.value?.run.usage ?? null)
const wholeMs = computed(() => usage.value?.wall_ms ?? 0)
const entries = computed(() => chain.value?.cases.flatMap((c) => c.entries) ?? [])

async function load() {
  loading.value = true
  try {
    chain.value = await chatApi.run(props.id)
    error.value = ''
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

function spanLabel(s: UsageSpan): string {
  if (s.kind === 'step') {
    return s.name
  }
  return s.purpose === 'route' ? '模型：判断路由' : s.tier === 'quick' ? '模型：快速回答' : '模型：推理'
}

function spanDetail(s: UsageSpan): string {
  const parts = [seconds(s.duration_ms)]
  if (s.first_token_ms !== undefined) {
    parts.push(`首字 ${seconds(s.first_token_ms)}`)
  }
  if (s.prompt_tokens !== undefined || s.completion_tokens !== undefined) {
    parts.push(`${s.prompt_tokens ?? '?'} + ${s.completion_tokens ?? '?'} tokens`)
  }
  if (s.status !== 'ok') {
    parts.push(s.status === 'cancelled' ? '被中断' : `失败${s.error ? `（${s.error}）` : ''}`)
  }
  return parts.join(' · ')
}

watch(() => props.id, load)
onMounted(load)
</script>

<template>
  <div v-loading="loading" class="run">
    <el-alert v-if="error" type="error" :title="error" show-icon :closable="false" />
    <template v-if="chain">
      <div class="head">
        <h2>运行详情</h2>
        <el-tag size="small" :type="RUN_STATUS[chain.run.status].type">{{ RUN_STATUS[chain.run.status].label }}</el-tag>
        <span class="muted small">
          {{ chain.run.trigger === 'questionnaire_reply' ? '回答问卷后继续' : '用户提问' }} ·
          {{ formatTime(chain.run.started_at) }}
        </span>
        <router-link :to="`/chat/${chain.run.session_id}`" class="small">回到对话</router-link>
      </div>
      <div class="mono muted small ids">run {{ chain.run.run_id }}</div>
      <el-alert
        v-if="chain.core_error"
        type="warning"
        :title="`core-service 没有回应，处置和验证看不到：${chain.core_error}`"
        show-icon
        :closable="false"
      />

      <section v-if="chain.question || chain.answer">
        <p v-if="chain.question" class="question">{{ chain.question }}</p>
        <p v-if="chain.answer?.content" class="answer">{{ chain.answer.content }}</p>
      </section>

      <section>
        <h3>用量</h3>
        <p v-if="!usage" class="muted small">这一轮没有记录用量（还没结束，或者是记录用量之前的回答）。</p>
        <template v-else>
          <div class="stats">
            <div>
              <span class="muted small">模型调用</span><strong>{{ usage.model_calls }} 次</strong>
              <span class="muted small">{{ seconds(usage.model_ms) }}</span>
            </div>
            <div>
              <span class="muted small">Token</span>
              <strong>{{ tokens(usage.total_tokens, usage.calls_without_usage) || '没有使用大模型' }}</strong>
              <span v-if="usage.total_tokens !== null" class="muted small">
                输入 {{ usage.prompt_tokens ?? 0 }}<template v-if="usage.cached_tokens !== null">（缓存 {{ usage.cached_tokens }}）</template>
                · 输出 {{ usage.completion_tokens ?? 0 }}<template v-if="usage.reasoning_tokens !== null">（推理 {{ usage.reasoning_tokens }}）</template>
              </span>
            </div>
            <div>
              <span class="muted small">模型补查工具</span><strong>{{ usage.tool_steps }} 次</strong>
              <span class="muted small">{{ seconds(usage.tool_ms) }}</span>
            </div>
            <div><span class="muted small">全程</span><strong>{{ seconds(wholeMs) }}</strong></div>
          </div>
          <div class="timeline">
            <div v-for="(s, i) in usage.spans" :key="i" class="span">
              <span class="label" :title="s.name">{{ spanLabel(s) }}</span>
              <span class="track">
                <i class="bar" :class="[s.kind, s.status]" :style="spanBar(s, wholeMs)" />
              </span>
              <span class="muted small detail">{{ spanDetail(s) }}</span>
            </div>
          </div>
        </template>
      </section>

      <section>
        <h3>操作与结果</h3>
        <p v-if="!chain.actions.length" class="muted small">这一轮没有提出操作。</p>
        <ol class="chain">
          <li v-for="a in chain.actions" :key="a.action_id">
            <div>
              <strong>{{ a.title }}</strong>
              <el-tag size="small" :type="ACTION_STATUS[a.status].type">{{ ACTION_STATUS[a.status].label }}</el-tag>
            </div>
            <div class="muted small">{{ a.reason }}</div>
            <div v-if="a.receipt" class="small">回执：{{ a.receipt.summary }}</div>
            <div v-if="a.remediation" class="small">
              core-service 记为 {{ a.remediation.status }}（{{ formatTime(a.remediation.created_at) }}）：{{ a.remediation.receipt.summary }}
            </div>
            <div v-for="v in a.verifications" :key="v.verification_id" class="small">
              验证：
              <template v-if="v.status === 'done' && v.outcome">
                <el-tag size="small" :type="OUTCOME[v.outcome].type">{{ OUTCOME[v.outcome].label }}</el-tag>
                <ul class="checks">
                  <li v-for="c in v.result?.checks ?? []" :key="c.id">{{ VERDICT[c.verdict] }} {{ c.title }}：{{ c.reason }}</li>
                </ul>
              </template>
              <span v-else-if="v.status === 'stale'" class="muted">已作废（之后又执行了新的处置）</span>
              <span v-else class="muted">等待中，{{ formatTime(v.not_before) }} 之后判断</span>
            </div>
          </li>
        </ol>
      </section>

      <section v-if="chain.cases.length">
        <h3>处置单</h3>
        <div v-for="c in chain.cases" :key="c.case_id" class="small">
          {{ c.title }}
          <el-tag size="small" :type="CASE_STATUS[c.status].type">{{ CASE_STATUS[c.status].label }}</el-tag>
        </div>
        <div v-for="e in entries" :key="e.entry_id" class="entry small">
          <el-tag size="small" effect="plain">{{ ENTRY_KIND[e.kind] }}</el-tag>
          <span>{{ e.text }}</span>
        </div>
        <p v-if="!entries.length" class="muted small">这一轮没有往处置单写内容。</p>
      </section>

      <section v-if="chain.run.snapshot">
        <h3>上下文快照</h3>
        <div class="mono muted small ids">{{ chain.run.snapshot_digest }}</div>
        <details>
          <summary class="small">这一轮给模型的上下文</summary>
          <pre class="snapshot">{{ JSON.stringify(chain.run.snapshot, null, 2) }}</pre>
        </details>
      </section>
    </template>
  </div>
</template>

<style scoped>
.run {
  max-width: 900px;
  display: flex;
  flex-direction: column;
  gap: 12px;
  font-size: 13px;
}

.head {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
}

.head h2 {
  margin: 0;
  font-size: 18px;
}

h3 {
  margin: 0 0 8px;
  font-size: 14px;
}

section {
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  padding: 10px 12px;
  background: var(--el-bg-color);
}

.small {
  font-size: 12px;
}

.ids {
  overflow-wrap: anywhere;
}

.question {
  margin: 0 0 6px;
  font-weight: 600;
}

.answer {
  margin: 0;
  white-space: pre-wrap;
  max-height: 160px;
  overflow: auto;
  color: var(--el-text-color-regular);
}

.stats {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(170px, 1fr));
  gap: 10px;
  margin-bottom: 10px;
}

.stats > div {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.stats strong {
  font-size: 15px;
  font-variant-numeric: tabular-nums;
}

.span {
  display: grid;
  grid-template-columns: 150px minmax(60px, 1fr) minmax(0, 230px);
  gap: 8px;
  align-items: center;
  padding: 2px 0;
}

.label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.detail {
  font-variant-numeric: tabular-nums;
}

.track {
  position: relative;
  display: block;
  height: 10px;
  background: var(--el-fill-color-light);
  border-radius: 2px;
}

.bar {
  position: absolute;
  top: 0;
  bottom: 0;
  border-radius: 2px;
  background: var(--el-color-info);
}

.bar.model {
  background: var(--el-color-primary);
}

.bar.error {
  background: var(--el-color-danger);
}

.bar.cancelled {
  background: var(--el-color-warning);
}

.chain {
  margin: 0;
  padding-left: 18px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.chain .el-tag {
  margin-left: 6px;
}

.checks {
  margin: 2px 0;
  padding-left: 16px;
}

.entry {
  display: flex;
  gap: 6px;
  align-items: baseline;
  margin-top: 4px;
}

.snapshot {
  font-size: 12px;
  max-height: 320px;
  overflow: auto;
  background: var(--el-fill-color-lighter);
  padding: 8px;
  border-radius: 4px;
}

@media (max-width: 600px) {
  .span {
    grid-template-columns: 1fr;
    gap: 2px;
    padding: 4px 0;
  }
}
</style>
