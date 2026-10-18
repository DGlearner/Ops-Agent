<script setup lang="ts">
import type { Severity } from '@/api/types'
import { SEVERITY_LABEL, SEVERITY_TAG } from '@/format'

defineProps<{ severity: Severity }>()
</script>

<template>
  <el-tag :type="SEVERITY_TAG[severity]" :effect="severity === 'critical' || severity === 'high' ? 'dark' : 'light'" size="small">
    {{ SEVERITY_LABEL[severity] }}
  </el-tag>
</template>
