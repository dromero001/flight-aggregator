import { expect, test } from '@playwright/test';
import { REPRICE_ANSWERED_WITHIN_MS } from './cards.js';

/** The three answers of a re-price the providers confirmed (spec 6.5; plan 6, Q5-A). */
const CONFIRMED_OUTCOME =
  /Price confirmed: €|Price changed: |We couldn't confirm this flight\. This is the best alternative we found\./;

test('opens a card and shows a confirmed price, a new price or an alternative', async ({
  page,
}) => {
  await page.goto('/');
  const card = page.getByRole('article').first();
  const summary = card.getByRole('button').first();

  await summary.click();

  await expect(summary).toHaveAttribute('aria-expanded', 'true');
  await expect(card.getByRole('list', { name: 'Legs' })).toBeVisible();
  // Whichever the providers answer today, the price shown is one they have just confirmed.
  await expect(card.getByRole('status')).toContainText(CONFIRMED_OUTCOME, {
    timeout: REPRICE_ANSWERED_WITHIN_MS,
  });
  await expect(card.getByRole('button', { name: 'Select' })).toBeVisible();
});
