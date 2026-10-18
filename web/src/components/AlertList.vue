<script setup lang="ts">
import type { Alert, Sample } from '@/api/types'
import { formatTime, shortId } from '@/format'
import SeverityTag from './SeverityTag.vue'

defineProps<{
  alerts: Alert[]
  /** Alert and event ids the agent cites. */
  cited: Set<string>
  /** An alert to draw the eye to, after the user clicked evidence in it. */
  flash?: string
}>()

/** Ids of the alerts that are open. */
const expanded = defineModel<string[]>('expanded', { required: true })

function groupText(alert: Alert): string {
  const entries = Object.entries(alert.group_values)
  return entries.length ? entries.map(([k, v]) => `${k}=${v}`).join(', ') : alert.group_key
}
</script>

<template>
  <el-empty v-if="!alerts.length" :image-size="64" description="没有告警" />
  <el-collapse v-else v-model="expanded">
    <div v-for="a in alerts" :id="`alert-${a.alert_id}`" :key="a.alert_id" :class="{ flash: flash === a.alert_id }">
      <el-collapse-item :name="a.alert_id">
        <template #title>
          <div class="alert-title">
            <SeverityTag :severity="a.severity" />
            <strong>{{ a.service ?? a.group_key }}</strong>
            <span class="summary">{{ a.summary }}</span>
            <el-tag v-if="cited.has(a.alert_id)" size="small" effect="plain">Agent 引用</el-tag>
            <span class="muted time">{{ formatTime(a.created_at) }}</span>
          </div>
        </template>
        <el-descriptions :column="3" size="small" border>
          <el-descriptions-item label="规则">{{ a.rule_id }} v{{ a.rule_version }}</el-descriptions-item>
          <el-descriptions-item label="次数 / 阈值">{{ a.count }} / {{ a.threshold }}</el-descriptions-item>
          <el-descriptions-item label="主机">{{ a.host ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="统计窗口" :span="2">
            {{ formatTime(a.window_start) }} – {{ formatTime(a.window_end) }}
          </el-descriptions-item>
          <el-descriptions-item label="分组">{{ groupText(a) }}</el-descriptions-item>
        </el-descriptions>
        <el-table
          :data="a.samples"
          size="small"
          class="samples"
          empty-text="这条告警没有保存日志样本"
          :row-class-name="({ row }: { row: Sample }) => (cited.has(row.event_id) ? 'cited' : '')"
        >
          <el-table-column label="时间" width="140">
            <template #default="{ row }">{{ formatTime(row.timestamp) }}</template>
          </el-table-column>
          <el-table-column label="日志样本（告警的证据）" min-width="320">
            <template #default="{ row }">
              <span class="message">{{ row.message }}</span>
              <el-tag v-if="cited.has(row.event_id)" size="small" effect="plain" class="cited-tag">Agent 引用</el-tag>
            </template>
          </el-table-column>
          <el-table-column label="trace" width="100">
            <template #default="{ row }">
              <el-tooltip v-if="row.trace_id" :content="row.trace_id" placement="top">
                <span class="mono">{{ shortId(row.trace_id) }}</span>
              </el-tooltip>
            </template>
          </el-table-column>
        </el-table>
      </el-collapse-item>
    </div>
  </el-collapse>
</template>

<style scoped>
.alert-title {
  display: flex;
  align-items: center;
  gap: 8px;
  min-width: 0;
  flex: 1;
  padding-right: 8px;
}

.summary {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
  min-width: 0;
}

.time {
  font-size: 12px;
  white-space: nowrap;
}

.samples {
  margin-top: 12px;
}

.message {
  word-break: break-all;
}

.cited-tag {
  margin-left: 6px;
}

.samples :deep(.cited) {
  --el-table-tr-bg-color: var(--el-color-warning-light-9);
}

.flash {
  animation: flash 1.6s ease-out;
}

@keyframes flash {
  from {
    background: var(--el-color-primary-light-7);
  }
  to {
    background: transparent;
  }
}
</style>
