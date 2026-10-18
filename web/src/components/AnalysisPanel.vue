<script setup lang="ts">
import { computed, ref } from 'vue'
import type { Analysis } from '@/api/types'
import { EVIDENCE_KIND_LABEL, type EvidenceResolver } from '@/evidence'
import {
  ANALYSIS_STATUS_LABEL,
  CONFIDENCE_LABEL,
  CONFIDENCE_TAG,
  formatSeconds,
  formatTime,
  requesterLabel,
} from '@/format'

const props = defineProps<{
  /** Newest first. */
  analyses: Analysis[]
  resolve: EvidenceResolver
  requesting: boolean
}>()

const emit = defineEmits<{
  request: []
  /** The user clicked evidence that lives in an alert on the page. */
  locate: [alertId: string]
}>()

const selectedId = ref<string>()

const latest = computed(() => props.analyses[0])
/** Shown in full: the one picked from the list, else the newest that completed. */
const current = computed(
  () =>
    props.analyses.find((a) => a.analysis_id === selectedId.value) ??
    props.analyses.find((a) => a.status === 'completed'),
)
const latestFailed = computed(
  () => latest.value !== undefined && (latest.value.status === 'failed' || latest.value.status === 'timed_out'),
)

function meta(a: Analysis): string {
  return [
    `${requesterLabel(a.requested_by)}请求于 ${formatTime(a.requested_at)}`,
    a.model && `模型 ${a.model}`,
    a.tool_calls !== undefined && `调用工具 ${a.tool_calls} 次`,
    a.duration_ms !== undefined && `用时 ${formatSeconds(a.duration_ms / 1000)}`,
  ]
    .filter(Boolean)
    .join(' · ')
}
</script>

