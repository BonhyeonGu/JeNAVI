import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/', redirect: '/fast' },
  { path: '/fast', name: 'Fast', component: () => import('../views/FastView.vue') },
  { path: '/browser', name: 'Browser', component: () => import('../views/BrowserView.vue') },
  { path: '/query',   name: 'Query',   component: () => import('../views/QueryView.vue') },
  { path: '/realtime', name: 'Realtime', component: () => import('../views/MqttView.vue') },
  { path: '/experiment', name: 'Experiment', component: () => import('../views/ExperimentView.vue') },
  { path: '/admin',   name: 'Admin',   component: () => import('../views/AdminView.vue') },
]

export const router = createRouter({
  history: createWebHistory(),
  routes,
})

