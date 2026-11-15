import { defineConfig, devices } from '@playwright/test';

// The specs run against the compose stack (docker compose up --build), through nginx.
// One worker, no retries, and the real rate limits. The projects run in this order, each one after
// the previous: the admin run changes the generation and the rate-limit test spends the window, so
// both go last.
const specs = ['smoke', 'search', 're-price', 'accessibility', 'admin', 'rate-limit'];

export default defineConfig({
  testDir: './e2e',
  globalSetup: './e2e/global-setup.ts',
  workers: 1,
  retries: 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  outputDir: 'test-results/e2e',
  use: { baseURL: 'http://localhost:3000' },
  projects: specs.map((name, index) => ({
    name,
    testMatch: `${name}.spec.ts`,
    dependencies: index === 0 ? [] : [specs[index - 1]],
    use: { ...devices['Desktop Chrome'] },
  })),
});
