import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

const backendUrl = process.env.V2K_E2E_BACKEND_URL ?? 'http://localhost:8080'
if (
  process.env.V2K_E2E_BACKEND_URL &&
  !/^http:\/\/127\.0\.0\.1:\d+$/.test(backendUrl)
) {
  throw new Error(
    'Browser integration backend must be an isolated loopback server',
  )
}

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': backendUrl,
    },
  },
  test: {
    include: ['src/**/*.{test,spec}.{ts,tsx}'],
    environment: 'jsdom',
    setupFiles: './src/test/setup.ts',
  },
})