<template>
  <el-card shadow="never">
    <template #header>
      <div class="card-head">
        <span>Agent 分析</span>
        <el-button
          type="primary"
          size="small"
          :loading="requesting || latest?.status === 'pending'"
          @click="emit('request')"
        >
          {{ latest ? '重新分析' : '请求分析' }}
        </el-button>
      </div>
    </template>

    <el-alert
      v-if="latest?.status === 'pending'"
      type="info"
      show-icon
      :closable="false"
      title="Agent 正在分析"
      :description="`${requesterLabel(latest.requested_by)}请求于 ${formatTime(latest.requested_at)}，一般一分钟内完成`"
      class="banner"
    />
    <el-alert
      v-else-if="latestFailed && latest"
      type="warning"
      show-icon
      :closable="false"
      :title="`最近一次分析${ANALYSIS_STATUS_LABEL[latest.status]}`"
      :description="latest.error ?? 'Agent 没有在期限内给出结果'"
      class="banner"
    />

    <div v-if="current" class="analysis">
      <div class="muted meta">{{ meta(current) }}</div>
      <el-alert
        v-if="current.uncertain"
        type="warning"
        show-icon
        :closable="false"
        title="Agent 认为证据不足以下结论，下面的原因只是推测"
        class="banner"
      />
      <p class="summary">{{ current.summary }}</p>

      <h4>可能的原因 <span class="muted">按可能性排序</span></h4>
      <div
        v-for="(h, i) in current.hypotheses ?? []"
        :key="`${h.component}-${i}`"
        class="hypothesis"
        :class="{ first: i === 0 }"
      >
        <div class="hypothesis-head">
          <span class="rank">{{ i + 1 }}</span>
          <strong>{{ h.component }}</strong>
          <el-tag :type="CONFIDENCE_TAG[h.confidence]" effect="plain" size="small">
            置信度{{ CONFIDENCE_LABEL[h.confidence] }}
          </el-tag>
        </div>
        <p class="cause">{{ h.cause }}</p>
        <ul class="evidence">
          <li v-for="e in h.evidence.map(resolve)" :key="`${e.kind}-${e.id}`">
            <el-tag size="small" type="info" effect="plain" class="kind">{{ EVIDENCE_KIND_LABEL[e.kind] }}</el-tag>
            <div>
              <a v-if="e.alertId" class="evidence-title link" @click="emit('locate', e.alertId)">{{ e.title }}</a>
              <span v-else class="evidence-title">{{ e.title }}</span>
              <span v-if="e.source" class="muted"> · {{ e.source }}</span>
              <div v-if="e.note" class="note">{{ e.note }}</div>
            </div>
          </li>
        </ul>
      </div>

      <template v-if="current.next_steps?.length">
        <h4>建议排查 <span class="muted">Agent 只给建议，不执行操作</span></h4>
        <ol class="steps">
          <li v-for="(step, i) in current.next_steps" :key="i">{{ step }}</li>
        </ol>
      </template>

      <el-alert
        v-if="current.rejected_evidence?.length"
        type="info"
        :closable="false"
        show-icon
        :title="`core-service 剔除了 ${current.rejected_evidence.length} 条引用：本 Incident 没有这些告警、日志或组件`"
        class="banner"
      >
        <div v-for="e in current.rejected_evidence" :key="`${e.kind}-${e.id}`" class="mono">
          {{ EVIDENCE_KIND_LABEL[e.kind] ?? e.kind }} {{ e.id }}<span v-if="e.note">：{{ e.note }}</span>
        </div>
      </el-alert>
    </div>
    <el-empty
      v-else-if="!latest"
      :image-size="64"
      description="还没有分析。Incident 一分钟没有新告警后，系统会自动请求 Agent 分析，也可以手动请求。"
    />
    <el-empty v-else-if="latest.status === 'pending'" :image-size="64" description="等待第一份分析结果" />

    <el-collapse v-if="analyses.length > 1" class="history">
      <el-collapse-item :title="`全部分析（${analyses.length}）`" name="history">
        <el-table :data="analyses" size="small">
          <el-table-column label="请求时间" width="130">
            <template #default="{ row }">{{ formatTime(row.requested_at) }}</template>
          </el-table-column>
          <el-table-column label="来源" width="80">
            <template #default="{ row }">{{ requesterLabel(row.requested_by) }}</template>
          </el-table-column>
          <el-table-column label="状态" width="80">
            <template #default="{ row }">{{ ANALYSIS_STATUS_LABEL[row.status as Analysis['status']] }}</template>
          </el-table-column>
          <el-table-column label="最可能的原因" min-width="120">
            <template #default="{ row }">{{ row.hypotheses?.[0]?.component ?? '—' }}</template>
          </el-table-column>
          <el-table-column width="70" align="right">
            <template #default="{ row }">
              <el-button
                v-if="row.status === 'completed'"
                link
                type="primary"
                :disabled="row.analysis_id === current?.analysis_id"
                @click="selectedId = row.analysis_id"
              >
                {{ row.analysis_id === current?.analysis_id ? '当前' : '查看' }}
              </el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-collapse-item>
    </el-collapse>
  </el-card>
</template>

<style scoped>
.banner {
  margin-bottom: 12px;
}

.meta {
  font-size: 12px;
  margin-bottom: 8px;
}

.summary {
  margin: 0 0 16px;
  line-height: 1.7;
}

h4 {
  margin: 16px 0 8px;
  font-size: 14px;
}

h4 .muted {
  margin-left: 6px;
  font-size: 12px;
  font-weight: 400;
}

.hypothesis {
  padding: 12px 14px;
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  margin-bottom: 10px;
}

.hypothesis.first {
  border-color: #8e44ad;
  border-left-width: 4px;
}

.hypothesis-head {
  display: flex;
  align-items: center;
  gap: 8px;
}

.rank {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: var(--el-fill-color-dark);
  font-size: 12px;
}

.first .rank {
  background: #8e44ad;
  color: #fff;
}

.cause {
  margin: 8px 0;
  line-height: 1.7;
}

.evidence {
  list-style: none;
  margin: 0;
  padding: 0;
}

.evidence li {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 6px 0;
  border-top: 1px dashed var(--el-border-color-lighter);
  font-size: 13px;
}

.kind {
  flex: none;
  width: 56px;
  justify-content: center;
}

.evidence-title {
  word-break: break-all;
}

.link {
  color: var(--el-color-primary);
  cursor: pointer;
}

.note {
  margin-top: 2px;
  color: var(--el-text-color-regular);
}

.steps {
  margin: 0;
  padding-left: 20px;
  line-height: 1.8;
}

.history {
  margin-top: 16px;
}
</style>
