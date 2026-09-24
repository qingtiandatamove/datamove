import Vue from 'vue'
import ElementUI from 'element-ui'
import 'element-ui/lib/theme-chalk/index.css'

/* ============================================================
 * 主题 (light/dark) —— 越早越好
 *
 * 关键: 在 import ElementUI 之后、在 new Vue() 之前同步执行,
 * 因为 <html class="theme-dark"> 必须在 Vue 渲染第一个组件之前就生效,
 * 否则会出现「亮色闪一下再变暗」的视觉跳变 (App mounted 时机太晚)。
 *
 * 同时把样式直接 import 在 ElementUI 之后, 保证 CSS 注入顺序:
 *   <style>element-ui css</style>     ← 先
 *   <style>theme.scss</style>        ← 后 (覆写生效)
 *   <style>element-dark.scss</style> ← 最后 (暗色覆写生效)
 * ============================================================ */
;(function applyThemeEarly () {
  try {
    const saved = localStorage.getItem('datamove-theme') || 'light'
    if (saved === 'dark') document.documentElement.classList.add('theme-dark')
  } catch (e) { /* localStorage 不可用 (隐私模式) 时忽略, 走默认亮色 */ }
})()
import './styles/theme.scss'
import './styles/element-dark.scss'

import App from './App.vue'
import router from './router'
import store from './store'

import { request, getToken, setToken, removeToken } from '@/utils/request'

Vue.use(ElementUI, { size: 'small', zIndex: 3000 })

Vue.prototype.$axios = request
Vue.prototype.$getToken = getToken
Vue.prototype.$setToken = setToken
Vue.prototype.$removeToken = removeToken

Vue.config.productionTip = false

/**
 * 主题切换全局 API (供顶栏按钮 / 任何组件调用)
 * 设计要点:
 *   - 单源: 写 localStorage + 操作 <html> 类都在这里, layout / login 只调 API, 不直接动 DOM
 *   - 跨页签同步: storage 事件监听, 一个 tab 切其它 tab 跟随
 */
window.__datamoveTheme = {
  THEME_KEY: 'datamove-theme',
  DARK_CLASS: 'theme-dark',
  get () {
    try { return localStorage.getItem(this.THEME_KEY) || 'light' }
    catch (e) { return 'light' }
  },
  set (mode) {
    try { localStorage.setItem(this.THEME_KEY, mode) } catch (e) { /* 隐私模式静默 */ }
    document.documentElement.classList.toggle(this.DARK_CLASS, mode === 'dark')
  },
  toggle () {
    const next = this.get() === 'dark' ? 'light' : 'dark'
    this.set(next)
    return next
  }
}
// 跨页签同步: 一个浏览器多个 tab 切主题跟随
window.addEventListener('storage', e => {
  if (e.key === window.__datamoveTheme.THEME_KEY) {
    window.__datamoveTheme.set(e.newValue || 'light')
  }
})

// 全局兜底: 吞掉未捕获的 promise rejection, 避免 webpack-dev-server 或浏览器把它当作 runtime error 红屏
// 真正的业务错误已在 utils/request.js 拦截器里通过 ElementUI Message 提示
window.addEventListener('unhandledrejection', e => {
  e.preventDefault()
})

// 过滤 ResizeObserver loop 报错 (Element UI 表格/布局尺寸抖动引发的无害警告, 不影响功能)
const _roErr = window.onerror
window.onerror = function (msg, ...rest) {
  if (msg && String(msg).includes('ResizeObserver loop')) return true
  return _roErr ? _roErr.call(this, msg, ...rest) : false
}
window.addEventListener('error', e => {
  if (e.message && e.message.includes('ResizeObserver loop')) {
    e.stopImmediatePropagation()
    e.preventDefault()
  }
}, true)

new Vue({ router, store, render: h => h(App) }).$mount('#app')