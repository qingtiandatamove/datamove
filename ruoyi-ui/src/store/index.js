import Vue from 'vue'
import Vuex from 'vuex'
import { login, getInfo, logout, setToken, removeToken } from '@/api/auth'

Vue.use(Vuex)

export default new Vuex.Store({
  state: { token: '', user: { userId: '', userName: '', nickName: '' }, roles: [], permissions: [] },
  mutations: {
    SET_TOKEN (s, t) { s.token = t },
    SET_USER  (s, u) { s.user = u },
    SET_ROLES (s, r) { s.roles = r },
    SET_PERMS (s, p) { s.permissions = p }
  },
  actions: {
    login ({ commit }, { username, password }) {
      return login(username, password).then(res => {
        const d = res.data
        setToken(d.token)
        commit('SET_TOKEN', d.token)
        commit('SET_USER', { userId: d.userId, userName: d.userName, nickName: d.nickName })
        commit('SET_ROLES', d.roles || [])
        commit('SET_PERMS', d.permissions || [])
        return res
      })
    },
    info ({ commit }) {
      return getInfo().then(res => {
        if (res.data) commit('SET_USER', res.data)
        return res
      })
    },
    logout ({ commit }) {
      return logout().then(() => {
        commit('SET_TOKEN', '')
        removeToken()
      })
    }
  }
})
