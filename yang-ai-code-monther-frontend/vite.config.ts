import { fileURLToPath, URL } from 'node:url'

import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import vueDevTools from 'vite-plugin-vue-devtools'

// https://vite.dev/config/
export default defineConfig({
  plugins: [
    vue(),
    vueDevTools(),
  ],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    proxy: {
      // 开发环境把 /api 请求转发到后端(端口 8123,context-path 为 /api)
      // 这样前端里统一写 /api/xxx,后端就绪后直接可联调
      '/api': {
        target: 'http://localhost:8123',
        changeOrigin: true,
      },
    },
  },
})
