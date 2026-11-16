import { expect, test, type Page } from '@playwright/test';
import { expectEveryCardFrom } from './cards.js';

/** The cards' prices, in the order shown ("€1,234.50" read as 1234.5, for comparison only). */
async function shownPrices(page: Page): Promise<number[]> {
  const texts = await page.getByRole('article').allInnerTexts();
  return texts.map((text) => {
    const amount = /€([\d,]+\.\d{2})/.exec(text);
    if (!amount) throw new Error(`No price on the card: ${text}`);
    return Number(amount[1].replaceAll(',', ''));
  });
}

function isNonDecreasing(values: number[]): boolean {
  return values.every((value, index) => index === 0 || values[index - 1] <= value);
}

/** Each card's departure and arrival instants, read from its first two `<time dateTime>`. */
async function shownTimes(page: Page): Promise<{ departure: number; arrival: number }[]> {
  const cards = await page.getByRole('article').all();
  return Promise.all(
    cards.map(async (card) => {
      const instant = async (index: number) =>
        Date.parse((await card.locator('time').nth(index).getAttribute('datetime')) ?? '');
      return { departure: await instant(0), arrival: await instant(1) };
    }),
  );
}

/** The API's page size when no `limit` is sent, which the dashboard never sends. */
const PAGE_SIZE = 20;

/** Polls until a first page is on screen in `order` (`expectEveryCardFrom` says why it polls). */
async function expectSortedBy(
  page: Page,
  order: string,
  key: (times: { departure: number; arrival: number }) => number,
): Promise<void> {
  await expect
    .poll(
      async () => {
        const values = (await shownTimes(page)).map(key);
        return values.length === PAGE_SIZE && isNonDecreasing(values);
      },
      { message: `a first page in ${order} order` },
    )
    .toBe(true);
}

test('shows the cheapest flights first and loads 20 more', async ({ page }) => {
  await page.goto('/');
  await expect(page.getByText('Showing 20 flights')).toBeVisible();

  await page.getByRole('button', { name: 'Load more' }).click();

  await expect(page.getByText('Showing 40 flights')).toBeVisible();
  await expect(page.getByRole('article')).toHaveCount(2 * PAGE_SIZE);
  const prices = await shownPrices(page);
  expect(isNonDecreasing(prices), `prices in the order shown: ${prices.join(', ')}`).toBe(true);
});

test('filters by origin and keeps the search in the URL', async ({ page }) => {
  await page.goto('/');

  await page.getByLabel('From').fill('mad');
  await page.getByRole('button', { name: 'Search' }).click();

  await expect(page).toHaveURL(/\/\?origin=MAD$/);
  await expectEveryCardFrom(page, 'MAD');

  // A reload reads the search back from the URL.
  await page.reload();
  await expect(page.getByLabel('From')).toHaveValue('MAD');
  await expectEveryCardFrom(page, 'MAD');

  // Back returns to the search before it, and forward to this one again.
  await page.goBack();
  await expect(page).toHaveURL(/\/$/);
  await expect(page.getByLabel('From')).toHaveValue('');
  await page.goForward();
  await expect(page.getByLabel('From')).toHaveValue('MAD');
  await expectEveryCardFrom(page, 'MAD');
});

test('sorts by departure and by duration', async ({ page }) => {
  await page.goto('/');

  await page.getByRole('radio', { name: 'Departure' }).click();
  await expect(page).toHaveURL(/\/\?sort=departure$/);
  await expectSortedBy(page, 'departure', (times) => times.departure);

  // The duration is the time between the two instants, whatever the airports' zones.
  await page.getByRole('radio', { name: 'Duration' }).click();
  await expect(page).toHaveURL(/\/\?sort=duration$/);
  await expectSortedBy(page, 'duration', (times) => times.arrival - times.departure);
});

test('stacks the filters on a phone', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto('/');

  // Each control starts below the one before it, at the same left edge.
  const controls = [
    page.getByLabel('From'),
    page.getByLabel('To', { exact: true }),
    page.getByLabel('Airline'),
    page.getByLabel('Max price'),
    page.getByRole('button', { name: 'Search' }),
  ];
  const boxes = await Promise.all(
    controls.map(async (control) => {
      const box = await control.boundingBox();
      if (!box) throw new Error(`${control.toString()} is not laid out`);
      return box;
    }),
  );
  for (const [index, box] of boxes.entries()) {
    if (index === 0) continue;
    const above = boxes[index - 1];
    const control = controls[index].toString();
    expect(box.y, `${control} is below the control before`).toBeGreaterThanOrEqual(
      above.y + above.height,
    );
    expect(box.x, `${control} is at the same left edge`).toBe(above.x);
  }
});
