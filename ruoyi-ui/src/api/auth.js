import { request, setToken, removeToken } from '@/utils/request'

export function login (username, password) {
  return request({ url: '/auth/login', method: 'post', data: { username, password } })
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
