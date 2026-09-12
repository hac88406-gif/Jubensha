import http from './http'
import axios from 'axios'

/**
 * AI 陪练 API
 *
 * 对话主链路：前端 /api/agent/chat → 网关 8081 → agent-gateway 8085 → Python Agent 8000
 *   axios 响应拦截器已解包外层 R，这里直接拿到 ChatResponse：
 *     { code, message(回复文本), data, intent, toolCalls? }
 *
 * 会话记忆重置：Python Agent 直连（Vite 代理 /py → localhost:8000）
 */

/** 发送一条对话（userId + message；历史由 Python Agent 的 Redis 会话记忆管理，不重复传） */
export function fetchAgentChat(payload) {
  return http.post('/agent/chat', payload)
}

/** 重置某用户的服务端会话记忆（Python Agent /api/reset） */
export function resetAgentMemory(userId) {
  return axios
    .post(`/py/api/reset?user_id=${userId}`, null, { timeout: 5000 })
    .then((r) => r.data)
}