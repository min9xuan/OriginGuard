import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import LoginView from '../views/LoginView.vue'
import AdminLoginView from '../views/AdminLoginView.vue'
import PublicHomeView from '../views/PublicHomeView.vue'
import AppLayout from '../layouts/AppLayout.vue'
import AdminLayout from '../layouts/AdminLayout.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', component: PublicHomeView, meta: { public: true } },
    { path: '/login', component: LoginView, meta: { public: true } },
    { path: '/admin/login', component: AdminLoginView, meta: { public: true, adminLogin: true } },
    {
      path: '/analyze',
      component: AppLayout,
      meta: { investigatorOnly: true },
      children: [
        { path: '', component: () => import('../views/InvestigatorHomeView.vue') },
        { path: 'history', component: () => import('../views/AgentTasksView.vue'), meta: { permissions: ['agent:trace:read'] } },
        { path: 'cases/:caseId', component: () => import('../views/CaseDetailView.vue'), meta: { permissions: ['case:read'] } },
        { path: 'agent-tasks/:taskId', component: () => import('../views/AgentTaskDetailView.vue'), meta: { permissions: ['agent:trace:read'] } },
      ],
    },
    {
      path: '/admin',
      component: AdminLayout,
      meta: { adminOnly: true },
      children: [
        { path: '', component: () => import('../views/WorkspaceView.vue') },
        { path: 'assets', component: () => import('../views/MediaAssetsView.vue'), meta: { permissions: ['asset:read'] } },
        { path: 'cases', component: () => import('../views/CasesView.vue'), meta: { permissions: ['case:read'] } },
        { path: 'cases/:caseId', component: () => import('../views/CaseDetailView.vue'), meta: { permissions: ['case:read'] } },
        { path: 'knowledge', component: () => import('../views/KnowledgeDocumentsView.vue'), meta: { permissions: ['knowledge:read'] } },
        { path: 'model-evaluation', component: () => import('../views/DetectionEvaluationView.vue'), meta: { permissions: ['model:read'] } },
        { path: 'agent-tasks', component: () => import('../views/AgentTasksView.vue'), meta: { permissions: ['agent:trace:read'] } },
        { path: 'agent-tasks/:taskId', component: () => import('../views/AgentTaskDetailView.vue'), meta: { permissions: ['agent:trace:read'] } },
      ],
    },
    { path: '/workspace', redirect: '/admin' },
    { path: '/assets', redirect: '/admin/assets' },
    { path: '/cases', redirect: '/admin/cases' },
    { path: '/knowledge', redirect: '/admin/knowledge' },
    { path: '/model-evaluation', redirect: '/admin/model-evaluation' },
    { path: '/history', redirect: '/analyze/history' },
    { path: '/agent-tasks', redirect: '/analyze/history' },
    { path: '/cases/:caseId', redirect: to => `/analyze/cases/${String(to.params.caseId)}` },
    { path: '/agent-tasks/:taskId', redirect: to => `/analyze/agent-tasks/${String(to.params.taskId)}` },
    { path: '/:pathMatch(.*)*', redirect: '/', meta: { fallback: true } },
  ],
})

router.beforeEach(async (to) => {
  const auth = useAuthStore()
  if (!auth.initialized) {
    // The public landing page must never wait on the backend before it can paint.
    // Session restoration continues in the background and updates its actions in place.
    if (to.path === '/') void auth.restoreSession()
    else await auth.restoreSession()
  }
  const isAdmin = auth.user?.roles.includes('ADMIN') ?? false
  if (to.path === '/login' && auth.authenticated) return isAdmin ? '/admin' : '/analyze'
  if (to.path === '/admin/login' && auth.authenticated) return isAdmin ? '/admin' : '/analyze'
  if (!to.meta.public && !auth.authenticated) {
    const adminTarget = to.matched.some((record) => record.meta.adminOnly)
    return { path: adminTarget ? '/admin/login' : '/login', query: { redirect: to.fullPath } }
  }
  if (to.matched.some((record) => record.meta.adminOnly) && !isAdmin) return '/'
  if (to.matched.some((record) => record.meta.investigatorOnly) && isAdmin) return '/admin'
  const required = to.meta.permissions ?? []
  if (required.length && !required.every((permission) => auth.hasPermission(permission))) return isAdmin ? '/admin' : '/analyze'
})

export default router
