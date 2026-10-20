import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

// The dev server proxies /api to the Spring Boot application so the browser never needs CORS.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://127.0.0.1:8080',
        changeOrigin: true,
      },
    },
  },
  test: {
    environment: 'jsdom',
    environmentOptions: {
      // localStorage is only available on a real origin, not on about:blank.
      jsdom: { url: 'http://localhost:5173/' },
    },
    globals: true,
    setupFiles: './src/test/setup.ts',
    css: false,
  },
});
