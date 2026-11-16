// The customer's search on a healthy stack: nothing is stopped or changed.
// Spends about ten searches and seven price checks of this address's minute.
const { DASHBOARD, COLUMN, launch, desktop, phone, shot, waitResults, openCard, main } = require('./lib.cjs');

const MAD_JFK = `${DASHBOARD}/?origin=MAD&destination=JFK&carrier=IB&maxPrice=700`;

main(async () => {
  const browser = await launch();
  const page = await (await desktop(browser)).newPage();

  // The page on arrival: cheapest flights first, no filter.
  await page.goto(`${DASHBOARD}/`);
  await waitResults(page);
  await shot(page, 'search-home.png', { clip: COLUMN });

  // The four filters typed in, then Search.
  await page.getByRole('combobox', { name: 'From' }).fill('MAD');
  await page.getByRole('combobox', { name: 'To' }).fill('JFK');
  await page.getByRole('combobox', { name: 'Airline' }).fill('IB');
  await page.getByRole('textbox', { name: 'Max price' }).fill('700');
  await page.getByRole('button', { name: 'Search' }).click();
  await page.waitForURL(/origin=MAD/);
  await waitResults(page);
  await page.waitForTimeout(300);
  await shot(page, 'search-filters.png', { clip: COLUMN });

  // Sorted by departure.
  await page.getByText('Departure', { exact: true }).click();
  await page.waitForURL(/sort=departure/);
  await waitResults(page);
  await page.waitForTimeout(300);
  await shot(page, 'search-sorted-by-departure.png', { clip: COLUMN });

  // "Load more" under the first 20.
  const more = page.getByRole('button', { name: 'Load more' });
  await more.scrollIntoViewIfNeeded();
  await page.mouse.wheel(0, 200);
  await page.waitForTimeout(300);
  await shot(page, 'search-load-more.png', { clip: COLUMN });

  // A field error: a partial city name.
  await page.goto(`${DASHBOARD}/`);
  await waitResults(page);
  await page.getByRole('combobox', { name: 'From' }).fill('Madri');
  await page.getByRole('button', { name: 'Search' }).click();
  await page.getByText('Enter a 3-letter airport code, like MAD').waitFor();
  await shot(page, 'search-field-error.png', { clip: { x: 0, y: 60, width: 900, height: 300 } });

  // No match.
  await page.goto(`${DASHBOARD}/?origin=MAD&destination=JFK&maxPrice=1`);
  await waitResults(page);
  await shot(page, 'search-no-flights.png', { clip: { x: 0, y: 60, width: 900, height: 330 } });

  // An itinerary with two stops, opened, then "Select".
  await page.goto(`${DASHBOARD}/?origin=EDI&destination=BOG`);
  await waitResults(page);
  const card = page.locator('article').first();
  console.log('EDI-BOG:', await openCard(card));
  await shot(card, 'itinerary-with-stops.png');
  const select = card.getByRole('button', { name: 'Select' });
  if (await select.count()) {
    await select.click();
    await card.getByText('Booking is not part of this demo.').waitFor();
    await shot(card, 'itinerary-select.png');
  }

  // Price checks on several cards: the first changed price and the first confirmed one.
  await page.goto(MAD_JFK);
  await waitResults(page);
  const seen = new Set();
  for (let i = 0; i < 6 && seen.size < 2; i++) {
    const c = page.locator('article').nth(i);
    const answer = await openCard(c);
    console.log(i, answer);
    const kind = answer.includes('Price changed') ? 'changed' : answer.includes('Price confirmed') ? 'confirmed' : '';
    if (kind && !seen.has(kind)) {
      seen.add(kind);
      await shot(c, kind === 'changed' ? 'reprice-price-changed.png' : 'reprice-confirmed.png');
    }
    await c.getByRole('button').first().click();
  }

  // The search on a phone.
  const p = await (await phone(browser)).newPage();
  await p.goto(MAD_JFK);
  await waitResults(p);
  await shot(p, 'search-phone.png');
  await browser.close();
});
