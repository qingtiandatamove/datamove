import axios from 'axios'
import { Message, MessageBox } from 'element-ui'
import { getToken, setToken, removeToken } from '@/utils/auth'

const baseURL = process.env.NODE_ENV === 'production' ? '/prod-api' : '/dev-api'
const service = axios.create({ baseURL, timeout: 30000 })

/**
 * 防 unhandled promise rejection
 * 调用方即使只有 .then 没 .catch, 也不会触发 webpack overlay 红屏
 */
function safeReject (err) {
  const p = Promise.reject(err)
  // 静默兜底,业务方没 catch 时不再向外冒泡
  p.catch(() => {})
  return p
}

service.interceptors.request.use(c => {
  const t = getToken()
  if (t) c.headers['Authorization'] = 'Bearer ' + t
  return c
}, e => Promise.reject(e))

// 登录过期只弹一次确认框: 页面往往同时发出好几个请求(菜单/用户信息/列表),
// 令牌失效时它们会一起 401, 不挡住就是一叠 MessageBox
let isLoginExpiring = false

function handleLoginExpired () {
  if (isLoginExpiring) return
  isLoginExpiring = true
  MessageBox.confirm('登录已过期,请重新登录', '系统提示', {
    confirmButtonText: '重新登录',
    cancelButtonText: '取消',
    type: 'warning'
  }).then(() => {
    isLoginExpiring = false
    removeToken()
    location.href = '/login'
  }).catch(() => { isLoginExpiring = false })
}

/**
 * 同样的错误文案短时间内只提示一次
 *
 * <p>一个页面往往并发好几个请求, 服务抖动或令牌失效时它们会返回同一句错误,
 * 逐个弹就是满屏 toast —— 这里按文案去重, 3 秒内相同内容只显示一次。
 */
const lastMsgAt = new Map()
function toastError (msg) {
  const now = Date.now()
  const last = lastMsgAt.get(msg) || 0
  if (now - last < 3000) return
  lastMsgAt.set(msg, now)
  Message.error(msg)
}

service.interceptors.response.use(r => {
  const data = r.data
  // 业务成功
  if (data && data.code === 200) return data
  // 登录过期 - 走弹窗逻辑,不弹 Message
  if (data && data.code === 401) {
    handleLoginExpired()
    return safeReject(new Error(data.msg || '登录已过期'))
  }
  // 业务错误 - 默认在页面上以 ElementUI Message 提示
  // (config.customError=true 时跳过全局弹窗, 由调用方自行内联展示)
  const errMsg = (data && data.msg) || '操作失败'
  if (!(r.config && r.config.customError)) toastError(errMsg)
  // 用字符串 error,调用方 .catch 即使不写也不会触发 unhandled rejection 红屏
  return safeReject(new Error(errMsg))
}, e => {
  // HTTP / 网络层错误
  const status = e.response && e.response.status
  let errMsg
  if (e.code === 'ECONNABORTED' || /timeout/i.test(e.message || '')) {
    errMsg = '请求超时,请稍后重试'
  } else if (status === 400) {
    errMsg = '请求参数错误'
  } else if (status === 401) {
    handleLoginExpired()
    // 过期提示统一交给上面的确认框, 这里不再叠 toast:
    // 并发请求会同时 401, 每个都弹一次就是一屏提示框
    const expired = new Error('登录已过期,请重新登录')
    expired.status = status
    expired.response = e.response
    return safeReject(expired)
  } else if (status === 403) {
    errMsg = '没有访问权限'
  } else if (status === 404) {
    errMsg = '请求资源不存在'
  } else if (status === 500) {
    errMsg = '服务器内部错误'
  } else if (status && status >= 500) {
    errMsg = '服务暂不可用,请稍后重试'
  } else if (!e.response) {
    // 后端完全连不上 / 跨域 / 断网
    errMsg = '网络异常,请检查您的网络后重试'
  } else {
    errMsg = (e.response.data && e.response.data.msg) || e.message || '请求失败'
  }
  // config.customError=true 时跳过全局弹窗, 由调用方自行判断
  // (例如 opt-in 的 License 模块: 未启用时 404 应静默降级为占位提示)
  if (!(e.config && e.config.customError)) toastError(errMsg)
  // 把 HTTP 状态码带出去 —— 否则被包装成 Error 后调用方无法区分 404/401
  const err = new Error(errMsg)
  err.status = status
  err.response = e.response
  return safeReject(err)
})

export function request(config) { return service(config) }
export { getToken, setToken, removeToken }
export default service