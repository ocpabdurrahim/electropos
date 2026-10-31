/// <reference types="vitest" />
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: { port: 5175, strictPort: true },
  build: {
    sourcemap: true,
    // Firebase is most of the download; its own chunk stays cached between app updates.
    chunkSizeWarningLimit: 700,
    rollupOptions: { output: { manualChunks: { firebase: ['firebase/app', 'firebase/auth', 'firebase/firestore'] } } },
  },
  test: { environment: 'node', include: ['src/**/*.test.ts'] },
})
