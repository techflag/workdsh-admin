import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'
import { readFileSync } from 'node:fs'
const tlsKey = process.env.WORKDSH_WEB_TLS_KEY
const tlsCert = process.env.WORKDSH_WEB_TLS_CERT
if (!!tlsKey !== !!tlsCert) throw new Error('Portal HTTPS requires both key and certificate')
const https = tlsKey ? { key:readFileSync(tlsKey),cert:readFileSync(tlsCert),minVersion:'TLSv1.2' } : undefined

export default defineConfig({
  plugins: [vue()],
  server: { https, port: Number(process.env.WORKDSH_WEB_PORT || 18891),
    proxy: { '/api': process.env.WORKDSH_ADMIN_URL || 'http://127.0.0.1:18890' } },
})
