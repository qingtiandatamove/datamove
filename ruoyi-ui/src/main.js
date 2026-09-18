import Vue from 'vue'
import ElementUI from 'element-ui'
import 'element-ui/lib/theme-chalk/index.css'
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
