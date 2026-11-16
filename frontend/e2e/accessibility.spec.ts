import { expect, test, type Locator, type Page } from '@playwright/test';
import { expectNoAxeViolations } from './axe.js';
import { expectEveryCardFrom, REPRICE_ANSWERED_WITHIN_MS } from './cards.js';

/** More than the controls before "Load more": four filters, Search, the sort, 20 cards, Select. */
const MAX_TAB_PRESSES = 40;

/**
 * The key that moves to the next control. Safari on macOS gives Tab to text fields only by default
 * and Option+Tab to every control, so that is the key its users press (plan 6 E6).
 */
function nextControlKey(browserName: string): string {
  return browserName === 'webkit' && process.platform === 'darwin' ? 'Alt+Tab' : 'Tab';
}

/** Presses `key` until `target` has the focus, failing if it never gets it. */
async function tabTo(page: Page, key: string, target: Locator): Promise<void> {
  for (let presses = 0; presses < MAX_TAB_PRESSES; presses++) {
    if ((await target.and(page.locator(':focus')).count()) > 0) return;
    await page.keyboard.press(key);
  }
  throw new Error(`${key} never reached ${target.toString()}`);
}

test('has no axe violations', async ({ page }) => {
  // The results.
  await page.goto('/');
  await expect(page.getByText(/^Showing \d+ flights?$/)).toBeVisible();
  await expectNoAxeViolations(page);

  // A card opened, with the outcome of its re-price.
  const card = page.getByRole('article').first();
  await card.getByRole('button').first().click();
  await expect(card.getByRole('button', { name: 'Select' })).toBeVisible({
    timeout: REPRICE_ANSWERED_WITHIN_MS,
  });
  await expectNoAxeViolations(page);

  // The empty state: no fare is that low.
  await page.goto('/?maxPrice=0.01');
  await expect(page.getByText(/^No flights match these filters\./)).toBeVisible();
  await expectNoAxeViolations(page);

  // A field error, from a hand-edited URL.
  await page.goto('/?origin=MADR');
  await expect(page.getByText('Enter a 3-letter airport code, like MAD')).toBeVisible();
  await expectNoAxeViolations(page);
});

test('searches, loads more and opens a card with the keyboard only', async ({
  page,
  browserName,
}) => {
  const next = nextControlKey(browserName);
  await page.goto('/');

  // Search: the first control is "From", and Enter submits the filters.
  await tabTo(page, next, page.getByLabel('From'));
  await page.keyboard.type('MAD');
  await page.keyboard.press('Enter');
  await expect(page).toHaveURL(/\/\?origin=MAD$/);
  await expectEveryCardFrom(page, 'MAD');

  // Open the first card: it re-prices and offers "Select".
  const card = page.getByRole('article').first();
  const summary = card.getByRole('button').first();
  await tabTo(page, next, summary);
  await page.keyboard.press('Enter');
  await expect(summary).toHaveAttribute('aria-expanded', 'true');
  await expect(card.getByRole('button', { name: 'Select' })).toBeVisible({
    timeout: REPRICE_ANSWERED_WITHIN_MS,
  });

  // Load more, past the opened card and the other 19. On a stack up for over 10 minutes, the MAD
  // search may start a refresh that publishes before this page is read (search-api.md, "The
  // stale-route refresh"): the page is then flagged and may append fewer than 20 (Q4-A), so the
  // test asks only that cards were appended.
  await tabTo(page, next, page.getByRole('button', { name: 'Load more' }));
  await page.keyboard.press('Enter');
  await expect.poll(() => page.getByRole('article').count()).toBeGreaterThan(20);
});
