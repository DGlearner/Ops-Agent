<script setup lang="ts">
// Experience the assistant proposed to keep for every session (agent/src/ops_agent/chat/knowledge.py). Nothing
// here is used until approved; a review names the version it saw, so two reviewers cannot overwrite each other.
import { ElMessage } from 'element-plus'
import { onMounted, ref, watch } from 'vue'
import { chatApi, type MemoryCard } from '@/api/chat'
import { MEMORY_FLAG } from '@/chat'
import { errorMessage } from '@/errors'
import { formatTime } from '@/format'

const status = ref<MemoryCard['status']>('candidate')
const items = ref<MemoryCard[]>([])
const loading = ref(false)
const error = ref('')
const busy = ref<string | null>(null)

async function load() {
  loading.value = true
  try {
    items.value = (await chatApi.memories(status.value)).items
    error.value = ''
  } catch (e) {
    error.value = errorMessage(e)
  } finally {
    loading.value = false
  }
}

async function review(m: MemoryCard, approve: boolean) {
  busy.value = m.memory_id
  try {
    await chatApi.reviewMemory(m.memory_id, m.version, approve)
    ElMessage.success(approve ? '已通过，之后的对话会参考它' : '已拒绝')
  } catch (e) {
    ElMessage.warning(errorMessage(e))
  } finally {
    busy.value = null
    await load()
  }
}

watch(status, () => void load())
onMounted(load)
</script>

<template>
  <div class="memories">
    <div class="head">
      <h2>经验审核</h2>
      <el-radio-group v-model="status" size="small">
        <el-radio-button value="candidate">待审核</el-radio-button>
        <el-radio-button value="approved">已通过</el-radio-button>
        <el-radio-button value="rejected">已拒绝</el-radio-button>
      </el-radio-group>
    </div>
    <p class="muted">
      值班助手提出的、以后其他对话也用得上的运维经验。通过之后才会提供给模型，而且只作参考，不当作系统现在的状态。
    </p>
    <el-alert v-if="error" type="error" :title="error" show-icon :closable="false" />
    <div v-loading="loading">
      <el-empty v-if="!items.length && !loading" description="没有" />
      <div v-for="m in items" :key="m.memory_id" class="memory">
        <div class="text">{{ m.text }}</div>
        <div class="muted meta">
          涉及 {{ m.components.join('、') }} · {{ formatTime(m.created_at) }} ·
          <router-link :to="`/chat/${m.source_session}`">来自的对话</router-link>
        </div>
        <el-alert
          v-for="f in m.flags"
          :key="f"
          type="warning"
          :title="MEMORY_FLAG[f] ?? f"
          show-icon
          :closable="false"
          class="flag"
        />
        <div class="evidence">
          <span class="muted">依据</span>
          <component
            :is="e.incident_id || e.kind === 'incident' ? 'router-link' : 'span'"
            v-for="e in m.evidence"
            :key="e.ref"
            :to="`/incidents/${e.kind === 'incident' ? e.id : e.incident_id}`"
            class="chip"
            :title="e.label"
          >
            {{ e.ref }} {{ e.label }}
          </component>
        </div>
        <div v-if="m.status === 'candidate'" class="buttons">
          <el-button type="primary" size="small" :loading="busy === m.memory_id" @click="review(m, true)">通过</el-button>
          <el-button size="small" :disabled="busy === m.memory_id" @click="review(m, false)">拒绝</el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.memories {
  max-width: 900px;
}

.head {
  display: flex;
  align-items: center;
  gap: 16px;
}

.head h2 {
  margin: 0;
  font-size: 18px;
}

.memory {
  border: 1px solid var(--el-border-color-light);
  border-radius: 6px;
  padding: 12px;
  margin-bottom: 12px;
  background: var(--el-bg-color);
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.text {
  font-size: 14px;
}

.meta {
  font-size: 12px;
}

.evidence {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  font-size: 12px;
  align-items: center;
}

.chip {
  max-width: 300px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  padding: 0 6px;
  border-radius: 3px;
  background: var(--el-fill-color-light);
  color: var(--el-text-color-regular);
  text-decoration: none;
}

.buttons {
  display: flex;
  gap: 8px;
}
</style>
