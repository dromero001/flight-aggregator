// The providers unreachable: stops and starts the mock-gds service (nothing else), runs two
// ingestions, and leaves every provider Up again. Takes about five minutes.
const { DASHBOARD, COLUMN, launch, desktop, shot, waitResults, openCard, signIn, runIngestion, compose, mockHealthy, recoverProviders, main } =
  require('./lib.cjs');

main(async () => {
  const browser = await launch();
  const admin = await (await desktop(browser)).newPage();
  await signIn(admin);
  const customer = await (await desktop(browser)).newPage();
  await customer.goto(`${DASHBOARD}/?origin=MAD&destination=JFK&carrier=IB&maxPrice=700`);
  await waitResults(customer);

  let failure;
  try {
    // No provider answers: the price check cannot confirm anything (503), and the breakers open.
    compose('stop', 'mock-gds');
    const card = customer.locator('article').nth(2);
    console.log('price check', await openCard(card));
    const openedAt = Date.now();
    await shot(card, 'reprice-unavailable.png');
    await admin.getByText('Open', { exact: true }).first().waitFor({ timeout: 30_000 });
    await admin.waitForTimeout(500);
    await shot(admin, 'admin-breakers-open.png', { clip: COLUMN });

    // The providers back; after the breakers' 60 s, a price check lets a trial call through the
    // breaker of the provider it asks. A card may ask a provider whose breaker never opened, so
    // cards are opened until one breaker shows Half-open.
    compose('start', 'mock-gds');
    await mockHealthy();
    const wait = openedAt + 70_000 - Date.now();
    if (wait > 0) await customer.waitForTimeout(wait);
    const halfOpen = admin.getByText('Half-open', { exact: true }).first();
    for (let i = 3; i < 9 && !(await halfOpen.isVisible()); i++) {
      console.log('price check', await openCard(customer.locator('article').nth(i)));
      await halfOpen.waitFor({ timeout: 12_000 }).catch(() => {});
    }
    await halfOpen.waitFor({ timeout: 1_000 });
    await admin.waitForTimeout(500);
    await shot(admin, 'admin-provider-degraded.png', { clip: COLUMN });

    // A run while the providers are unreachable keeps their last good legs.
    compose('stop', 'mock-gds');
    console.log('generation (providers down)', await runIngestion(admin));
    await admin.getByText('Kept from the last good ingestion').first().waitFor({ timeout: 30_000 });
    await admin.waitForTimeout(500);
    await shot(admin, 'admin-providers-kept.png', { clip: COLUMN });
  } catch (error) {
    failure = error;
  } finally {
    compose('start', 'mock-gds');
    await mockHealthy();
  }
  // Whatever happened above, the providers end Up.
  await recoverProviders(admin);
  await browser.close();
  if (failure) throw failure;
});
