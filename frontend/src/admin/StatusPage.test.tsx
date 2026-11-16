import { act, screen, within } from '@testing-library/react';
import { delay, http, HttpResponse } from 'msw';
import { afterEach, expect, test, vi } from 'vitest';
import type { StatusReport } from '../api/types';
import { axe } from '../test/axe';
import { providerStatus, statusReport } from '../test/fixtures';
import { renderAsAdmin } from '../test/renderAsAdmin';
import { server } from '../test/server';
import { StatusPage } from './StatusPage';

const STATUS = '*/api/admin/status';

function serveStatus(report: StatusReport) {
  server.use(http.get(STATUS, () => HttpResponse.json(report)));
}

afterEach(() => {
  vi.useRealTimers();
});

test('has no accessibility violations with the providers shown', async () => {
  serveStatus(
    statusReport({
      providers: [
        providerStatus(),
        providerStatus({
          provider: 'BETA',
          health: 'DOWN',
          stale: true,
          discarded: { UNKNOWN_AIRPORT: 3 },
        }),
      ],
    }),
  );
  const { container } = renderAsAdmin(<StatusPage />);
  await screen.findByRole('table', { name: 'Providers' });

  expect(await axe(container)).toHaveNoViolations();
});

test('says it is loading until the first status arrives', async () => {
  server.use(
    http.get(STATUS, async () => {
      await delay('infinite');
      return HttpResponse.json(statusReport());
    }),
  );

  renderAsAdmin(<StatusPage />);

  expect(await screen.findByText('Loading…')).toBeInTheDocument();
});

test('shows the generation, its publication and the next ingestion', async () => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-11-15T09:12:00Z'));
  serveStatus(statusReport());

  renderAsAdmin(<StatusPage />);

  expect(
    await screen.findByRole('heading', { level: 1, name: 'Provider status' }),
  ).toBeInTheDocument();
  expect(await screen.findByText('Generation 7')).toBeInTheDocument();
  expect(screen.getByText('Published 12 min ago')).toBeInTheDocument();
  expect(screen.getByText('Next ingestion in 18 min')).toBeInTheDocument();
});

test('gives the next ingestion in hours and minutes when it is over an hour away', async () => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-11-15T09:12:00Z'));
  serveStatus(statusReport({ nextIngestionAt: '2026-11-15T10:42:00Z' }));

  renderAsAdmin(<StatusPage />);

  expect(await screen.findByText('Next ingestion in 1 h 30 min')).toBeInTheDocument();
});

test('gives the next ingestion in whole hours without minutes', async () => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-11-15T09:12:00Z'));
  serveStatus(statusReport({ nextIngestionAt: '2026-11-15T11:12:00Z' }));

  renderAsAdmin(<StatusPage />);

  expect(await screen.findByText('Next ingestion in 2 h')).toBeInTheDocument();
});

test('says when the next ingestion is due', async () => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-11-15T09:30:20Z'));
  serveStatus(statusReport());

  renderAsAdmin(<StatusPage />);

  expect(await screen.findByText('Next ingestion due')).toBeInTheDocument();
});

/** The text of each cell of a provider's row, its header included. */
function rowOf(provider: string): string[] {
  const header = screen.getByRole('rowheader', { name: provider });
  const row = header.closest('tr');
  if (!row) throw new Error(`No row for ${provider}`);
  return [header, ...within(row).getAllByRole('cell')].map((cell) => cell.textContent);
}

test('shows one row per provider with its health and breaker', async () => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-11-15T09:12:00Z'));
  serveStatus(
    statusReport({
      providers: [
        providerStatus(),
        providerStatus({
          provider: 'BETA',
          health: 'DEGRADED',
          breaker: 'HALF_OPEN',
          newestQuotedAt: '2026-11-15T08:30:00Z',
          legs: 380,
          discarded: { UNKNOWN_AIRPORT: 3, PRICE: 2 },
        }),
        providerStatus({
          provider: 'GAMMA',
          health: 'DOWN',
          breaker: 'OPEN',
          newestQuotedAt: null,
          legs: 0,
        }),
      ],
    }),
  );

  renderAsAdmin(<StatusPage />);

  const table = await screen.findByRole('table', { name: 'Providers' });
  expect(
    within(table)
      .getAllByRole('columnheader')
      .map((each) => each.textContent),
  ).toEqual([
    'Provider',
    'Health',
    'Circuit breaker',
    'Last successful ingestion',
    'Legs',
    'Discarded records',
  ]);
  expect(rowOf('Alpha')).toEqual(['Alpha', 'Up', 'Closed', '12 min ago', '412', '0']);
  expect(rowOf('Beta')).toEqual([
    'Beta',
    'Degraded',
    'Half-open',
    '42 min ago',
    '380',
    '5Unknown airport: 3Price: 2',
  ]);
  expect(rowOf('Gamma')).toEqual(['Gamma', 'Down', 'Open', 'Never', '0', '0']);
});

