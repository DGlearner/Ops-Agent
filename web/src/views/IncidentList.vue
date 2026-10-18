<script setup lang="ts">
import { Refresh } from '@element-plus/icons-vue'
import { ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { api, type IncidentFilter } from '@/api/client'
import type { Analysis, Incident, IncidentStatus, Severity } from '@/api/types'
import AnalysisBadge from '@/components/AnalysisBadge.vue'
import SeverityTag from '@/components/SeverityTag.vue'
import StatusTag from '@/components/StatusTag.vue'
import { usePolling } from '@/composables/usePolling'
import { errorMessage } from '@/errors'
import { ago, formatTime, SEVERITY_LABEL, STATUS_LABEL } from '@/format'

const PAGE_SIZE = 20

const router = useRouter()

const status = ref<IncidentStatus | ''>('')
const severity = ref<Severity | ''>('')
const service = ref('')
const autoRefresh = ref(true)

const services = ref<string[]>([])
const incidents = ref<Incident[]>([])
const analyses = ref<Record<string, Analysis[]>>({})
const nextCursor = ref<string>()
const loading = ref(true)
const loadingMore = ref(false)
const error = ref('')
const now = ref(Date.now())

function filter(limit: number): IncidentFilter {
  return {
    status: status.value || undefined,
    severity: severity.value || undefined,
    service: service.value || undefined,
    limit,
  }
}

async function loadAnalyses(items: Incident[]) {
  const lists = await Promise.all(items.map((i) => api.analyses(i.incident_id)))
  const next = { ...analyses.value }
  items.forEach((incident, k) => (next[incident.incident_id] = lists[k] ?? []))
  analyses.value = next
}

/** Reloads everything shown so far, so a refresh keeps the pages loaded with "加载更多". */
async function load() {
  try {
    const page = await api.incidents(filter(Math.min(200, Math.max(PAGE_SIZE, incidents.value.length))))
    await loadAnalyses(page.items)
    incidents.value = page.items
    nextCursor.value = page.next_cursor
    error.value = ''
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
    now.value = Date.now()
  }
}

async function loadMore() {
  if (!nextCursor.value) {
    return
  }
  loadingMore.value = true
  try {
    const page = await api.incidents(filter(PAGE_SIZE), nextCursor.value)
    await loadAnalyses(page.items)
    incidents.value = [...incidents.value, ...page.items]
    nextCursor.value = page.next_cursor
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loadingMore.value = false
  }
}

const { refresh } = usePolling(load, 5000, autoRefresh)

watch([status, severity, service], () => {
  incidents.value = []
  loading.value = true
  void refresh()
})

api
  .topology()
  .then((t) => (services.value = t.nodes.map((n) => n.name).sort()))
  .catch(() => {
    // The filter just offers no services; the table shows the error from its own load.
  })

function open(row: Incident) {
  void router.push(`/incidents/${row.incident_id}`)
}
</script>

<template>
  <div class="page-head">
    <h2>Incident</h2>
    <span class="muted">core-service 按服务依赖把相关告警合并成 Incident；一分钟没有新告警后，Agent 自动分析原因</span>
  </div>

  <el-card shadow="never">
    <div class="toolbar">
      <el-select v-model="status" placeholder="状态" clearable class="filter">
        <el-option v-for="(label, value) in STATUS_LABEL" :key="value" :label="label" :value="value" />
      </el-select>
      <el-select v-model="severity" placeholder="严重度" clearable class="filter">
        <el-option v-for="(label, value) in SEVERITY_LABEL" :key="value" :label="label" :value="value" />
      </el-select>
      <el-select v-model="service" placeholder="服务" clearable filterable class="filter wide">
        <el-option v-for="name in services" :key="name" :label="name" :value="name" />
      </el-select>
      <div class="spacer" />
      <el-switch v-model="autoRefresh" active-text="每 5 秒刷新" />
      <el-button :icon="Refresh" @click="refresh">刷新</el-button>
    </div>

    <el-alert v-if="error" type="error" :title="error" show-icon :closable="false" class="error" />

    <el-table
      v-loading="loading"
      :data="incidents"
      row-key="incident_id"
      class="incidents"
      empty-text="还没有 Incident"
      @row-click="open"
    >
      <el-table-column label="严重度" width="76">
        <template #default="{ row }"><SeverityTag :severity="row.severity" /></template>
      </el-table-column>
      <el-table-column label="标题" prop="title" min-width="300" show-overflow-tooltip />
      <el-table-column label="状态" width="84">
        <template #default="{ row }"><StatusTag :status="row.status" /></template>
      </el-table-column>
      <el-table-column label="出问题的服务" min-width="170">
        <template #default="{ row }">
          <div class="tags">
            <el-tag v-for="s in row.subjects" :key="s" size="small" type="danger" effect="plain">{{ s }}</el-tag>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="告警" prop="alert_count" width="64" align="right" />
      <el-table-column label="开始" width="150">
        <template #default="{ row }">{{ formatTime(row.opened_at) }}</template>
      </el-table-column>
      <el-table-column label="最近告警" width="100">
        <template #default="{ row }">{{ ago(row.last_alert_at, now) }}</template>
      </el-table-column>
      <el-table-column label="Agent 结论" min-width="210">
        <template #default="{ row }"><AnalysisBadge :analyses="analyses[row.incident_id]" /></template>
      </el-table-column>
    </el-table>

    <div v-if="nextCursor" class="more">
      <el-button :loading="loadingMore" @click="loadMore">加载更多</el-button>
    </div>
  </el-card>
</template>

<style scoped>
.toolbar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 12px;
}

.filter {
  width: 120px;
}

.filter.wide {
  width: 180px;
}

.spacer {
  flex: 1;
}

.error {
  margin-bottom: 12px;
}

.incidents :deep(.el-table__row) {
  cursor: pointer;
}

.more {
  display: flex;
  justify-content: center;
  margin-top: 12px;
}
</style>
