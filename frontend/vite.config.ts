import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    host: '127.0.0.1',
    proxy: {
      '/auth': { target: 'https://localhost', secure: false },
      '/orgs': { target: 'https://localhost', secure: false },
      '/services': { target: 'https://localhost', secure: false },
      '/credentials': { target: 'https://localhost', secure: false },
      '/onboarding': { target: 'https://localhost', secure: false },
      '/user':       { target: 'https://localhost', secure: false },
      '/locations':  { target: 'https://localhost', secure: false },
      '/metadata':   { target: 'https://localhost', secure: false },
      '/products':   { target: 'https://localhost', secure: false },
      '/stock':      { target: 'https://localhost', secure: false },
    },
  },
})
