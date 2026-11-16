import react from '@vitejs/plugin-react';
import { defineConfig } from 'vitest/config';

export default defineConfig({
  plugins: [react()],
  server: {
    // The dev server stands in for nginx, which proxies /api in the image (spec 6.8).
    proxy: { '/api': 'http://localhost:8080' },
  },
  test: {
    // e2e/ holds Playwright's specs, run by `npm run e2e` against the compose stack.
    exclude: ['**/node_modules/**', 'e2e/**/*.spec.ts'],
    environment: 'jsdom',
    setupFiles: ['./src/test/setup.ts'],
  },
});
