<script setup lang="ts">
import { ArrowLeft } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { computed, nextTick, ref } from 'vue'
import { api, ApiError } from '@/api/client'
import type { Alert, Analysis, Incident, IncidentStatus, TimelineEntry, Topology } from '@/api/types'
import AlertList from '@/components/AlertList.vue'
import AnalysisPanel from '@/components/AnalysisPanel.vue'
import SeverityTag from '@/components/SeverityTag.vue'
import StatusTag from '@/components/StatusTag.vue'
import TopologyGraph from '@/components/TopologyGraph.vue'
import { usePolling } from '@/composables/usePolling'
import { errorMessage } from '@/errors'
import { citedIds, evidenceResolver } from '@/evidence'
import { between, formatTime, NEXT_STATUSES, TRANSITION_LABEL } from '@/format'
import { buildTimeline } from '@/timeline'

const props = defineProps<{ id: string }>()

const incident = ref<Incident>()
const alerts = ref<Alert[]>([])
const timeline = ref<TimelineEntry[]>([])
const analyses = ref<Analysis[]>([])
const topology = ref<Topology>()
const error = ref('')
const notFound = ref(false)
const requesting = ref(false)
const transitioning = ref(false)
const expanded = ref<string[]>([])
const flash = ref<string>()

async function load() {
  if (notFound.value) {
    return
  }
  try {
    const [i, a, t, an] = await Promise.all([
      api.incident(props.id),
      api.incidentAlerts(props.id),
      api.timeline(props.id),
      api.analyses(props.id),
    ])
    if (!incident.value) {
      // First load: open the first few alerts; later the user decides.
      expanded.value = a.slice(0, 3).map((x) => x.alert_id)
    }
    incident.value = i
    alerts.value = a
    timeline.value = t
    analyses.value = an
    error.value = ''
    if (!topology.value) {
      topology.value = await api.topology()
    }
  } catch (e) {
    notFound.value = e instanceof ApiError && e.status === 404
    error.value = errorMessage(e)
  }
}

const { refresh } = usePolling(load, 5000)

/** The analysis whose findings the page marks: the newest that completed. */
const shown = computed(() => analyses.value.find((a) => a.status === 'completed'))
const cited = computed(() => citedIds(shown.value?.hypotheses))
const suspected = computed(() => {
  const top = shown.value?.hypotheses?.[0]?.component
  return top ? [top] : []
})
const resolve = computed(() => (incident.value ? evidenceResolver(incident.value, alerts.value, topology.value) : undefined))
const history = computed(() => buildTimeline(timeline.value, analyses.value))
const nextStatuses = computed(() => (incident.value ? NEXT_STATUSES[incident.value.status] : []))

async function requestAnalysis() {
  requesting.value = true
  try {
    await api.requestAnalysis(props.id)
    ElMessage.success('已请求 Agent 分析')
    await refresh()
  } catch (e) {
    ElMessage.error(errorMessage(e))
  } finally {
    requesting.value = false
  }
}

async function transition(to: IncidentStatus) {
  let note: string | undefined
  try {
    const answer = await ElMessageBox.prompt('备注（可不填），会记入时间线', TRANSITION_LABEL[to], {
      confirmButtonText: '确定',
      cancelButtonText: '取消',
      inputPlaceholder: '例如：已重启 shop-mysql',
    })
    note = answer.value?.trim() || undefined
  } catch {
    return // cancelled
  }
  transitioning.value = true
  try {
    await api.transition(props.id, to, note)
    await refresh()
  } catch (e) {
    ElMessage.error(errorMessage(e))
  } finally {
    transitioning.value = false
  }
}

async function locate(alertId: string) {
  if (!expanded.value.includes(alertId)) {
    expanded.value = [...expanded.value, alertId]
  }
  flash.value = undefined
  await nextTick()
  flash.value = alertId
  document.getElementById(`alert-${alertId}`)?.scrollIntoView({ behavior: 'smooth', block: 'center' })
}
</script>

