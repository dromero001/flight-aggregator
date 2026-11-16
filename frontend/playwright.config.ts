import { defineConfig, devices } from '@playwright/test';

// The specs run against the compose stack (docker compose up --build), through nginx.
// One worker, no retries, and the real rate limits. The projects run in this order, each one after
// the previous: the admin run changes the generation and the rate-limit test spends the window, so
// both go last. Playwright has no order-only dependency: a failed project also skips every project
// after it, so a red run reports the first failure, and the later browsers as "did not run".
const specs = ['smoke', 'search', 're-price', 'accessibility', 'admin'];

// Each browser runs every spec above before the next browser starts (plan 6 E6). Run back to back,
// the three browsers' searches and re-prices would crowd one minute's limits; the admin's run
// between them spaces them out.
const browsers = {
  chromium: devices['Desktop Chrome'],
  firefox: devices['Desktop Firefox'],
  webkit: devices['Desktop Safari'],
};

const browserProjects = Object.entries(browsers).flatMap(([browser, device]) =>
  specs.map((spec) => ({ name: `${spec}-${browser}`, testMatch: `${spec}.spec.ts`, use: device })),
);

// The observability checks read the stack's telemetry through Grafana and Prometheus; they run once,
// in Chromium, after every browser's admin run. The rate-limit test sends its requests from Node,
// not from a browser, so it runs once, last.
const projects = [
  ...browserProjects,
  { name: 'observability', testMatch: 'observability.spec.ts', use: devices['Desktop Chrome'] },
  { name: 'rate-limit', testMatch: 'rate-limit.spec.ts', use: devices['Desktop Chrome'] },
];

export default defineConfig({
  testDir: './e2e',
  globalSetup: './e2e/global-setup.ts',
  workers: 1,
  retries: 0,
  reporter: [['list'], ['html', { open: 'never' }]],
  outputDir: 'test-results/e2e',
  use: { baseURL: 'http://localhost:3000' },
  projects: projects.map((project, index) => ({
    ...project,
    dependencies: index === 0 ? [] : [projects[index - 1].name],
  })),
});
