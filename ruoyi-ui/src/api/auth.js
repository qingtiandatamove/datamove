import { request, setToken, removeToken } from '@/utils/request'

export function login (username, password) {
  return request({ url: '/auth/login', method: 'post', data: { username, password } })
}

/** 发送短信验证码 (登录用) */
export function sendSmsCode (phone) {
  return request({ url: '/auth/sms-code', method: 'post', data: { phone } })
}

/** 短信验证码登录 */
export function loginBySms (phone, code) {
  return request({ url: '/auth/login-sms', method: 'post', data: { phone, code } })
}

/** 发送邮件验证码 (登录用) */
export function sendEmailCode (email) {
  return request({ url: '/auth/email-code', method: 'post', data: { email } })
}

/** 邮件验证码登录 */
export function loginByEmail (email, code) {
  return request({ url: '/auth/login-email', method: 'post', data: { email, code } })
}

export function getInfo () {
  return request({ url: '/auth/info', method: 'get' })
}

export function logout () {
  return request({ url: '/auth/logout', method: 'post' })
}

export function changePassword (data) {
  return request({ url: '/auth/password', method: 'put', data })
}

export { setToken, removeToken }
