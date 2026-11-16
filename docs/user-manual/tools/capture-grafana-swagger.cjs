// Grafana, as the anonymous Viewer, and Swagger UI. Reads only, plus one search from Swagger UI.
// Grafana needs at least one ingestion run in the last hour for the log-to-trace shots.
const { GRAFANA, API, launch, desktop, shot, main } = require('./lib.cjs');

/** Explore with one query on a provisioned datasource, whose uid is its type's name. */
function exploreUrl(datasource, query) {
  const panes = {
    pane: {
      datasource,
      queries: [{ refId: 'A', ...query, datasource: { type: datasource, uid: datasource } }],
      range: { from: 'now-1h', to: 'now' },
    },
  };
  return `${GRAFANA}/explore?schemaVersion=1&orgId=1&panes=${encodeURIComponent(JSON.stringify(panes))}`;
}

main(async () => {
  const browser = await launch();
  const page = await (await desktop(browser)).newPage();
  const closeNavigation = async () => {
    const close = page.getByRole('button', { name: 'Close menu' });
    if (await close.count()) await close.click();
  };
  const pointerAway = () => page.mouse.move(5, 795);

  // The dashboard.
  await page.goto(`${GRAFANA}/d/flight-aggregator?orgId=1&from=now-3h&to=now`);
  await page.getByText('Provider calls').first().waitFor({ timeout: 30_000 });
  await closeNavigation();
  await page.waitForTimeout(6_000);
  await shot(page, 'grafana-dashboard.png');

  // One panel on its own, through its menu's View.
  await page.getByText('Ingestion', { exact: true }).first().hover();
  await page.locator('button[aria-label="Menu for panel Ingestion"]').click();
  await page.getByRole('menuitem', { name: /View/ }).first().click();
  await page.getByRole('button', { name: 'Back to dashboard' }).waitFor();
  await pointerAway();
  await page.waitForTimeout(4_000);
  await shot(page, 'grafana-panel-ingestion.png');

  // A run's log line, then its trace.
  await page.goto(exploreUrl('loki', { expr: '{service_name="flight-aggregator"} |= "Ingestion run ended"' }));
  await page.getByText('Ingestion run ended', { exact: true }).first().waitFor({ timeout: 30_000 });
  await closeNavigation();
  await page.waitForTimeout(1_500);
  await page.getByText('Ingestion run ended', { exact: true }).first().click();
  const viewTrace = page.getByRole('link', { name: 'View trace' }).first();
  await viewTrace.waitFor();
  await viewTrace.scrollIntoViewIfNeeded();
  await pointerAway();
  await page.waitForTimeout(1_000);
  await shot(page, 'grafana-log-line.png');
  await viewTrace.click();
  await page.getByText('ingestion.run').first().waitFor({ timeout: 20_000 });
  await pointerAway();
  await page.waitForTimeout(3_000);
  await shot(page, 'grafana-log-to-trace.png');

  // Swagger UI: the overview, a search tried, and the empty Authorize dialog (never a token).
  await page.goto(`${API}/swagger-ui.html`);
  await page.getByText('Flight aggregator API').first().waitFor({ timeout: 30_000 });
  await page.waitForTimeout(1_000);
  await shot(page, 'swagger-overview.png');

  const search = page.locator('.opblock-get').filter({ hasText: '/api/itineraries' }).first();
  await search.locator('.opblock-summary').click();
  await search.getByRole('button', { name: 'Try it out' }).click();
  await search.locator('input[placeholder="origin"]').fill('MAD');
  await search.locator('input[placeholder="destination"]').fill('JFK');
  await search.locator('input[placeholder="limit"]').fill('2');
  await search.getByRole('button', { name: 'Execute' }).click();
  const live = search.locator('.responses-wrapper .live-responses-table');
  await live.waitFor({ timeout: 20_000 });
  await page.waitForTimeout(800);
  // From the operation's header to the end of the response's headers.
  const top = await search.boundingBox();
  const bottom = await live.boundingBox();
  const scrollY = await page.evaluate(() => window.scrollY);
  await shot(page, 'swagger-search-tried.png', {
    fullPage: true,
    clip: { x: top.x, y: top.y + scrollY, width: top.width, height: bottom.y + bottom.height - top.y },
  });

  await page.evaluate(() => window.scrollTo(0, 0));
  await page.getByRole('button', { name: /Authorize/ }).first().click();
  await page.locator('.modal-ux').waitFor();
  await page.waitForTimeout(500);
  await shot(page.locator('.modal-ux'), 'swagger-authorize.png');
  await browser.close();
});
