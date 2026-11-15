import { expect, test } from '@playwright/test';

test('serves the dashboard and proxies the API', async ({ page, request }) => {
  await page.goto('/');
  await expect(page.getByRole('heading', { level: 1, name: 'Find flights' })).toBeVisible();

  // /admin is a route of the app, not a file: a plain GET answers index.html.
  const index = await request.get('/');
  const admin = await request.get('/admin');
  expect(admin.status()).toBe(200);
  expect(await admin.text()).toBe(await index.text());

  // The settings of the admin screen, rendered from the container's environment.
  const config = await request.get('/config.json');
  expect(config.headers()['content-type']).toContain('application/json');
  expect(await config.json()).toMatchObject({ oidcClientId: 'dashboard' });

  // The API answers on the same origin: a page of itineraries.
  const itineraries = await request.get('/api/itineraries');
  expect(itineraries.status()).toBe(200);
  expect(itineraries.headers()['content-type']).toContain('application/json');
  const body = (await itineraries.json()) as { itineraries: unknown[] };
  expect(body.itineraries.length).toBeGreaterThan(0);
});
