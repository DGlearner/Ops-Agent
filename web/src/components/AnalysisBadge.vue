<script setup lang="ts">
import { Loading } from '@element-plus/icons-vue'
import { computed } from 'vue'
import type { Analysis } from '@/api/types'
import { ANALYSIS_STATUS_LABEL, CONFIDENCE_LABEL, CONFIDENCE_TAG } from '@/format'

/** The agent's verdict on an incident in a table cell: its latest analysis, newest first in {@code analyses}. */
const props = defineProps<{ analyses?: Analysis[] }>()

const latest = computed(() => props.analyses?.[0])
const top = computed(() => (latest.value?.status === 'completed' ? latest.value.hypotheses?.[0] : undefined))
</script>

<template>
  <span v-if="!latest" class="muted">未分析</span>
  <span v-else-if="latest.status === 'pending'" class="pending">
    <el-icon class="is-loading"><Loading /></el-icon>
    分析中
  </span>
  <el-tag v-else-if="latest.status !== 'completed'" type="info" size="small">
    分析{{ ANALYSIS_STATUS_LABEL[latest.status] }}
  </el-tag>
  <span v-else-if="top" class="verdict">
    <strong>{{ top.component }}</strong>
    <el-tag :type="CONFIDENCE_TAG[top.confidence]" effect="plain" size="small">
      置信度{{ CONFIDENCE_LABEL[top.confidence] }}
    </el-tag>
    <el-tag v-if="latest.uncertain" type="warning" size="small">不确定</el-tag>
  </span>
  <span v-else class="muted">没有结论</span>
</template>

<style scoped>
.pending {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  color: var(--el-color-primary);
}

.verdict {
  display: inline-flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}
</style>
