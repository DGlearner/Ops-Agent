import { fileURLToPath, URL } from 'node:url'
import vue from '@vitejs/plugin-vue'
import { defineConfig } from 'vite'

// core-service. The dev server and `vite preview` forward /api to it, so the browser sees a single origin.
const core = process.env.OPS_CORE_URL ?? 'http://127.0.0.1:8080'
const proxy = { '/api': { target: core, changeOrigin: true } }

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: { '@': fileURLToPath(new URL('./src', import.meta.url)) },
  },
  server: { host: '127.0.0.1', port: 5180, strictPort: true, proxy },
  preview: { host: '127.0.0.1', port: 4180, strictPort: true, proxy },
  build: {
    // Element Plus is registered as a whole; one large vendor chunk is fine for a console on the local network.
    chunkSizeWarningLimit: 1500,
  },
})
