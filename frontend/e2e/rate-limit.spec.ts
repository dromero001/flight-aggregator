import { expect, test } from '@playwright/test';

/** Searches a minute per client address (search-api.md, "Rate limit"). */
const SEARCHES_A_MINUTE = 60;

/**
 * Fixed windows of a minute: sent this fast, the searches straddle at most two windows, so one
 * more than two windows' worth must meet a 429 whatever the earlier specs spent.
 */
const ATTEMPTS = 2 * SEARCHES_A_MINUTE + 1;

test('counts a client that forges X-Forwarded-For under its own address', async ({ request }) => {
  let refusedAt: number | undefined;
  for (let attempt = 1; attempt <= ATTEMPTS && refusedAt === undefined; attempt++) {
    // A new documentation address (RFC 5737) per search: were it believed, no counter would fill.
    const response = await request.get('/api/itineraries?limit=1', {
      headers: { 'X-Forwarded-For': `198.51.100.${String(attempt)}` },
    });
    if (response.status() === 429) {
      refusedAt = attempt;
      expect(Number(response.headers()['retry-after'])).toBeGreaterThan(0);
    } else {
      expect(response.status(), `search ${String(attempt)}`).toBe(200);
    }
  }
  expect(refusedAt, `a 429 within ${String(ATTEMPTS)} searches`).toBeDefined();
});
