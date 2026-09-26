import Vue from 'vue'
import VueRouter from 'vue-router'
import { Message } from 'element-ui'
import store from '@/store'

Vue.use(VueRouter)

/* 不做菜单鉴权的路径: 首页/个人中心人人可用, 登录页本身放行 */
const PUBLIC_PATHS = ['/index', '/profile', '/login']

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
      { path: 'sync/dashboard',   component: () => import('@/views/sync/dashboard.vue'),  meta: { title: '任务大盘' } },
      { path: 'sync/log',         component: () => import('@/views/sync/log.vue'),        meta: { title: '同步日志' } },
      { path: 'sync/license',     component: () => import('@/views/sync/license.vue'),    meta: { title: '授权管理' } },
      { path: 'sync/audit',       component: () => import('@/views/sync/audit.vue'),       meta: { title: '审计日志' } },
      { path: 'sync/alert',       component: () => import('@/views/sync/alert.vue'),       meta: { title: '告警中心' } },
      { path: 'sync/template',    component: () => import('@/views/sync/template.vue'),    meta: { title: '模板市场' } },
      { path: 'sync/ai',          component: () => import('@/views/sync/ai.vue'),          meta: { title: 'AI 配置助手' } },
      { path: 'system/user',      component: () => import('@/views/system/user.vue'),     meta: { title: '用户管理' } },
      { path: 'system/role',      component: () => import('@/views/system/role.vue'),     meta: { title: '角色管理' } },
      { path: 'profile',          component: () => import('@/views/profile.vue'),         meta: { title: '我的' } }
    ]
  }
]

const router = new VueRouter({ mode: 'hash', routes })
router.beforeEach(async (to, from, next) => {
  document.title = (to.meta?.title ? to.meta.title + ' - ' : '') + 'DataMove 数据同步工具'
  const token = localStorage.getItem('Admin-Token')
  if (to.path === '/login') return next()
  if (!token) return next('/login')
  // 刷新页面后 Vuex 会被重置, 但有 token 仍是登录态, 用 token 换回用户信息
  // (SQL 收藏的「编辑/删除」按钮、个人中心都依赖 state.user)
  // 权限为空也要换: 老会话是加「用户授权」功能之前登录的, 本地还没缓存过权限,
  // 不补回来所有 v-if="$hasPerm(...)" 的按钮会全部消失
  if (!store.state.user.userName || !(store.state.permissions || []).length) {
    try { await store.dispatch('info') } catch (e) { /* 401 交由 request 拦截器统一处理 */ }
  }
  // 菜单(含可见路由)优先从缓存取, 没有才请求一次
  try { await store.dispatch('loadMenus') } catch (e) { /* 菜单拉取失败不阻塞跳转 */ }

  /* 越权访问拦截: 菜单里没有这个页面(note: 菜单是按权限下发的)就不让进。
     菜单为空时(接口异常)一律放行 —— 宁可多放行, 也不能因为一次接口抖动把人挡在门外 */
  const menus = store.state.menus || []
  if (PUBLIC_PATHS.indexOf(to.path) === -1 && menus.length && !store.getters.isAdmin) {
    if (store.getters.menuPaths.indexOf(to.path) === -1) {
      Message.warning('没有该页面的访问权限')
      return next('/index')
    }
  }
  next()
})
export default router
