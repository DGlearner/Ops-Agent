<script setup lang="ts">
import type { IncidentStatus } from '@/api/types'
import { STATUS_LABEL, STATUS_TAG } from '@/format'

defineProps<{ status: IncidentStatus }>()
</script>

<template>
  <el-tag :type="STATUS_TAG[status]" effect="plain" size="small">{{ STATUS_LABEL[status] }}</el-tag>
</template>
