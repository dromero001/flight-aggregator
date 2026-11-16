import { expect, type Page } from '@playwright/test';

// The dataset rolls with the clock, so the specs name no route or price: they read what the
// result cards show.

/**
 * How long an opened card may take to show its re-price: no quote starts after 10 s, and one
 * already started is bounded by its provider's timeouts (search-api.md, "Re-price on selection").
 */
export const REPRICE_ANSWERED_WITHIN_MS = 15_000;

/**
 * The airport each card leaves from: the code after its departure time. A card being replaced
 * may give its text without the line breaks of its layout, so none is required between them.
 */
async function shownOrigins(page: Page): Promise<string[]> {
  const texts = await page.getByRole('article').allInnerTexts();
  return texts.map((text) => {
    const origin = /^\d{2}:\d{2}\s*([A-Z]{3})/.exec(text);
    if (!origin) throw new Error(`No origin on the card: ${text}`);
    return origin[1];
  });
}

/**
 * Polls until every card leaves `airport`. A new search changes the URL a moment before the list
 * re-renders, and until then the cards on screen, and the "Showing N flights" above them, are
 * the previous search's.
 */
export async function expectEveryCardFrom(page: Page, airport: string): Promise<void> {
  await expect
    .poll(async () => [...new Set(await shownOrigins(page))], {
      message: `every card should leave ${airport}`,
    })
    .toEqual([airport]);
}
