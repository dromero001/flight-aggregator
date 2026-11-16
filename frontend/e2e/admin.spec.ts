import { expect, test, type Page } from '@playwright/test';
import { expectNoAxeViolations } from './axe.js';

// The realm's demo admin (README, "Admin access"; deploy/keycloak/flight-aggregator-realm.json).
const ADMIN_USERNAME = 'admin';
const ADMIN_PASSWORD = 'demo-admin-password';

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

/**
 * A scheduled run keeps the ingestion's lock for at least a minute from its start (plan 5,
 * IngestionJob), so on a stack just started the app's first run may still refuse a run on demand.
 */
const LOCK_FREE_WITHIN_MS = 70_000;

/** A run takes about half a minute (search-api.md); the screen reads the status every 10 s. */
const RUN_PUBLISHED_WITHIN_MS = 90_000;

test('runs an ingestion on demand', async ({ page }) => {
  test.setTimeout(LOCK_FREE_WITHIN_MS + RUN_PUBLISHED_WITHIN_MS + 30_000);
  await signIn(page);
  const generationLine = page.getByText(/^Generation \d+$/);
  const generation = async () => Number(/\d+/.exec(await generationLine.innerText())?.[0]);
  const before = await generation();

  // Pressed again every 10 s while the answer is "An ingestion is already running."
  await expect(async () => {
    await page.getByRole('button', { name: 'Run ingestion now' }).click();
    await expect(page.getByText('Ingestion started.')).toBeVisible({ timeout: 2_000 });
  }).toPass({ intervals: [10_000], timeout: LOCK_FREE_WITHIN_MS });

  await expect
    .poll(generation, {
      message: `a generation after ${String(before)}`,
      timeout: RUN_PUBLISHED_WITHIN_MS,
    })
    .toBeGreaterThan(before);
});
