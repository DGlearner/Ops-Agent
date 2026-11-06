import { createRouter, createWebHistory } from 'vue-router'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/incidents' },
    { path: '/incidents', component: () => import('./views/IncidentList.vue') },
    { path: '/incidents/:id', component: () => import('./views/IncidentDetail.vue'), props: true },
    { path: '/topology', component: () => import('./views/TopologyPage.vue') },
    { path: '/chat/:id?', component: () => import('./views/ChatPage.vue'), props: true },
    { path: '/runs/:id', component: () => import('./views/RunDetail.vue'), props: true },
    { path: '/memories', component: () => import('./views/MemoryReview.vue') },
    { path: '/:pathMatch(.*)*', redirect: '/incidents' },
  ],
})
