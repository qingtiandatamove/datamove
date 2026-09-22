import axios from 'axios'
import { Message, MessageBox } from 'element-ui'
import { getToken, setToken, removeToken } from '@/utils/auth'

const baseURL = process.env.NODE_ENV === 'production' ? '' : '/dev-api'
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

// 防止 401 弹窗被多次触发
let isLoginExpiring = false

service.interceptors.response.use(r => {
  const data = r.data
  // 业务成功
  if (data && data.code === 200) return data
  // 登录过期 - 走弹窗逻辑,不弹 Message
  if (data && data.code === 401) {
    if (!isLoginExpiring) {
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
    return safeReject(new Error(data.msg || '登录已过期'))
  }
  // 业务错误 - 默认在页面上以 ElementUI Message 提示
  // (config.customError=true 时跳过全局弹窗, 由调用方自行内联展示)
  const errMsg = (data && data.msg) || '操作失败'
  if (!(r.config && r.config.customError)) Message.error(errMsg)
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
    if (!isLoginExpiring) {
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
    errMsg = '登录已过期,请重新登录'
  } else if (status === 403) {  // 重新打 401 时同变量再次使用
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
  if (!(e.config && e.config.customError)) Message.error(errMsg)
  // 把 HTTP 状态码带出去 —— 否则被包装成 Error 后调用方无法区分 404/401
  const err = new Error(errMsg)
  err.status = status
  err.response = e.response
  return safeReject(err)
})

export function request(config) { return service(config) }
export { getToken, setToken, removeToken }
export default service