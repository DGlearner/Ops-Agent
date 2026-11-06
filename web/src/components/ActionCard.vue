<script setup lang="ts">
// An action the assistant proposed (agent/src/ops_agent/chat/actions.py): nothing runs until the user confirms it
// here, and the receipt underneath says what core-service really did, not what was meant.
import { computed, ref } from 'vue'
import type { ActionCard } from '@/api/chat'
import { ACTION_STATUS, canDecide, RISK_LABEL } from '@/chat'
import { formatTime } from '@/format'

const props = defineProps<{ card: ActionCard; busy?: boolean }>()
const emit = defineEmits<{ confirm: [card: ActionCard]; decline: [card: ActionCard] }>()

const status = computed(() => ACTION_STATUS[props.card.status])
const open = computed(() => canDecide(props.card))
const showRequest = ref(false)
const STATUS_LABEL: Record<string, string> = {
  open: '未处理',
  acknowledged: '已确认',
  investigating: '排查中',
  mitigated: '已缓解',
  resolved: '已解决',
  closed: '已关闭',
}
</script>

<template>
  <div class="action-card" :class="card.status">
    <div class="head">
      <strong>{{ card.title }}</strong>
      <el-tag size="small" :type="status.type" effect="light">{{ status.label }}</el-tag>
      <span class="muted">{{ RISK_LABEL[card.risk] }}</span>
    </div>
    <div class="row">
      <span class="muted">对象</span>
      <router-link :to="`/incidents/${card.target.incident_id}`">{{ card.target.ref }} {{ card.target.title }}</router-link>
      <span class="muted">（提出时：{{ STATUS_LABEL[card.target.status] ?? card.target.status }}，version {{ card.target.version }}）</span>
    </div>
    <div class="row">
      <span class="muted">理由</span>
      <span>{{ card.reason }}</span>
    </div>
    <div class="row">
      <span class="muted">依据</span>
      <span class="evidence">
        <span v-for="e in card.evidence" :key="e.ref" class="chip" :title="e.label">{{ e.ref }} {{ e.label }}</span>
      </span>
    </div>
    <template v-if="card.remedial">
      <div class="row">
        <span class="muted">前置条件</span>
        <ul class="plain">
          <li v-for="p in card.preconditions" :key="p">{{ p }}</li>
        </ul>
      </div>
      <div class="row"><span class="muted">预期效果</span><span>{{ card.effect }}</span></div>
      <div class="row"><span class="muted">影响范围</span><span>{{ card.blast_radius }}</span></div>
      <div class="row"><span class="muted">回退</span><span>{{ card.rollback }}</span></div>
      <div class="row"><span class="muted">验证</span><span>{{ card.verify }}</span></div>
      <div class="row muted small">
        Runbook {{ card.runbook?.id }} v{{ card.runbook?.version }} · 处置单第 {{ card.case?.state_version }} 版 ·
        摘要 <span class="mono">{{ card.payload_digest?.slice(7, 19) }}</span>
      </div>
    </template>

    <div v-if="open" class="buttons">
      <el-button type="primary" size="small" :loading="busy" @click="emit('confirm', card)">确认执行</el-button>
      <el-button size="small" :disabled="busy" @click="emit('decline', card)">拒绝</el-button>
      <span class="muted">{{ formatTime(card.expires_at) }} 前有效；{{
        card.remedial ? '执行前会再次核对处置单和靶场的状态，只执行一次' : '执行前会再次核对 Incident 是否被改过'
      }}</span>
    </div>

    <div v-if="card.receipt" class="receipt">
      <div>
        <span class="muted">回执</span>
        {{ card.receipt.summary }}
      </div>
      <div v-if="card.receipt.remediation?.observed" class="muted">
        读回：<span class="mono">{{ JSON.stringify(card.receipt.remediation.observed) }}</span>
      </div>
      <ul v-if="card.receipt.checks.length" class="checks muted">
        <li v-for="c in card.receipt.checks" :key="c">{{ c }}</li>
      </ul>
      <div v-if="card.receipt.request" class="muted">
        core-service：HTTP {{ card.receipt.response?.status || '无回应' }}{{
          card.receipt.response?.code ? ` ${card.receipt.response.code}` : ''
        }}{{ card.receipt.observed ? `，读回：${STATUS_LABEL[card.receipt.observed.status] ?? card.receipt.observed.status}，version ${card.receipt.observed.version}` : '' }}
        <el-button link size="small" type="primary" @click="showRequest = !showRequest">
          {{ showRequest ? '收起请求' : '查看请求' }}
        </el-button>
      </div>
      <pre v-if="showRequest && card.receipt.request" class="request mono">{{
        `${card.receipt.request.method} ${card.receipt.request.path}\n${JSON.stringify(card.receipt.request.body, null, 1)}`
      }}</pre>
    </div>
  </div>
</template>

<style scoped>
.plain {
  margin: 0;
  padding-left: 16px;
}

.small {
  font-size: 12px;
}

.action-card {
  border: 1px solid var(--el-border-color);
  border-left: 3px solid var(--el-color-warning);
  border-radius: 6px;
  padding: 10px 12px;
  margin-top: 10px;
  font-size: 13px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.action-card.succeeded {
  border-left-color: var(--el-color-success);
}

.action-card.failed,
.action-card.blocked {
  border-left-color: var(--el-color-danger);
}

.action-card.declined,
.action-card.expired {
  border-left-color: var(--el-border-color-darker);
}

.head {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.row {
  display: flex;
  gap: 8px;
  align-items: baseline;
  flex-wrap: wrap;
}

.row > .muted:first-child,
.receipt .muted:first-child {
  flex: none;
  width: 32px;
}

.evidence {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  min-width: 0;
}

.chip {
  max-width: 280px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  padding: 0 6px;
  border-radius: 3px;
  background: var(--el-fill-color-light);
}

.buttons {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.receipt {
  border-top: 1px dashed var(--el-border-color);
  padding-top: 6px;
}

.checks {
  margin: 4px 0;
  padding-left: 18px;
}

.request {
  margin: 4px 0 0;
  padding: 6px 8px;
  background: var(--el-fill-color-light);
  border-radius: 4px;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
}
</style>
