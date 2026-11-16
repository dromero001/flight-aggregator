// Shared helpers of the user manual's scripts: paths, the browser, screenshots and the stack.
// Every path is resolved from this file, so the scripts run from any checkout, on macOS or on
// Windows with Git Bash. README.md in this folder says how to run them.
const { execFileSync } = require('node:child_process');
const path = require('node:path');

/** The repository's root: this file is in docs/user-manual/tools/. */
const REPO = path.resolve(__dirname, '..', '..', '..');
const MANUAL = path.join(REPO, 'docs', 'user-manual');
/** Where the screenshots go; MANUAL_IMAGES_DIR writes them elsewhere, to try a script. */
const IMAGES = process.env.MANUAL_IMAGES_DIR || path.join(MANUAL, 'images');
/** The checkout whose compose stack runs; COMPOSE_DIR names another one (a worktree's main checkout). */
const COMPOSE_DIR = process.env.COMPOSE_DIR || REPO;

const DASHBOARD = 'http://localhost:3000';
const GRAFANA = 'http://localhost:3001';
const API = 'http://localhost:8080';
/** The realm's demo admin (README, "Admin access"); ADMIN_USER_PASSWORD overrides it, as in compose. */
const ADMIN_PASSWORD = process.env.ADMIN_USER_PASSWORD || 'demo-admin-password';

/** The dashboard's column, where every screen of the search and the admin lives. */
const COLUMN = { x: 0, y: 0, width: 900, height: 800 };
/** Every answer an opened card can show once its price check ends. */
const ANSWER = /Price confirmed|Price changed|can't confirm|no longer available|Too many price checks/;

/** The frontend's Playwright, installed by `npm ci` in frontend/. */
function playwright() {
  return require(path.join(REPO, 'frontend', 'node_modules', 'playwright'));
}

async function launch() {
  return playwright().chromium.launch({ headless: true });
}

const COMMON = { locale: 'en-GB', timezoneId: 'Europe/Madrid' };

/** A desktop browser, 1280 × 800 at a device scale of 1. */
function desktop(browser) {
  return browser.newContext({ viewport: { width: 1280, height: 800 }, deviceScaleFactor: 1, ...COMMON });
}

/** A phone, 390 × 844 at a device scale of 2. */
function phone(browser) {
  return browser.newContext({
    viewport: { width: 390, height: 844 },
    deviceScaleFactor: 2,
    isMobile: true,
    hasTouch: true,
    ...COMMON,
  });
}

/** Saves a screenshot of a page or an element under IMAGES. */
async function shot(target, name, options = {}) {
  await target.screenshot({ path: path.join(IMAGES, name), ...options });
  console.log('saved', name);
}

/** Waits for the search's status line to count the results. */
async function waitResults(page) {
  await page.getByRole('status').filter({ hasText: /Showing|No flights/ }).first().waitFor();
}

/** Opens a card and waits for its price check's answer, which it returns. */
async function openCard(card) {
  await card.getByRole('button').first().click();
  const status = card.getByRole('status').filter({ hasText: ANSWER });
  await status.waitFor({ timeout: 60_000 });
  return (await status.innerText()).replace(/\n+/g, ' | ');
}

/** Signs the demo admin in on /admin and waits for the provider status. */
async function signIn(page, { onLoginPage } = {}) {
  await page.goto(`${DASHBOARD}/admin`);
  await page.waitForURL(/localhost:8180/);
  await page.locator('#username').fill('admin');
  if (onLoginPage) await onLoginPage(page);
  await page.locator('#password').fill(ADMIN_PASSWORD);
  await page.locator('#kc-login').click();
  await page.getByText(/^Generation \d+$/).waitFor({ timeout: 30_000 });
}

async function generation(page) {
  return Number((await page.getByText(/^Generation \d+$/).innerText()).split(' ')[1]);
}

/** Presses "Run ingestion now" and waits, up to six minutes, for a new generation. */
async function runIngestion(admin) {
  const before = await generation(admin);
  await admin.getByRole('button', { name: 'Run ingestion now' }).click();
  await admin.getByText('Ingestion started.').waitFor();
  const deadline = Date.now() + 6 * 60_000;
  while (Date.now() < deadline) {
    await admin.waitForTimeout(5_000);
    const now = await generation(admin).catch(() => before);
    if (now > before && !(await admin.getByText('Ingestion in progress').count())) return now;
  }
  throw new Error('The run did not publish within six minutes');
}

/** `docker compose` in COMPOSE_DIR, with its output printed. */
function compose(...args) {
  console.log('$ docker compose', args.join(' '));
  process.stdout.write(execFileSync('docker', ['compose', ...args], { cwd: COMPOSE_DIR, encoding: 'utf8' }));
}

/** Waits, up to two minutes, until compose reports mock-gds healthy. */
async function mockHealthy() {
  for (let i = 0; i < 60; i++) {
    const id = execFileSync('docker', ['compose', 'ps', '-q', 'mock-gds'], { cwd: COMPOSE_DIR, encoding: 'utf8' }).trim();
    const state = id
      ? execFileSync('docker', ['inspect', '-f', '{{.State.Health.Status}}', id], { encoding: 'utf8' }).trim()
      : 'missing';
    if (state === 'healthy') return;
    await new Promise((resolve) => setTimeout(resolve, 2_000));
  }
  throw new Error('mock-gds is not healthy after two minutes');
}

/**
 * Brings every provider back to Up: waits out the breakers' 60 s, then runs an ingestion, which
 * closes them, and checks the table.
 */
async function recoverProviders(admin) {
  await admin.waitForTimeout(70_000);
  console.log('generation (recovered)', await runIngestion(admin));
  await admin.waitForTimeout(12_000);
  const table = await admin.locator('table').innerText();
  if (/Down|Degraded|Open|Half-open/.test(table)) throw new Error(`A provider is not back:\n${table}`);
  console.log('every provider Up, every breaker Closed');
}

/** Runs a script's body and fails the process on an error. */
function main(body) {
  body().catch((error) => {
    console.error(error);
    process.exit(1);
  });
}

module.exports = {
  REPO,
  MANUAL,
  IMAGES,
  DASHBOARD,
  GRAFANA,
  API,
  COLUMN,
  launch,
  desktop,
  phone,
  shot,
  waitResults,
  openCard,
  signIn,
  generation,
  runIngestion,
  compose,
  mockHealthy,
  recoverProviders,
  main,
};
