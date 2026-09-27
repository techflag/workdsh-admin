import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: { port: Number(process.env.WORKDSH_WEB_PORT || 18891),
    proxy: { '/api': process.env.WORKDSH_ADMIN_URL || 'http://127.0.0.1:18890' } },
})
