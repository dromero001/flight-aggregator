// The admin's sign-in, provider status and a run on demand, and the customer's "Results updated."
// Starts one ingestion run.
const { DASHBOARD, COLUMN, launch, desktop, shot, waitResults, openCard, signIn, runIngestion, main } = require('./lib.cjs');

const TOP = { x: 0, y: 0, width: 900, height: 420 };

main(async () => {
  const browser = await launch();

  // The customer's search, opened before the run: page 1 of the old inventory.
  const customer = await (await desktop(browser)).newPage();
  await customer.goto(`${DASHBOARD}/?origin=MAD&destination=JFK&carrier=IB&maxPrice=700`);
  await waitResults(customer);

  // A card opened twice: the second answer is usually a confirmed price.
  const card = customer.locator('article').nth(1);
  for (let attempt = 0; attempt < 2; attempt++) {
    const answer = await openCard(card);
    console.log('price check', attempt, answer);
    if (answer.includes('Price confirmed')) {
      await shot(card, 'reprice-confirmed.png');
      break;
    }
    await card.getByRole('button').first().click();
    await customer.waitForTimeout(1_500);
  }

  // Keycloak's form, cropped to it, with the username only; then the status.
  const admin = await (await desktop(browser)).newPage();
  await signIn(admin, {
    onLoginPage: (page) => shot(page, 'admin-sign-in.png', { clip: { x: 340, y: 190, width: 600, height: 490 } }),
  });
  await admin.waitForTimeout(500);
  await shot(admin, 'admin-provider-status.png', { clip: COLUMN });

  // Run ingestion now: started and in progress, then a second press refused.
  const run = runIngestion(admin);
  await admin.getByText('Ingestion in progress').waitFor({ timeout: 20_000 });
  await shot(admin, 'admin-ingestion-in-progress.png', { clip: TOP });
  await admin.getByRole('button', { name: 'Run ingestion now' }).click();
  await admin.getByText('An ingestion is already running.').waitFor({ timeout: 10_000 });
  await shot(admin, 'admin-ingestion-already-running.png', { clip: TOP });
  console.log('generation after the run', await run);

  // The customer's next page comes from the new inventory.
  await customer.getByRole('button', { name: 'Load more' }).click();
  await customer.getByText('Results updated.').waitFor({ timeout: 30_000 });
  await customer.evaluate(() => window.scrollTo(0, 0));
  await customer.waitForTimeout(300);
  await shot(customer, 'search-results-updated.png', { clip: { x: 0, y: 60, width: 900, height: 420 } });
  await browser.close();
});
