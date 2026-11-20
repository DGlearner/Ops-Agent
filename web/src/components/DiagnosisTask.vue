<script setup lang="ts">
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { api } from '@/api/client'
import type { DiagnosisTask } from '@/api/chat'
import { readDiagnosis, type TaskResult } from '@/diagnostics'
import { evidenceResolver } from '@/evidence'
import { CONFIDENCE_LABEL, formatTime } from '@/format'
import { usePolling } from '@/composables/usePolling'

const props = defineProps<{ task: DiagnosisTask; busy?: boolean }>()
const emit = defineEmits<{ explain: [text: string] }>()
const result = ref<TaskResult>()
const error = ref('')
let generation = 0
let disposed = false
const checkedAt = ref<string>()
const status = computed(() => result.value?.analysis.status ?? props.task.status)
const label = computed(() => ({ pending: '等待或执行中', completed: '已完成', failed: '分析失败', timed_out: '分析超时' })[status.value])
const resolve = computed(() => result.value && evidenceResolver(result.value.incident, result.value.alerts))
const evidenceLink = (alertId?: string) => `/incidents/${props.task.incident_id}${alertId ? `?alert=${alertId}` : ''}`

async function poll() {
  if (disposed) return
  const current = ++generation
  error.value = ''
  try {
    const found = await readDiagnosis(props.task, api)
    if (current !== generation) return
    result.value = found
    checkedAt.value = new Date().toISOString()
  } catch (e) {
    if (current === generation) error.value = e instanceof Error ? e.message : '查询任务失败'
  }
}
const active = computed(() => status.value === 'pending' && !error.value)
const { refresh } = usePolling(poll, 3000, active)
watch(() => [props.task.analysis_id, props.task.status, props.task.stale], () => {
  generation++
  result.value = undefined
  void refresh()
})
onBeforeUnmount(() => { disposed = true; generation++ })
</script>

<template>
  <el-card shadow="never" class="diagnosis-task">
    <div class="task-head">
      <strong>{{ task.incident_ref }} · 后台诊断 · {{ label }}</strong>
      <el-button size="small" @click="refresh">刷新状态</el-button>
    </div>
    <div class="muted mono">任务 {{ task.analysis_id }}</div>
    <div class="muted">{{ formatTime(result?.analysis.requested_at ?? task.requested_at) }} 请求</div>
    <div v-if="checkedAt" class="muted">状态核对于 {{ formatTime(checkedAt) }}；完成后可手动刷新查看后续变化。</div>
    <el-alert v-if="error" :title="error" type="warning" :closable="false" />
    <p v-else-if="status === 'pending'">后台任务正在等待或执行，本轮对话无需等待诊断完成。完成后这里会显示结果和依据。</p>
    <template v-if="result && !error">
      <el-alert v-if="result.stale" title="此任务已不是当前证据的最新分析，以下仅为历史结果。请结合最新证据核查。" type="warning" :closable="false" />
      <p v-if="status === 'failed' || status === 'timed_out'">{{ result.analysis.error ?? '后台未能给出结论。失败或超时不表示系统正常；本卡片不会自动重跑。' }}</p>
      <template v-if="status === 'completed'">
        <p>{{ result.analysis.uncertain ? '后台认为证据不足，以下仅为假设。' : '后台给出的判断仍需与当前原始证据对照。' }}</p>
        <p>{{ result.analysis.summary }}</p>
        <div v-for="(h, i) in result.analysis.hypotheses ?? []" :key="i">
          <strong>{{ h.component }} · 置信度{{ CONFIDENCE_LABEL[h.confidence] }}</strong>
          <p>{{ h.cause }}</p>
          <ul>
            <li v-for="e in h.evidence.map(resolve!)" :key="`${e.kind}-${e.id}`">
              <router-link :to="evidenceLink(e.alertId)">{{ e.title }}</router-link>
              <span v-if="e.note">：{{ e.note }}</span>
            </li>
          </ul>
          <p v-if="!h.evidence.length" class="muted">该假设没有可核验的引用。</p>
        </div>
        <p v-if="result.analysis.rejected_evidence?.length" class="muted">后台有 {{ result.analysis.rejected_evidence.length }} 条引用未通过校验，已剔除。</p>
        <el-button :disabled="busy" @click="emit('explain', `请查询 Incident ${task.incident_id} 的当前证据和最新后台任务，解释分析结论与依据；若任务 ${task.analysis_id} 已过时请明确说明。`)">
          结合当前证据解释
        </el-button>
      </template>
    </template>
    <router-link :to="evidenceLink()">查看事件与原始证据</router-link>
  </el-card>
</template>

<style scoped>
.diagnosis-task { margin: 12px 0; }
.task-head { display: flex; justify-content: space-between; gap: 12px; align-items: center; }
p, ul { margin: 8px 0; }
</style>
