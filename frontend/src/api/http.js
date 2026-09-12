import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '../router'

/**
 * Axios 单例 —— 双拦截器
 *
 * 请求拦截：自动注入 `Authorization: Bearer {token}`（走 Gateway JWT 校验）
 * 响应拦截：
 *   - 解包后端统一壳 `R { code, message, data }`，code===200 时直接返回 data
 *   - 401 清登录态并跳登录页
 *   - 其余错误统一 ElMessage 提示 + reject(Error) 交给页面 catch
 */
const service = axios.create({
  baseURL: '/api', // Vite 代理：/api → http://localhost:8081（Gateway）
  timeout: 15000,
})

// ---------- 请求拦截 ----------
service.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('token')
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => Promise.reject(error)
)

// ---------- 响应拦截 ----------
service.interceptors.response.use(
  (response) => {
    const body = response.data
    // 非标准壳（如网关 404 HTML）直接拒绝，由 catch 处理
    if (body === null || typeof body !== 'object' || !('code' in body)) {
      return Promise.reject(new Error('服务响应格式异常'))
    }
    if (body.code === 200) {
      return body.data
    }
    // 401：登录过期/凭证失效 → 清空本地态并跳登录页
    if (body.code === 401) {
      localStorage.removeItem('token')
      localStorage.removeItem('userInfo')
      ElMessage.error('登录已失效，请重新登录')
      router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
      return Promise.reject(new Error(body.message || '未登录'))
    }
    ElMessage.error(body.message || '操作失败')
    return Promise.reject(new Error(body.message || '操作失败'))
  },
  (error) => {
    const msg =
      error.code === 'ECONNABORTED'
        ? '请求超时，请检查后端服务'
        : error.response?.status === 502 || error.response?.status === 503
          ? '后端服务暂不可用'
          : error.response?.status === 404
            ? '接口不存在'
            : `网络异常：${error.message}`
    ElMessage.error(msg)
    return Promise.reject(error)
  }
)

export default service