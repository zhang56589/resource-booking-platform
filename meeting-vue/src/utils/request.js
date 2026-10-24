import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'

// 后端接口统一挂载在 /api 下，开发环境由 Vite 代理到 http://localhost:8080
const request = axios.create({
  baseURL: '/api',
  timeout: 10000
})

// 防止并发多个 401 触发多次跳转
let redirecting = false

function clearAuth() {
  localStorage.removeItem('token')
  localStorage.removeItem('role')
  localStorage.removeItem('userId')
  localStorage.removeItem('username')
}

function redirectToLogin(msg) {
  clearAuth()
  if (redirecting) return
  redirecting = true
  ElMessage.error(msg || '登录已过期，请重新登录')
  const current = router.currentRoute.value
  router.push({
    path: '/login',
    query: current.path === '/login' ? {} : { redirect: current.fullPath }
  })
  setTimeout(() => {
    redirecting = false
  }, 800)
}

// 请求拦截器：自动携带 Authorization: Bearer <token>
request.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('token')
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => Promise.reject(error)
)

// 响应拦截器：统一处理业务错误与登录态失效
request.interceptors.response.use(
  (response) => {
    const res = response.data
    // 非标准响应体（如文件流）直接返回
    if (res == null || typeof res.code === 'undefined') {
      return res
    }
    // 登录态失效（部分接口以 HTTP 200 + code 401 返回）
    if (res.code === 401) {
      redirectToLogin(res.msg)
      return Promise.reject(res)
    }
    if (res.code !== 200) {
      ElMessage.error(res.msg || '操作失败')
      return Promise.reject(res)
    }
    return res
  },
  (error) => {
    const status = error.response && error.response.status
    const msg = error.response && error.response.data && error.response.data.msg
    if (status === 401) {
      redirectToLogin(msg)
    } else if (status === 403) {
      ElMessage.error(msg || '权限不足，禁止访问')
    } else if (status) {
      ElMessage.error(msg || `请求失败（${status}）`)
    } else {
      ElMessage.error('网络异常，请确认后端服务已启动')
    }
    return Promise.reject(error)
  }
)

export default request