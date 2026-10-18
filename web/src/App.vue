<script setup lang="ts">
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()
const activeMenu = computed(() => (route.path.startsWith('/topology') ? '/topology' : '/incidents'))
</script>

<template>
  <el-container class="layout">
    <el-header class="header">
      <router-link to="/incidents" class="brand">
        Ops-Agent
        <span class="brand-note">值班运维</span>
      </router-link>
      <el-menu mode="horizontal" router :default-active="activeMenu" :ellipsis="false" class="nav">
        <el-menu-item index="/incidents">Incident</el-menu-item>
        <el-menu-item index="/topology">服务拓扑</el-menu-item>
      </el-menu>
    </el-header>
    <el-main class="main">
      <router-view :key="route.path" />
    </el-main>
  </el-container>
</template>

<style scoped>
.layout {
  min-height: 100vh;
}

.header {
  display: flex;
  align-items: center;
  gap: 24px;
  background: var(--el-bg-color);
  border-bottom: 1px solid var(--el-border-color-light);
}

.brand {
  font-size: 18px;
  font-weight: 600;
  color: var(--el-text-color-primary);
  text-decoration: none;
  white-space: nowrap;
}

.brand-note {
  margin-left: 6px;
  font-size: 13px;
  font-weight: 400;
  color: var(--el-text-color-secondary);
}

.nav {
  flex: 1;
  border-bottom: none;
}

.main {
  max-width: 1440px;
  width: 100%;
  margin: 0 auto;
  padding: 20px;
}
</style>
