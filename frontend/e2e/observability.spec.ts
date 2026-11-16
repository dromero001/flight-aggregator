import { randomBytes } from 'node:crypto';
import { expect, test, type APIRequestContext } from '@playwright/test';
import {
  adminToken,
  alertRules,
  dashboardPanels,
  grafanaQuery,
  lokiExploreUrl,
  lokiLabels,
  lokiLines,
  tempoTrace,
} from './telemetry.js';

// The three signals end to end (spec 6.10), read through Grafana with no login, as the team reads
// them (spec 6.6). Each read polls for up to a minute, the time telemetry may take to arrive.
test.describe.configure({ timeout: 180_000 });

/** The application's logs in Loki: their one stream label (plan 7 Task 5). */
const APP_LOGS = '{service_name="flight-aggregator"}';

/**
 * A run takes about half a minute (search-api.md), but two and a half minutes on a laptop running
 * a second stack beside this one (plan 7 Task 7's run).
 */
const RUN_PUBLISHED_WITHIN_MS = 240_000;

/** The search's server span (Spring's HTTP server observation). */
const SEARCH_SPAN = 'http get /api/itineraries';

test('shows a search as one trace', async ({ request }) => {
  // A trace the test chooses, as a caller in front of the API would send it (W3C trace context).
  const traceId = randomBytes(16).toString('hex');
  const parentSpanId = randomBytes(8).toString('hex');
  const search = await request.get('/api/itineraries?limit=1', {
    headers: { traceparent: `00-${traceId}-${parentSpanId}-01` },
  });
  expect(search.status()).toBe(200);

  const spans = await tempoTrace(traceId, SEARCH_SPAN);
  const server = spans.find((span) => span.name === SEARCH_SPAN);
  expect(server, "the search's server span").toMatchObject({
    serviceName: 'flight-aggregator',
    parentSpanId,
  });
});

test('shows a manual run as one trace with every provider request', async ({ request }) => {
  test.setTimeout(RUN_PUBLISHED_WITHIN_MS + 180_000);
  const generation = await runAnIngestion(request);

  // The run's INFO line names its trace.
  const [ended] = await lokiLines(
    `${APP_LOGS} |= "Ingestion run ended" | generation = "${String(generation)}"`,
  );
  const traceId = ended.fields.trace_id;
  expect(traceId, 'the trace id of the run line').toMatch(/^[0-9a-f]{32}$/);

  // The run's span ends after its provider requests, so the trace is read whole once it is there.
  const spans = await tempoTrace(traceId, 'ingestion.run');
  // Each provider request is a client span of the run's trace; its path names the provider.
  const providers = new Set(
    spans
      .filter((span) => span.name === 'http get')
      .map((span) => span.attributes.uri.split('/')[1]),
  );
  expect([...providers].sort()).toEqual(['alpha', 'beta', 'gamma']);
});

test('jumps from a log line to its trace in Grafana', async ({ page }) => {
  // No sign-in: the anonymous Viewer may use Explore (plan 7, Q1-A).
  await page.goto(lokiExploreUrl(`${APP_LOGS} |= "Ingestion run ended"`));
  await page.getByText('Ingestion run ended', { exact: true }).first().click();

  // The line's details link its trace_id to Tempo (the Loki datasource's derived field).
  await page.getByRole('link', { name: 'View trace' }).first().click();
  await expect(page.getByText('ingestion.run').first()).toBeVisible({ timeout: 15_000 });
});

test("keeps ids out of Loki's labels", async () => {
  // An id as a label would make a stream per run or request (plan 7 Global Constraints); the ids
  // are each line's structured metadata instead.
  const labels = await lokiLabels();
  const ids = ['trace_id', 'span_id', 'correlation_id'];
  expect(labels.filter((label) => ids.includes(label))).toEqual([]);
});

test('loads the alert rules, none firing on a healthy stack', async () => {
  // Rules evaluated on the app's gauges, not on a Prometheus that has not scraped them yet.
  await grafanaQuery('prometheus', { expr: 'ingestion_consecutive_failures', instant: true });
  const rules = await alertRules(new Date());

  // Each alert has a rule on its value and one on an unknown gauge (deploy/prometheus/rules).
  expect([...new Set(rules.map((rule) => rule.name))].sort()).toEqual([
    'IngestionFailing',
    'PartitionHorizonShort',
    'ProviderDataStale',
    'RowsInDefaultPartition',
  ]);
  expect(rules.filter((rule) => rule.health !== 'ok')).toEqual([]);
  expect(rules.filter((rule) => rule.state === 'firing')).toEqual([]);
});

test('feeds every dashboard panel', async ({ request }) => {
  // A miss, then a hit of the same search, for the cache hit ratio; the stack's runs feed the rest.
  for (let search = 0; search < 2; search++) {
    expect((await request.get('/api/itineraries?limit=1&sort=duration')).status()).toBe(200);
  }

  const panels = await dashboardPanels('flight-aggregator');
  expect(panels.length, 'panels of the dashboard').toBeGreaterThan(0);
  // Every query of every panel at once, so a minute's wait covers them all.
  const queries = panels.flatMap((panel) =>
    panel.targets.map((target) => ({
      name: `${panel.title}/${target.refId}`,
      answer: grafanaQuery(panel.datasource.uid, target),
    })),
  );
  const answers = await Promise.allSettled(queries.map(({ answer }) => answer));
  const withoutData = answers.flatMap((answer, index) =>
    answer.status === 'rejected' ? [`${queries[index].name}: ${String(answer.reason)}`] : [],
  );
  expect(withoutData).toEqual([]);
});

/**
 * Starts a run with the admin's token, waiting out one already going on (409), and waits until it
 * publishes. Answers the generation it published.
 */
async function runAnIngestion(request: APIRequestContext): Promise<number> {
  const headers = { Authorization: `Bearer ${await adminToken()}` };
  await expect
    .poll(async () => (await request.post('/api/admin/ingestions', { headers })).status(), {
      message: 'a run started (202)',
      intervals: [5_000],
      timeout: RUN_PUBLISHED_WITHIN_MS,
    })
    .toBe(202);

  // The run holds the ingestion's lock, so the next generation is this run's.
  const generation = async () => {
    const status = await request.get('/api/admin/status', { headers });
    expect(status.status()).toBe(200);
    return ((await status.json()) as { generation: number }).generation;
  };
  const published = (await generation()) + 1;
  await expect
    .poll(generation, { message: 'the run published', timeout: RUN_PUBLISHED_WITHIN_MS })
    .toBe(published);
  return published;
}
