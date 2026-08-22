import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import LoginView from '../views/LoginView.vue'
import AppLayout from '../layouts/AppLayout.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: LoginView, meta: { public: true } },
    {
      path: '/',
      component: AppLayout,
      children: [
        { path: '', redirect: '/workspace' },
        { path: 'workspace', component: () => import('../views/WorkspaceView.vue') },
        {
          path: 'assets',
          component: () => import('../views/MediaAssetsView.vue'),
          meta: { permissions: ['asset:read'] },
        },
        {
          path: 'cases',
          component: () => import('../views/CasesView.vue'),
          meta: { permissions: ['case:read'] },
        },
        {
          path: 'cases/new',
          component: () => import('../views/CaseCreateView.vue'),
          meta: { permissions: ['case:create'] },
        },
        {
          path: 'cases/:caseId',
          component: () => import('../views/CaseDetailView.vue'),
          meta: { permissions: ['case:read'] },
        },
        {
          path: 'knowledge',
          component: () => import('../views/KnowledgeDocumentsView.vue'),
          meta: { permissions: ['knowledge:read'] },
        },
        {
          path: 'model-evaluation',
          component: () => import('../views/DetectionEvaluationView.vue'),
          meta: { permissions: ['model:read'] },
        },
        {
          path: 'agent-tasks',
          component: () => import('../views/AgentTasksView.vue'),
          meta: { permissions: ['agent:trace:read'] },
        },
        {
          path: 'agent-tasks/:taskId',
          component: () => import('../views/AgentTaskDetailView.vue'),
          meta: { permissions: ['agent:trace:read'] },
        },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/workspace', meta: { fallback: true } },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (!auth.initialized) await auth.restoreSession()
  if (!to.meta.public && !auth.authenticated) return { path: '/login', query: { redirect: to.fullPath } }
  if (to.path === '/login' && auth.authenticated) return '/workspace'
  const required = to.meta.permissions ?? []
  if (required.length && !required.every((permission) => auth.hasPermission(permission))) return '/workspace'
})

export default router
