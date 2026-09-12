import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vite.dev/config/
export default defineConfig({
  plugins: [vue()],
  server: {
    host: true,          // 允许局域网访问（演示时手机/同事可访问）
    port: 5174,          // 新项目专用端口，避开旧项目 5173
    proxy: {
      // 全部后端请求走网关 8081（Gateway 路由 + JWT 鉴权统一在这里处理）
      '/api': {
        target: 'http://localhost:8081',
        changeOrigin: true,
      },
      // Python Agent 直连（仅 AI 陪练"重置会话记忆"用，留着仅供开发联调）
      '/py': {
        target: 'http://localhost:8000',
        changeOrigin: true,
        rewrite: (path) => path.replace(/^\/py/, ''), // 剥掉 /py 前缀再转发
      },
    },
  },
  build: {
    chunkSizeWarningLimit: 1500, // Element Plus 全量引入时放宽分包告警
  },
})