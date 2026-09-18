import Vue from 'vue'
import VueRouter from 'vue-router'

Vue.use(VueRouter)

const routes = [
  { path: '/login', component: () => import('@/views/login.vue'), meta: { title: '登录' } },
  {
    path: '/',
    component: () => import('@/layout/index.vue'),
    redirect: '/index',
    children: [
      { path: 'index',         component: () => import('@/views/dashboard.vue'), meta: { title: '首页' } },
      { path: 'browse',        component: () => import('@/views/sync/browse.vue'),     meta: { title: '数据中心' } },
      { path: 'sql',           component: () => import('@/views/sync/sql.vue'),        meta: { title: 'SQL 工作台' } },
      { path: 'sql-log',       component: () => import('@/views/sync/sqlLog.vue'),     meta: { title: 'SQL 执行日志' } },
      { path: 'sync/browse',   redirect: '/browse' },
      { path: 'sync/datasource', component: () => import('@/views/sync/datasource.vue'), meta: { title: '数据源管理' } },
      { path: 'sync/task',        component: () => import('@/views/sync/task.vue'),       meta: { title: '同步任务' } },
      { path: 'sync/log',         component: () => import('@/views/sync/log.vue'),        meta: { title: '同步日志' } },
      { path: 'sync/license',     component: () => import('@/views/sync/license.vue'),    meta: { title: '授权管理' } },
      { path: 'system/user',      component: () => import('@/views/system/user.vue'),     meta: { title: '用户管理' } },
      { path: 'profile',          component: () => import('@/views/profile.vue'),         meta: { title: '我的' } }
    ]
  }
]

const router = new VueRouter({ mode: 'hash', routes })
router.beforeEach((to, from, next) => {
  document.title = (to.meta?.title ? to.meta.title + ' - ' : '') + 'DataMove 数据同步工具'
  const token = localStorage.getItem('Admin-Token')
  if (to.path === '/login') return next()
  if (!token) return next('/login')
  next()
})
export default router
