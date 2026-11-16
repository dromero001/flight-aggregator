import { expect, test, type Page } from '@playwright/test';
import { expectNoAxeViolations } from './axe.js';

// The realm's demo admin (README, "Admin access"; deploy/keycloak/flight-aggregator-realm.json),
// whose password compose takes from ADMIN_USER_PASSWORD, with the same demo default.
const ADMIN_USERNAME = 'admin';
const ADMIN_PASSWORD = process.env.ADMIN_USER_PASSWORD ?? 'demo-admin-password';

/** Opens /admin and signs in on Keycloak's own page, which sends the browser back. */
async function signIn(page: Page): Promise<void> {
  await page.goto('/admin');
  await page.getByLabel('Username or email').fill(ADMIN_USERNAME);
  await page.getByLabel('Password', { exact: true }).fill(ADMIN_PASSWORD);
  await page.getByRole('button', { name: 'Sign In' }).click();
  await expect(page.getByRole('heading', { level: 1, name: 'Provider status' })).toBeVisible();
}

test('signs in through Keycloak and shows each provider', async ({ page }) => {
  await signIn(page);

  // Back on the dashboard, with the code exchanged and the URL clean.
  await expect(page).toHaveURL(/\/admin$/);
  const providers = page.getByRole('table', { name: 'Providers' });
  const names = providers.getByRole('rowheader');
  await expect(names).toHaveCount(3);
  expect((await names.allInnerTexts()).sort()).toEqual(['Alpha', 'Beta', 'Gamma']);
  await expectNoAxeViolations(page);
});

test('fits the provider status on a phone', async ({ page }) => {
  // 390 × 844, as the search's phone test: the page must not scroll sideways.
  await page.setViewportSize({ width: 390, height: 844 });
  await signIn(page);
  const table = page.getByRole('table', { name: 'Providers' });
  await expect(table).toBeVisible();

  // The widest thing on the screen.
  const box = await table.boundingBox();
  expect(box && box.x + box.width, 'the table ends within the phone').toBeLessThanOrEqual(390);
});

/** A run takes about half a minute (search-api.md); the screen reads the status every 10 s. */
const RUN_PUBLISHED_WITHIN_MS = 90_000;

test('runs an ingestion on demand', async ({ page }) => {
  test.setTimeout(RUN_PUBLISHED_WITHIN_MS + 30_000);
  await signIn(page);
  const generationLine = page.getByText(/^Generation \d+$/);
  const generation = async () => Number(/\d+/.exec(await generationLine.innerText())?.[0]);
  const before = await generation();

  // The ingestion's lock is held only while a run is going on (plan 5), and the first run has
  // published before any test starts (global-setup.ts), so the first press starts one.
  await page.getByRole('button', { name: 'Run ingestion now' }).click();
  await expect(page.getByText('Ingestion started.')).toBeVisible();

  await expect
    .poll(generation, {
      message: `a generation after ${String(before)}`,
      timeout: RUN_PUBLISHED_WITHIN_MS,
    })
    .toBeGreaterThan(before);
});
