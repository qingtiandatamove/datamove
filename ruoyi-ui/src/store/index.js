import Vue from 'vue'
import Vuex from 'vuex'
import { login, loginBySms, loginByEmail, getInfo, logout, setToken, removeToken } from '@/api/auth'
import { getMyMenus } from '@/api/datamove'

Vue.use(Vuex)

/* ============ 角色/权限的本地缓存 (刷新页面后恢复) ============
   必须定义在 store 之前: state 初始化时会同步调用 readCache() */
const ROLES_KEY = 'datamove-roles'
const PERMS_KEY = 'datamove-perms'

function readCache (key) {
  try { return JSON.parse(localStorage.getItem(key) || '[]') } catch (e) { return [] }
}
function writeCache (key, val) {
  try { localStorage.setItem(key, JSON.stringify(val)) } catch (e) { /* 隐私模式静默 */ }
}
function removeCache (key) {
  try { localStorage.removeItem(key) } catch (e) { /* 隐私模式静默 */ }
}

export default new Vuex.Store({
  /* roles/permissions 从 localStorage 初始化:
     刷新页面 Vuex 会重置, 但用户仍是登录态(有 token), 若权限丢空,
     所有 v-if="$hasPerm(...)" 的按钮会全部消失 —— 必须跟着 token 一起持久化 */
  state: {
    token: '',
    user: { userId: '', userName: '', nickName: '' },
    roles: readCache(ROLES_KEY),
    permissions: readCache(PERMS_KEY),
    /* 侧边栏菜单: 由后端按当前用户权限算出, 前端不再写死 */
    menus: []
  },
  mutations: {
    SET_TOKEN (s, t) { s.token = t },
    SET_USER  (s, u) { s.user = u },
    SET_ROLES (s, r) { s.roles = r || []; writeCache(ROLES_KEY, s.roles) },
    SET_PERMS (s, p) { s.permissions = p || []; writeCache(PERMS_KEY, s.permissions) },
    SET_MENUS (s, m) { s.menus = m || [] },
    CLEAR_AUTH (s) {
      s.token = ''; s.user = {}; s.roles = []; s.permissions = []; s.menus = []
      removeCache(ROLES_KEY); removeCache(PERMS_KEY); removeToken()
    }
  },
  getters: {
    /* 全部可见菜单的路由路径(扁平化), 路由守卫据此拦截无权限的直连访问 */
    menuPaths (s) {
      const paths = []
      const walk = list => (list || []).forEach(m => {
        if (m.path) paths.push(m.path)
        walk(m.children)
      })
      walk(s.menus)
      return paths
    },
    isAdmin (s) {
      return (s.roles || []).indexOf('admin') !== -1 || (s.permissions || []).indexOf('*:*:*') !== -1
    }
  },
  actions: {
    login ({ commit }, { username, password }) {
      return login(username, password).then(res => {
        commitAndSetToken(commit, res)
        return res
      })
    },
    loginBySms ({ commit }, { phone, code }) {
      return loginBySms(phone, code).then(res => {
        commitAndSetToken(commit, res)
        return res
      })
    },
    loginByEmail ({ commit }, { email, code }) {
      return loginByEmail(email, code).then(res => {
        commitAndSetToken(commit, res)
        return res
      })
    },
    info ({ commit }) {
      return getInfo().then(res => {
        const d = res.data
        if (d) {
          commit('SET_USER', d)
          /* 刷新页面 / 旧会话(登录时还没缓存权限) 都靠这里把权限补回来:
             /auth/info 返回的 LoginUser 里带 roles/permissions(由 JWT 过滤器实时算出) */
          if (d.roles) commit('SET_ROLES', d.roles)
          if (d.permissions) commit('SET_PERMS', d.permissions)
        }
        return res
      })
    },
    /* 拉当前用户可见菜单; 已加载过就不再请求(同一次会话内菜单不会自己变)。
       授权变更后需要重新登录才会重新拉取 —— 与按钮权限的刷新策略保持一致 */
    loadMenus ({ state, commit }) {
      if (state.menus && state.menus.length) return Promise.resolve(state.menus)
      return getMyMenus().then(res => {
        commit('SET_MENUS', res.data || [])
        return state.menus
      })
    },
    logout ({ commit }) {
      /* 退出必须连同角色/权限缓存一起清掉, 否则换账号登录后仍沿用上一家的权限快照 */
      return logout().then(() => { commit('CLEAR_AUTH') }).catch(() => { commit('CLEAR_AUTH') })
    }
  }
})

/** 账号密码 / 短信登录共用: 提取 token + 角色 + 权限 + 写 localStorage */
function commitAndSetToken (commit, res) {
  const d = res.data
  setToken(d.token)
  commit('SET_TOKEN', d.token)
  /* 先清空菜单: 换账号登录时必须重新拉, 否则会沿用上一个账号的菜单 */
  commit('SET_MENUS', [])
  commit('SET_USER', { userId: d.userId, userName: d.userName, nickName: d.nickName })
  commit('SET_ROLES', d.roles || [])
  commit('SET_PERMS', d.permissions || [])
}
