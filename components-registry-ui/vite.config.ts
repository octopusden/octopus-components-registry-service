import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'
import path from 'path'

const deployBase = '/components-registry-service'

export default defineConfig({
  base: `${deployBase}/`,
  plugins: [react(), tailwindcss()],
  test: {
    environment: 'jsdom',
    globals: true,
    setupFiles: ['./src/test/setup.ts'],
    exclude: ['e2e/**', 'node_modules/**'],
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    port: 5173,
    proxy: {
      [`${deployBase}/rest`]: {
        target: 'http://localhost:4567',
        changeOrigin: true,
        rewrite: (path) => path.replace(deployBase, ''),
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: true,
  },
})