<template>
  <router-link to="/incidents" class="back">
    <el-icon><ArrowLeft /></el-icon>
    Incident 列表
  </router-link>

  <el-result v-if="notFound" icon="warning" title="没有这个 Incident" :sub-title="error" />
  <el-alert v-else-if="error && !incident" type="error" :title="error" show-icon :closable="false" />

  <template v-if="incident && resolve">
    <el-alert v-if="error" type="error" :title="`刷新失败：${error}`" show-icon :closable="false" class="stale" />

    <el-card shadow="never">
      <div class="title-row">
        <SeverityTag :severity="incident.severity" />
        <StatusTag :status="incident.status" />
        <h2>{{ incident.title }}</h2>
      </div>
      <div class="actions">
        <el-button
          v-for="to in nextStatuses"
          :key="to"
          size="small"
          :type="to === 'resolved' ? 'success' : 'default'"
          :loading="transitioning"
          @click="transition(to)"
        >
          {{ TRANSITION_LABEL[to] }}
        </el-button>
      </div>
      <el-descriptions :column="3" size="small" class="facts">
        <el-descriptions-item label="开始">{{ formatTime(incident.opened_at) }}</el-descriptions-item>
        <el-descriptions-item label="最近告警">{{ formatTime(incident.last_alert_at) }}</el-descriptions-item>
        <el-descriptions-item label="告警持续">
          {{ between(incident.opened_at, incident.last_alert_at) }}，共 {{ incident.alert_count }} 条告警
        </el-descriptions-item>
        <el-descriptions-item label="出问题的服务">
          <span class="tags inline">
            <el-tag v-for="s in incident.subjects" :key="s" size="small" type="danger" effect="plain">{{ s }}</el-tag>
          </span>
        </el-descriptions-item>
        <el-descriptions-item label="告警来自">{{ incident.services.join('、') }}</el-descriptions-item>
        <el-descriptions-item label="共同依赖">
          <template v-if="incident.shared_dependencies.length">
            {{ incident.shared_dependencies.join('、') }}
            <span class="muted">（按服务目录算出的线索，不是结论）</span>
          </template>
          <span v-else class="muted">无</span>
        </el-descriptions-item>
        <el-descriptions-item label="ID"><span class="mono">{{ incident.incident_id }}</span></el-descriptions-item>
      </el-descriptions>
    </el-card>

    <el-row :gutter="16" class="body">
      <el-col :xs="24" :lg="14">
        <AnalysisPanel :analyses="analyses" :resolve="resolve" :requesting="requesting" @request="requestAnalysis" @locate="locate" />
        <el-card shadow="never">
          <template #header>
            <div class="card-head">
              <span>告警与日志证据</span>
              <span class="muted">{{ alerts.length }} 条告警</span>
            </div>
          </template>
          <AlertList v-model:expanded="expanded" :alerts="alerts" :cited="cited" :flash="flash" />
        </el-card>
      </el-col>
      <el-col :xs="24" :lg="10">
        <el-card v-if="topology" shadow="never">
          <template #header><div class="card-head">影响范围</div></template>
          <TopologyGraph :topology="topology" :affected="incident.subjects" :suspected="suspected" :height="280" />
        </el-card>
        <el-card shadow="never">
          <template #header><div class="card-head">时间线</div></template>
          <el-timeline class="timeline">
            <el-timeline-item
              v-for="item in history"
              :key="item.key"
              :timestamp="formatTime(item.at)"
              :type="item.type"
              placement="top"
            >
              <div>{{ item.title }}</div>
              <div v-if="item.detail" class="muted detail">{{ item.detail }}</div>
            </el-timeline-item>
          </el-timeline>
        </el-card>
      </el-col>
    </el-row>
  </template>
</template>

<style scoped>
.back {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-bottom: 12px;
  color: var(--el-text-color-regular);
  text-decoration: none;
}

.stale {
  margin-bottom: 12px;
}

.title-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.title-row h2 {
  margin: 0;
  font-size: 18px;
}

.actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 12px 0;
}

.actions .el-button + .el-button {
  margin-left: 0;
}

.tags.inline {
  display: inline-flex;
}

.body {
  margin-top: 16px;
}

.timeline {
  max-height: 520px;
  overflow-y: auto;
  padding: 4px 4px 0 2px;
}

.detail {
  margin-top: 2px;
  font-size: 12px;
  word-break: break-all;
}
</style>
