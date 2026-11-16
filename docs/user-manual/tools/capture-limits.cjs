// An opened card on a phone, then the search's rate limit: spends this address's 60 searches of
// the minute, so the dashboard answers 429 for up to a minute afterwards.
const { DASHBOARD, launch, desktop, phone, shot, waitResults, openCard, main } = require('./lib.cjs');

main(async () => {
  const browser = await launch();

  const p = await (await phone(browser)).newPage();
  await p.goto(`${DASHBOARD}/?origin=EDI&destination=BOG`);
  await waitResults(p);
  const card = p.locator('article').first();
  console.log('EDI-BOG:', await openCard(card));
  await shot(card, 'itinerary-phone.png');

  const page = await (await desktop(browser)).newPage();
  for (let i = 0; i < 65; i++) await page.request.get(`${DASHBOARD}/api/itineraries?limit=1&sort=duration`);
  await page.goto(`${DASHBOARD}/?origin=MAD&destination=JFK`);
  const message = page.getByText(/Too many searches from your connection/);
  await message.waitFor({ timeout: 15_000 });
  console.log(await message.innerText());
  await shot(page, 'search-rate-limit.png', { clip: { x: 0, y: 60, width: 900, height: 320 } });
  await browser.close();
});
