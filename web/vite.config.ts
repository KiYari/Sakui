import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

// Overridable so a second backend (another branch, a test instance) can run
// next to the usual one without editing this file.
// Read through globalThis: this config is type-checked without @types/node.
const env = (globalThis as { process?: { env: Record<string, string | undefined> } }).process?.env
const apiTarget = env?.EECK_API_TARGET ?? 'http://localhost:3001'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: apiTarget,
        changeOrigin: true,
      },
      '/ws': {
        target: apiTarget.replace(/^http/, 'ws'),
        ws: true,
      },
    },
  },
})
