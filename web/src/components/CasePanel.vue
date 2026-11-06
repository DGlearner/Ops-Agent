<script setup lang="ts">
// A case (docs/agent-design.md §2, §6): who said or saw what, what was done and whether it recovered. Only a person
// signs a case off here; a verification that says recovered only moves it to monitoring.
import { ElMessage } from 'element-plus'
import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { api, ApiError } from '@/api/client'
import type { Case, Remediation, Verification } from '@/api/types'
import { CASE_STATUS, ENTRY_KIND, ENTRY_SOURCE, OUTCOME, reviewDraft, VERDICT } from '@/casework'
import { errorMessage } from '@/errors'
import { formatTime } from '@/format'

const props = defineProps<{ caseId: string; refresh?: number }>()

const current = ref<Case | null>(null)
const remediations = ref<Remediation[]>([])
const verifications = ref<Verification[]>([])
const error = ref('')
const signing = ref(false)
const showDraft = ref(false)
let timer: ReturnType<typeof setInterval> | undefined

const shown = computed(() => (current.value?.entries ?? []).filter((e) => e.status !== 'superseded'))
const superseded = computed(() => (current.value?.entries ?? []).filter((e) => e.status === 'superseded').length)
const draft = computed(() =>
  current.value ? reviewDraft(current.value, remediations.value, verifications.value) : '',
)

async function load() {
  try {
    const [c, r, v] = await Promise.all([
      api.case(props.caseId),
      api.remediations(props.caseId),
      api.verifications(props.caseId),
    ])
    current.value = c
    remediations.value = r.items
    verifications.value = v.items
    error.value = ''
  } catch (e) {
    error.value = errorMessage(e)
  }
}

async function signOff() {
  if (!current.value) {
    return
  }
  signing.value = true
  try {
    current.value = await api.transitionCase(current.value.case_id, 'resolved', current.value.version, '人工验收')
    ElMessage.success('处置单已验收')
  } catch (e) {
    ElMessage.warning(errorMessage(e))
    if (e instanceof ApiError && e.status === 409) {
      await load()
    }
  } finally {
    signing.value = false
  }
}

// Verifying: check back until the verification has its verdict.
watch(
  () => current.value?.status,
  (status) => {
    if (timer) {
      clearInterval(timer)
      timer = undefined
    }
    if (status === 'verifying' || status === 'mitigating') {
      timer = setInterval(() => void load(), 5000)
    }
  },
)
watch(() => [props.caseId, props.refresh], () => void load(), { immediate: true })
onBeforeUnmount(() => timer && clearInterval(timer))
</script>

<template>
  <div class="case-panel">
    <el-alert v-if="error" type="error" :title="error" show-icon :closable="false" />
    <template v-if="current">
      <div class="head">
        <strong>处置单</strong>
        <el-tag size="small" :type="CASE_STATUS[current.status].type">{{ CASE_STATUS[current.status].label }}</el-tag>
        <span class="muted small">第 {{ current.version }} 版</span>
      </div>
      <div class="muted small">{{ current.title }}</div>

      <div v-for="e in shown" :key="e.entry_id" class="entry" :class="e.kind">
        <el-tag size="small" effect="plain">{{ ENTRY_KIND[e.kind] }}</el-tag>
        <span class="text">{{ e.text }}</span>
        <span class="muted small">{{ ENTRY_SOURCE[e.source_type] }}{{ e.kind === 'hypothesis' ? ` · ${e.status}` : '' }}</span>
      </div>
      <div v-if="superseded" class="muted small">另有 {{ superseded }} 条已被取代（保留在记录里）</div>

      <div v-for="r in remediations" :key="r.remediation_id" class="remediation">
        <div>
          <strong>{{ r.op }}</strong>
          <span class="mono small">{{ JSON.stringify(r.params) }}</span>
          <el-tag size="small" :type="r.status === 'executed' ? 'success' : 'warning'">{{ r.status }}</el-tag>
          <router-link v-if="r.run_id" :to="`/runs/${r.run_id}`" class="small run-link">提出它的那一轮</router-link>
        </div>
        <div class="small">{{ r.receipt.summary }}</div>
        <div v-for="v in verifications.filter((x) => x.remediation_id === r.remediation_id)" :key="v.verification_id" class="verification">
          <span class="muted small">验证（{{ v.criteria.goal === 'contain' ? '止血' : '恢复' }}）：</span>
          <template v-if="v.status === 'done' && v.outcome">
            <el-tag size="small" :type="OUTCOME[v.outcome].type">{{ OUTCOME[v.outcome].label }}</el-tag>
            <ul class="checks small">
              <li v-for="c in v.result?.checks ?? []" :key="c.id">{{ VERDICT[c.verdict] }} {{ c.title }}：{{ c.reason }}</li>
            </ul>
          </template>
          <span v-else-if="v.status === 'stale'" class="muted small">已作废（之后又执行了新的处置）</span>
          <span v-else class="muted small">等待中，{{ formatTime(v.not_before) }} 之后判断</span>
        </div>
      </div>

      <div class="buttons">
        <el-button
          v-if="current.status !== 'resolved'"
          size="small"
          type="success"
          :loading="signing"
          :disabled="current.status === 'verifying' || current.status === 'mitigating'"
          @click="signOff"
        >
          验收：标为已解决
        </el-button>
        <el-button size="small" link type="primary" @click="showDraft = !showDraft">
          {{ showDraft ? '收起复盘草稿' : '复盘草稿' }}
        </el-button>
      </div>
      <pre v-if="showDraft" class="draft">{{ draft }}</pre>
    </template>
  </div>
</template>

<style scoped>
.case-panel {
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  padding: 10px 12px;
  background: var(--el-bg-color);
  display: flex;
  flex-direction: column;
  gap: 6px;
  font-size: 13px;
}

.head {
  display: flex;
  gap: 8px;
  align-items: center;
}

.small {
  font-size: 12px;
}

.entry {
  display: flex;
  gap: 6px;
  align-items: baseline;
  flex-wrap: wrap;
}

.entry .text {
  flex: 1;
  min-width: 0;
}

.remediation {
  border-top: 1px dashed var(--el-border-color);
  padding-top: 6px;
}

.run-link {
  margin-left: 6px;
}

.checks {
  margin: 2px 0;
  padding-left: 16px;
}

.buttons {
  display: flex;
  gap: 8px;
  align-items: center;
}

.draft {
  white-space: pre-wrap;
  font-size: 12px;
  background: var(--el-fill-color-lighter);
  padding: 8px;
  border-radius: 4px;
  max-height: 320px;
  overflow: auto;
}
</style>