test('marks a provider kept from the last good ingestion', async () => {
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date('2026-11-15T09:12:00Z'));
  serveStatus(
    statusReport({
      providers: [
        providerStatus(),
        providerStatus({ provider: 'BETA', health: 'DOWN', stale: true, legs: 380 }),
      ],
    }),
  );

  renderAsAdmin(<StatusPage />);

  await screen.findByRole('table', { name: 'Providers' });
  expect(rowOf('Beta')[4]).toBe('380Kept from the last good ingestion');
  expect(rowOf('Alpha')[4]).toBe('412');
});

test('shows that no ingestion has run yet', async () => {
  // What the API answers before the first run ends: nothing published, nothing scheduled.
  serveStatus({ generation: 0, publishedAt: null, nextIngestionAt: null, providers: [] });

  renderAsAdmin(<StatusPage />);

  expect(await screen.findByText('No ingestion has run yet.')).toBeInTheDocument();
  expect(screen.queryByText('Generation 0')).not.toBeInTheDocument();
  expect(screen.queryByRole('table')).not.toBeInTheDocument();
});

test('refreshes the status every 10 seconds', async () => {
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
  let requests = 0;
  server.use(
    http.get(STATUS, () => {
      requests += 1;
      return HttpResponse.json(statusReport({ generation: 6 + requests }));
    }),
  );
  renderAsAdmin(<StatusPage />);
  expect(await screen.findByText('Generation 7')).toBeInTheDocument();

  await act(async () => {
    await vi.advanceTimersByTimeAsync(9_000);
  });
  expect(requests).toBe(1);

  await act(async () => {
    await vi.advanceTimersByTimeAsync(1_000);
  });
  expect(await screen.findByText('Generation 8')).toBeInTheDocument();
  expect(requests).toBe(2);
});

test('keeps its times relative to the current time', async () => {
  // The status answered again is the same, so only the clock can move "12 min ago" on.
  vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] });
  vi.setSystemTime(new Date('2026-11-15T09:12:00Z'));
  serveStatus(statusReport());
  renderAsAdmin(<StatusPage />);
  expect(await screen.findByText('Published 12 min ago')).toBeInTheDocument();

  await act(async () => {
    await vi.advanceTimersByTimeAsync(60_000);
  });

  expect(screen.getByText('Published 13 min ago')).toBeInTheDocument();
  expect(screen.getByText('Next ingestion in 17 min')).toBeInTheDocument();
});

test('shows the missing admin role', async () => {
  // A signed-in account without the ADMIN role: Spring Security answers 403 with no body.
  server.use(http.get(STATUS, () => new HttpResponse(null, { status: 403 })));

  renderAsAdmin(<StatusPage />);

  expect(await screen.findByRole('alert')).toHaveTextContent(
    'This account does not have the admin role.',
  );
});

test('stops refreshing once the account is refused the admin role', async () => {
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
  let requests = 0;
  server.use(
    http.get(STATUS, () => {
      requests += 1;
      return new HttpResponse(null, { status: 403 });
    }),
  );
  renderAsAdmin(<StatusPage />);
  await screen.findByRole('alert');

  await act(async () => {
    await vi.advanceTimersByTimeAsync(30_000);
  });

  // The role will not come by itself: asking every 10 s would only be refused again.
  expect(requests).toBe(1);
});

test('shows a failure to read the status', async () => {
  // nginx's own page while the app is down: no problem body to show.
  server.use(
    http.get(
      STATUS,
      () =>
        new HttpResponse('<html>502 Bad Gateway</html>', {
          status: 502,
          headers: { 'Content-Type': 'text/html' },
        }),
    ),
  );

  renderAsAdmin(<StatusPage />);

  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Provider status could not be loaded. Retrying every 10 seconds.',
  );
});

test('keeps refreshing after a failure, as its message says', async () => {
  vi.useFakeTimers({ toFake: ['setInterval', 'clearInterval'] });
  let failing = true;
  server.use(
    http.get(STATUS, () =>
      failing ? new HttpResponse(null, { status: 502 }) : HttpResponse.json(statusReport()),
    ),
  );
  renderAsAdmin(<StatusPage />);
  await screen.findByRole('alert');

  failing = false;
  await act(async () => {
    await vi.advanceTimersByTimeAsync(10_000);
  });

  expect(await screen.findByText('Generation 7')).toBeInTheDocument();
  expect(screen.queryByRole('alert')).not.toBeInTheDocument();
});
