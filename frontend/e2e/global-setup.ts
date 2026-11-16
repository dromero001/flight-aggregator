import type { FullConfig } from '@playwright/test';

const POLL_INTERVAL_MS = 5_000;
const TIMEOUT_MS = 180_000;

// The search answers 200 with no itinerary until the first ingestion has published a generation.
// Poll every 5 s, so the wait spends little of the per-IP search limit, until one itinerary shows up.
export default async function globalSetup(config: FullConfig): Promise<void> {
  const baseURL = config.projects[0].use.baseURL;
  const deadline = Date.now() + TIMEOUT_MS;
  let last = 'no answer';

  while (Date.now() < deadline) {
    try {
      // A hung connection (nginx up, the app stalled) must not outlast the deadline.
      const response = await fetch(String(baseURL) + '/api/itineraries', {
        signal: AbortSignal.timeout(POLL_INTERVAL_MS),
      });
      last = 'HTTP ' + String(response.status);
      if (response.ok) {
        const page = (await response.json()) as { itineraries?: unknown[] };
        if (page.itineraries && page.itineraries.length > 0) return;
        last = 'HTTP 200 with no itinerary';
      }
    } catch (error) {
      last = String(error);
    }
    await new Promise((resolve) => setTimeout(resolve, POLL_INTERVAL_MS));
  }
  throw new Error(
    `No itinerary after ${String(TIMEOUT_MS / 1000)} s (last answer: ${last}). Is the first ingestion done?`,
  );
}
