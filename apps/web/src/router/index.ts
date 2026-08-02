import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import LoginView from '../views/LoginView.vue'
import WorkspaceView from '../views/WorkspaceView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/workspace' },
    { path: '/login', component: LoginView, meta: { public: true } },
    { path: '/workspace', component: WorkspaceView },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (!auth.initialized) await auth.restoreSession()
  if (!to.meta.public && !auth.authenticated) return { path: '/login', query: { redirect: to.fullPath } }
  if (to.path === '/login' && auth.authenticated) return '/workspace'
})

export default router
