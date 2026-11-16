// The price check's alternative answer: recreates mock-gds with Beta failing every request, opens
// cards until one answers with an alternative, then recreates mock-gds from compose.yaml alone and
// runs an ingestion so that every provider is Up again. Only mock-gds is recreated, never rebuilt.
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');
const { DASHBOARD, launch, desktop, shot, waitResults, openCard, signIn, compose, mockHealthy, recoverProviders, main } = require('./lib.cjs');

/** Cards to try at most; the price checks are limited to 10 a minute per address. */
const ATTEMPTS = 40;

main(async () => {
  const override = path.join(fs.mkdtempSync(path.join(os.tmpdir(), 'manual-')), 'beta-failing.yaml');
  fs.writeFileSync(override, 'services:\n  mock-gds:\n    environment:\n      MOCKGDS_PROVIDERS_BETA_ERRORRATE: "1"\n');
  const browser = await launch();
  const admin = await (await desktop(browser)).newPage();
  await signIn(admin);
  const page = await (await desktop(browser)).newPage();
  let found = false;
  let failure;

  try {
    compose('-f', 'compose.yaml', '-f', override, 'up', '-d', '--no-deps', '--no-build', 'mock-gds');
    await mockHealthy();
    // Direct flights: one leg, so a card whose only seller is Beta has nothing else to confirm it.
    await page.goto(`${DASHBOARD}/?origin=MAD&destination=JFK&sort=departure`);
    await waitResults(page);
    for (let i = 0; i < ATTEMPTS && !found; i++) {
      const card = page.locator('article').nth(i);
      if (!(await card.count())) {
        await page.getByRole('button', { name: 'Load more' }).click();
        await card.waitFor();
      }
      const answer = await openCard(card);
      console.log(i, answer);
      if (answer.includes('Too many price checks')) {
        // The minute's checks are spent: wait for the next window.
        await page.waitForTimeout(61_000);
      } else if (answer.includes("We couldn't confirm this flight")) {
        found = true;
        await card.scrollIntoViewIfNeeded();
        await shot(card, 'reprice-alternative.png');
      }
    }
  } catch (error) {
    failure = error;
  } finally {
    compose('-f', 'compose.yaml', 'up', '-d', '--no-deps', '--no-build', 'mock-gds');
    await mockHealthy();
    fs.rmSync(path.dirname(override), { recursive: true, force: true });
  }
  // Whatever happened above, the providers end Up.
  await recoverProviders(admin);
  await browser.close();
  if (failure) throw failure;
  if (!found) throw new Error(`No alternative in ${ATTEMPTS} cards`);
});
