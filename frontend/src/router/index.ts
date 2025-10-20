import { createRouter, createWebHistory } from 'vue-router'

const routes = [
  { path: '/', redirect: '/browser' },
  { path: '/browser', name: 'Browser', component: () => import('../views/BrowserView.vue') },
  { path: '/query',   name: 'Query',   component: () => import('../views/QueryView.vue') },
  { path: '/admin',   name: 'Admin',   component: () => import('../views/AdminView.vue') },
]

export const router = createRouter({
  history: createWebHistory(),
  routes,
})

