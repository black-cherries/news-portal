import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    host: '127.0.0.1',
    proxy: {
      // Same-origin API calls in dev; avoids CORS setup.
      '/api': 'http://127.0.0.1:8081',
    },
  },
})
