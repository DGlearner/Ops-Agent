<script setup lang="ts">
import { computed, ref } from 'vue'
import { api } from '@/api/client'
import type { Analysis, Incident, Topology } from '@/api/types'
import AnalysisBadge from '@/components/AnalysisBadge.vue'
import SeverityTag from '@/components/SeverityTag.vue'
import StatusTag from '@/components/StatusTag.vue'
import TopologyGraph from '@/components/TopologyGraph.vue'
import { usePolling } from '@/composables/usePolling'
import { errorMessage } from '@/errors'
import { ACTIVE_STATUSES, ago } from '@/format'

/** Incidents are not resolved on their own, so only those with recent alerts mark the graph. 0: all active ones. */
const WINDOWS = [
  { label: '最近 15 分钟', seconds: 900 },
  { label: '最近 1 小时', seconds: 3600 },
  { label: '最近 6 小时', seconds: 21600 },
  { label: '全部', seconds: 0 },
]

const topology = ref<Topology>()
const windowSeconds = ref(3600)
const active = ref<Incident[]>([])
const analyses = ref<Record<string, Analysis[]>>({})
const selected = ref<string>()
const error = ref('')
const now = ref(Date.now())

async function load() {
  try {
    const [t, page] = await Promise.all([api.topology(), api.incidents({ limit: 200 })])
    const from = Date.now() - windowSeconds.value * 1000
    const open = page.items.filter(
      (i) => ACTIVE_STATUSES.includes(i.status) && (!windowSeconds.value || Date.parse(i.last_alert_at) >= from),
    )
    const lists = await Promise.all(open.map((i) => api.analyses(i.incident_id)))
    topology.value = t
    active.value = open
    analyses.value = Object.fromEntries(open.map((i, k) => [i.incident_id, lists[k] ?? []]))
    error.value = ''
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    now.value = Date.now()
  }
}

const { refresh } = usePolling(load, 10000)

/** Active incidents per service they are about. */
const counts = computed(() => {
  const result: Record<string, number> = {}
  for (const incident of active.value) {
    for (const subject of incident.subjects) {
      result[subject] = (result[subject] ?? 0) + 1
    }
  }
  return result
})

function topCause(incident: Incident): string | undefined {
  return analyses.value[incident.incident_id]?.find((a) => a.status === 'completed')?.hypotheses?.[0]?.component
}

const affected = computed(() => Object.keys(counts.value))
const suspected = computed(() => [...new Set(active.value.map(topCause).filter((c): c is string => !!c))])

const node = computed(() => topology.value?.nodes.find((n) => n.name === selected.value))
const dependsOn = computed(() => topology.value?.edges.filter((e) => e.from === selected.value).map((e) => e.to) ?? [])
const dependedOnBy = computed(
  () => topology.value?.edges.filter((e) => e.to === selected.value).map((e) => e.from) ?? [],
)
/** Active incidents the selected node has a part in: as a subject, as the source of alerts, or as the cause. */
const related = computed(() =>
  active.value.filter(
    (i) =>
      selected.value !== undefined &&
      (i.subjects.includes(selected.value) ||
        i.services.includes(selected.value) ||
        topCause(i) === selected.value),
  ),
)
</script>

<template>
  <div class="page-head">
    <h2>服务拓扑</h2>
    <span class="muted">来自 core-service 的服务目录；标出进行中、且在所选时间内有告警的 Incident 涉及的服务，括号里是 Incident 数</span>
    <el-radio-group v-model="windowSeconds" size="small" class="window" @change="refresh">
      <el-radio-button v-for="w in WINDOWS" :key="w.seconds" :value="w.seconds">{{ w.label }}</el-radio-button>
    </el-radio-group>
  </div>

  <el-alert v-if="error" type="error" :title="error" show-icon :closable="false" class="error" />

  <el-row :gutter="16">
    <el-col :xs="24" :lg="15">
      <el-card shadow="never">
        <TopologyGraph
          v-if="topology"
          :topology="topology"
          :affected="affected"
          :suspected="suspected"
          :counts="counts"
          :selected="selected"
          :height="460"
          @select="(name) => (selected = name)"
        />
        <el-skeleton v-else :rows="8" animated />
      </el-card>
    </el-col>
    <el-col :xs="24" :lg="9">
      <el-card shadow="never">
        <template #header>
          <div class="card-head">{{ node ? node.name : '进行中的 Incident' }}</div>
        </template>
        <template v-if="node">
          <el-descriptions :column="1" size="small">
            <el-descriptions-item label="类型">{{ node.kind }}</el-descriptions-item>
            <el-descriptions-item label="说明">{{ node.description ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="依赖">{{ dependsOn.join('、') || '无' }}</el-descriptions-item>
            <el-descriptions-item label="被依赖">{{ dependedOnBy.join('、') || '无' }}</el-descriptions-item>
          </el-descriptions>
          <h4>相关的进行中 Incident</h4>
          <el-button link type="primary" class="clear" @click="selected = undefined">看全部</el-button>
        </template>
        <el-empty
          v-if="!(node ? related : active).length"
          :image-size="64"
          :description="node ? '所选时间内没有进行中的 Incident 涉及它' : '所选时间内没有进行中的 Incident；点击图上的服务查看详情'"
        />
        <div v-for="i in node ? related : active" :key="i.incident_id" class="incident">
          <div class="incident-head">
            <SeverityTag :severity="i.severity" />
            <StatusTag :status="i.status" />
            <router-link :to="`/incidents/${i.incident_id}`" class="incident-title">{{ i.title }}</router-link>
          </div>
          <div class="incident-meta">
            <span class="muted">最近告警 {{ ago(i.last_alert_at, now) }} · Agent：</span>
            <AnalysisBadge :analyses="analyses[i.incident_id]" />
          </div>
        </div>
      </el-card>
    </el-col>
  </el-row>
</template>

<style scoped>
.error {
  margin-bottom: 12px;
}

.window {
  margin-left: auto;
}

h4 {
  display: inline-block;
  margin: 16px 0 8px;
  font-size: 14px;
}

.clear {
  margin-left: 8px;
}

.incident {
  padding: 10px 0;
  border-top: 1px solid var(--el-border-color-lighter);
}

.incident-head {
  display: flex;
  align-items: center;
  gap: 6px;
}

.incident-title {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--el-color-primary);
  text-decoration: none;
}

.incident-meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 4px;
  margin-top: 6px;
  font-size: 13px;
}
</style>
