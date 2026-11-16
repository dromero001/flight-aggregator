import { focusManager, onlineManager, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { delay, http, HttpResponse } from 'msw';
import { Profiler } from 'react';
import { MemoryRouter, useLocation, useNavigate } from 'react-router';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import type { Itinerary, SearchResponse } from '../api/types';
import { createQueryClient } from '../queryClient';
import { axe } from '../test/axe';
import { connecting, direct } from '../test/fixtures';
import { server } from '../test/server';
import { SearchPage } from './SearchPage';

const SEARCH = '*/api/itineraries';

function page(overrides: Partial<SearchResponse> = {}): SearchResponse {
  return { itineraries: [], nextCursor: null, inventoryChanged: false, ...overrides };
}

/** An RFC 9457 answer, as the API sends its 4xx. */
function problem(status: number, detail: string, headers: Record<string, string> = {}) {
  return HttpResponse.json(
    { type: 'about:blank', title: 'Error', status, detail },
    { status, headers: { 'Content-Type': 'application/problem+json', ...headers } },
  );
}

/** `count` distinct direct flights, numbered from `first`. */
function flights(count: number, first = 0): Itinerary[] {
  return Array.from({ length: count }, (_, index) =>
    direct({ id: `00000000-0000-4000-8000-${String(first + index).padStart(12, '0')}` }),
  );
}

beforeEach(() => {
  // Every page searches as it opens; a test that cares about the answer overrides this one.
  server.use(http.get(SEARCH, () => HttpResponse.json(page())));
});
afterEach(() => {
  vi.useRealTimers();
  focusManager.setFocused(undefined);
  onlineManager.setOnline(true);
});

function Location() {
  const { search } = useLocation();
  const navigate = useNavigate();
  return (
    <>
      <output aria-label="location">{search}</output>
      <button
        type="button"
        onClick={() => {
          void navigate(-1);
        }}
      >
        Back
      </button>
    </>
  );
}

/** Renders the page at `url`; `onCommit` runs after each render React commits to the page. */
function renderAt(url: string, onCommit: () => void = () => undefined) {
  const queryClient = createQueryClient();
  // Retries keep the app's rule but not its wait, so a test does not sleep through them.
  const defaults = queryClient.getDefaultOptions();
  queryClient.setDefaultOptions({ ...defaults, queries: { ...defaults.queries, retryDelay: 0 } });
  const rendered = render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[url]}>
        <Profiler id="search-page" onRender={onCommit}>
          <SearchPage />
        </Profiler>
        <Location />
      </MemoryRouter>
    </QueryClientProvider>,
  );
  return { ...rendered, queryClient };
}

/**
 * The window loses and regains the focus, which makes TanStack refetch what it counts stale. The
 * refetch starts a few microtasks after the event, which `act` lets run.
 */
async function refocus() {
  await act(async () => {
    focusManager.setFocused(false);
    focusManager.setFocused(true);
    await Promise.resolve();
  });
}

test('has no accessibility violations with results on the page', async () => {
  server.use(
    http.get(SEARCH, () =>
      HttpResponse.json(page({ itineraries: [direct(), connecting()], nextCursor: 'page-2' })),
    ),
  );
  const { container } = renderAt('/?origin=MAD');
  await screen.findByRole('button', { name: 'Load more' });

  expect(await axe(container)).toHaveNoViolations();
});

test('shows the loading state', () => {
  server.use(
    http.get(SEARCH, async () => {
      await delay('infinite');
      return HttpResponse.json(page());
    }),
  );
  renderAt('/');

  const results = screen.getByRole('region', { name: 'Flights' });
  expect(results).toHaveAttribute('aria-busy', 'true');
  expect(within(results).getByText('Loading flights…')).toBeInTheDocument();
});

test('shows the first page and how many flights are shown', async () => {
  server.use(http.get(SEARCH, () => HttpResponse.json(page({ itineraries: flights(20) }))));
  renderAt('/?origin=MAD');

  const results = screen.getByRole('region', { name: 'Flights' });
  // A polite live region, so a screen reader hears the count when it changes.
  expect(await within(results).findByText('Showing 20 flights')).toHaveRole('status');
  expect(within(results).getAllByRole('article')).toHaveLength(20);
  expect(results).toHaveAttribute('aria-busy', 'false');
});

test('counts a single flight in the singular', async () => {
  server.use(http.get(SEARCH, () => HttpResponse.json(page({ itineraries: flights(1) }))));
  renderAt('/?origin=MAD');

  expect(await screen.findByText('Showing 1 flight')).toHaveRole('status');
});

test('keeps the freshness true on a page left open', async () => {
  // Only the clock and the interval are faked; MSW and Testing Library keep their real timers.
  vi.useFakeTimers({ toFake: ['Date', 'setInterval', 'clearInterval'] });
  vi.setSystemTime(new Date('2026-11-15T09:05:00Z')); // five minutes after the quote
  server.use(http.get(SEARCH, () => HttpResponse.json(page({ itineraries: flights(1) }))));
  renderAt('/?origin=MAD');
  expect(await screen.findByText('Updated 5 min ago')).toBeInTheDocument();

  act(() => {
    vi.advanceTimersByTime(60_000);
  });

  expect(screen.getByText('Updated 6 min ago')).toBeInTheDocument();
});

test('loads more until the last page', async () => {
  const user = userEvent.setup();
  let releaseSecondPage: (() => void) | undefined;
  const secondPageHeld = new Promise<void>((resolve) => {
    releaseSecondPage = resolve;
  });
  server.use(
    http.get(SEARCH, async ({ request }) => {
      if (new URL(request.url).searchParams.get('cursor') !== 'page-2') {
        return HttpResponse.json(page({ itineraries: flights(20), nextCursor: 'page-2' }));
      }
      await secondPageHeld;
      return HttpResponse.json(page({ itineraries: flights(20, 20) }));
    }),
  );
  renderAt('/');

  await user.click(await screen.findByRole('button', { name: 'Load more' }));
  expect(screen.getByRole('button', { name: 'Loading…' })).toBeInTheDocument();
  releaseSecondPage?.();

  expect(await screen.findByText('Showing 40 flights')).toBeInTheDocument();
  expect(screen.getAllByRole('article')).toHaveLength(40);
  expect(screen.queryByRole('button', { name: 'Load more' })).not.toBeInTheDocument();
});

test('searches again when the sort changes', async () => {
  const user = userEvent.setup();
  const searched: URLSearchParams[] = [];
  server.use(
    http.get(SEARCH, ({ request }) => {
      searched.push(new URL(request.url).searchParams);
      return HttpResponse.json(page({ itineraries: flights(1) }));
    }),
  );
  renderAt('/?origin=MAD');
  await screen.findByRole('article');

  await user.click(screen.getByRole('radio', { name: 'Duration' }));

  await waitFor(() => {
    expect(searched).toHaveLength(2);
  });
  expect(searched[1]?.get('sort')).toBe('duration');
  expect(searched[1]?.get('origin')).toBe('MAD');
});

test('stops loading a search the customer has left', async () => {
  // A hung API: the request of the sort left behind must not keep a connection open.
  const user = userEvent.setup();
  let abandoned: AbortSignal | undefined;
  server.use(
    http.get(SEARCH, async ({ request }) => {
      if (new URL(request.url).searchParams.get('sort') !== 'duration') {
        abandoned = request.signal;
        await delay('infinite');
      }
      return HttpResponse.json(page({ itineraries: flights(1) }));
    }),
  );
  renderAt('/?origin=MAD');
  await waitFor(() => {
    expect(abandoned).toBeDefined();
  });

  await user.click(screen.getByRole('radio', { name: 'Duration' }));

  expect(await screen.findByText('Showing 1 flight')).toBeInTheDocument();
  expect(abandoned?.aborted).toBe(true);
});

test('shows the empty state', async () => {
  // The default handler answers an empty last page.
  renderAt('/?origin=MAD&maxPrice=10');

  expect(
    await screen.findByText(
      'No flights match these filters. Remove a filter or raise the maximum price.',
    ),
  ).toHaveRole('status');
  expect(screen.queryByText('Showing 0 flights')).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Load more' })).not.toBeInTheDocument();
});

test("shows the API's detail for a rejected filter", async () => {
  server.use(
    http.get(SEARCH, () =>
      problem(400, 'The cursor does not belong to this search; start again from the first page.'),
    ),
  );
  renderAt('/?origin=MAD');

  expect(await screen.findByRole('alert')).toHaveTextContent(
    'The cursor does not belong to this search; start again from the first page.',
  );
  // The same search would be rejected again; the detail says what to change.
  expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument();
});

test('shows a failure without a problem body', async () => {
  // What nginx answers while the app is down.
  let requests = 0;
  server.use(
    http.get(SEARCH, () => {
      requests += 1;
      return new HttpResponse('<html><body><h1>502 Bad Gateway</h1></body></html>', {
        status: 502,
        headers: { 'Content-Type': 'text/html' },
      });
    }),
  );
  renderAt('/?origin=MAD');

  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Flights could not be loaded. Search again in a moment.',
  );
  // A 5xx without Retry-After may pass, so it is retried as TanStack does by default: three more
  // times.
  expect(requests).toBe(4);
});

test('does not retry a failure that says when to ask again', async () => {
  let requests = 0;
  server.use(
    http.get(SEARCH, () => {
      requests += 1;
      return problem(503, 'The inventory could not be read in time.', { 'Retry-After': '60' });
    }),
  );
  renderAt('/?origin=MAD');

  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Flights could not be loaded. Search again in a moment.',
  );
  // Asking again at once would not be answered sooner than Retry-After says.
  expect(requests).toBe(1);
});

test('tries a failed search again', async () => {
  const user = userEvent.setup();
  let appUp = false;
  server.use(
    http.get(SEARCH, () =>
      appUp
        ? HttpResponse.json(page({ itineraries: flights(1) }))
        : new HttpResponse(null, { status: 502 }),
    ),
  );
  renderAt('/?origin=MAD');
  await screen.findByRole('alert');

  appUp = true;
  await user.click(screen.getByRole('button', { name: 'Try again' }));

  expect(await screen.findByText('Showing 1 flight')).toBeInTheDocument();
  expect(screen.queryByRole('alert')).not.toBeInTheDocument();
});

test('cannot try again while a failed next page is being tried again', async () => {
  // TanStack keeps the error while the repeat runs (the list has data): a second click would
  // abort the request in flight and send another, each one counted by the rate limit.
  const user = userEvent.setup();
  let appUp = true;
  server.use(
    http.get(SEARCH, async ({ request }) => {
      if (new URL(request.url).searchParams.get('cursor') !== 'page-2') {
        return HttpResponse.json(page({ itineraries: flights(1), nextCursor: 'page-2' }));
      }
      if (!appUp) return new HttpResponse(null, { status: 502 });
      await delay('infinite');
      return HttpResponse.json(page());
    }),
  );
  renderAt('/?origin=MAD');
  await screen.findByText('Showing 1 flight');
  appUp = false;
  await user.click(screen.getByRole('button', { name: 'Load more' }));
  await screen.findByRole('alert');

  appUp = true;
  await user.click(screen.getByRole('button', { name: 'Try again' }));

  expect(screen.getByRole('button', { name: 'Try again' })).toBeDisabled();
});

test('tries a failed next page again', async () => {
  const user = userEvent.setup();
  let appUp = true;
  const cursors: (string | null)[] = [];
  server.use(
    http.get(SEARCH, ({ request }) => {
      const cursor = new URL(request.url).searchParams.get('cursor');
      cursors.push(cursor);
      if (!appUp) return new HttpResponse(null, { status: 502 });
      return cursor === 'page-2'
        ? HttpResponse.json(page({ itineraries: flights(1, 1) }))
        : HttpResponse.json(page({ itineraries: flights(1), nextCursor: 'page-2' }));
    }),
  );
  renderAt('/?origin=MAD');
  await screen.findByText('Showing 1 flight');
  appUp = false;
  await user.click(screen.getByRole('button', { name: 'Load more' }));
  await screen.findByRole('alert');

  appUp = true;
  cursors.length = 0;
  await user.click(screen.getByRole('button', { name: 'Try again' }));

  // The page that failed is read again, not the one already shown.
  expect(await screen.findByText('Showing 2 flights')).toBeInTheDocument();
  expect(cursors).toEqual(['page-2']);
});

test('does not offer Load more again after a rate-limited next page', async () => {
  // A click during the wait would spend the limit again, as "Try again" would.
  const user = userEvent.setup();
  server.use(
    http.get(SEARCH, ({ request }) =>
      new URL(request.url).searchParams.get('cursor') === 'page-2'
        ? problem(429, 'Too many requests from this address.', { 'Retry-After': '30' })
        : HttpResponse.json(page({ itineraries: flights(1), nextCursor: 'page-2' })),
    ),
  );
  renderAt('/?origin=MAD');
  await user.click(await screen.findByRole('button', { name: 'Load more' }));

  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Too many searches from your connection. Try again in 30 seconds.',
  );
  expect(screen.queryByRole('button', { name: 'Load more' })).not.toBeInTheDocument();
  expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument();
});

test('offers Load more again once a rate-limited next page has waited', async () => {
  // The pages already loaded stay; "Search" would start again from the first page.
  // Faked timeouts still run with the real clock, so MSW and Testing Library keep working.
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'], shouldAdvanceTime: true });
  const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
  const cursors: (string | null)[] = [];
  server.use(
    http.get(SEARCH, ({ request }) => {
      const cursor = new URL(request.url).searchParams.get('cursor');
      cursors.push(cursor);
      if (cursor !== 'page-2') {
        return HttpResponse.json(page({ itineraries: flights(1), nextCursor: 'page-2' }));
      }
      return cursors.filter((sent) => sent === 'page-2').length === 1
        ? problem(429, 'Too many requests from this address.', { 'Retry-After': '30' })
        : HttpResponse.json(page({ itineraries: flights(1, 1) }));
    }),
  );
  renderAt('/?origin=MAD');
  await user.click(await screen.findByRole('button', { name: 'Load more' }));
  await screen.findByText('Too many searches from your connection. Try again in 30 seconds.');

  act(() => {
    vi.advanceTimersByTime(29_000);
  });
  expect(screen.queryByRole('button', { name: 'Load more' })).not.toBeInTheDocument();
  act(() => {
    vi.advanceTimersByTime(1_000);
  });
  await user.click(screen.getByRole('button', { name: 'Load more' }));

  expect(await screen.findByText('Showing 2 flights')).toBeInTheDocument();
  expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  expect(cursors).toEqual([null, 'page-2', 'page-2']);
});

test('waits again when the next page is rate-limited again', async () => {
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'], shouldAdvanceTime: true });
  const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
  const cursors: (string | null)[] = [];
  server.use(
    http.get(SEARCH, ({ request }) => {
      const cursor = new URL(request.url).searchParams.get('cursor');
      cursors.push(cursor);
      return cursor === 'page-2'
        ? problem(429, 'Too many requests from this address.', { 'Retry-After': '30' })
        : HttpResponse.json(page({ itineraries: flights(1), nextCursor: 'page-2' }));
    }),
  );
  renderAt('/?origin=MAD');
  await user.click(await screen.findByRole('button', { name: 'Load more' }));
  await screen.findByRole('alert');
  act(() => {
    vi.advanceTimersByTime(30_000);
  });

  await user.click(screen.getByRole('button', { name: 'Load more' }));

  expect(await screen.findByRole('alert')).toHaveTextContent('Try again in 30 seconds.');
  expect(screen.queryByRole('button', { name: 'Load more' })).not.toBeInTheDocument();
  expect(cursors).toEqual([null, 'page-2', 'page-2']);
});

test("waits for a new search's own rate-limited next page", async () => {
  // The wait that ended belonged to the search before; this one has not started its own.
  vi.useFakeTimers({ toFake: ['setTimeout', 'clearTimeout'], shouldAdvanceTime: true });
  const user = userEvent.setup({ advanceTimers: vi.advanceTimersByTime });
  const requests: string[] = [];
  server.use(
    http.get(SEARCH, ({ request }) => {
      const params = new URL(request.url).searchParams;
      requests.push(`${String(params.get('origin'))}:${String(params.get('cursor'))}`);
      return params.get('cursor') === 'page-2'
        ? problem(429, 'Too many requests from this address.', { 'Retry-After': '30' })
        : HttpResponse.json(page({ itineraries: flights(1), nextCursor: 'page-2' }));
    }),
  );
  renderAt('/?origin=MAD');
  await user.click(await screen.findByRole('button', { name: 'Load more' }));
  await screen.findByRole('alert');
  act(() => {
    vi.advanceTimersByTime(30_000);
  });
  await user.clear(screen.getByLabelText('From'));
  await user.type(screen.getByLabelText('From'), 'BCN');
  await user.click(screen.getByRole('button', { name: 'Search' }));

  await user.click(await screen.findByRole('button', { name: 'Load more' }));

  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Too many searches from your connection. Try again in 30 seconds.',
  );
  expect(screen.queryByRole('button', { name: 'Load more' })).not.toBeInTheDocument();
  expect(requests).toEqual(['MAD:null', 'MAD:page-2', 'BCN:null', 'BCN:page-2']);
});

test('shows a next-page failure where Load more was', async () => {
  // The customer pressed "Load more" at the bottom of the list, so the failure is shown there.
  const user = userEvent.setup();
  server.use(
    http.get(SEARCH, ({ request }) =>
      new URL(request.url).searchParams.get('cursor') === 'page-2'
        ? new HttpResponse(null, { status: 502 })
        : HttpResponse.json(page({ itineraries: flights(1), nextCursor: 'page-2' })),
    ),
  );
  renderAt('/?origin=MAD');
  await user.click(await screen.findByRole('button', { name: 'Load more' }));

  const failure = await screen.findByRole('alert');
  const list = screen.getByRole('list');
  expect(list.compareDocumentPosition(failure) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
  expect(screen.getAllByRole('button', { name: 'Try again' })).toHaveLength(1);
});

test('does not retry a rate-limited search', async () => {
  let requests = 0;
  server.use(
    http.get(SEARCH, () => {
      requests += 1;
      return problem(429, 'Too many requests from this address.', { 'Retry-After': '30' });
    }),
  );
  renderAt('/?origin=MAD');

  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Too many searches from your connection. Try again in 30 seconds.',
  );
  expect(requests).toBe(1);
  // A click now would spend the limit again; the message says when to search.
  expect(screen.queryByRole('button', { name: 'Try again' })).not.toBeInTheDocument();
});

test('shows the failure for a rate limit without a wait', async () => {
  // The contract always sends Retry-After; a proxy's own 429 might not.
  server.use(http.get(SEARCH, () => problem(429, 'Too many requests.')));
  renderAt('/?origin=MAD');

  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Flights could not be loaded. Search again in a moment.',
  );
});

test('searches again with the same filters after a rate-limited search', async () => {
  const user = userEvent.setup();
  let requests = 0;
  server.use(
    http.get(SEARCH, () => {
      requests += 1;
      return requests === 1
        ? problem(429, 'Too many requests from this address.', { 'Retry-After': '30' })
        : HttpResponse.json(page({ itineraries: flights(1) }));
    }),
  );
  renderAt('/?origin=MAD');
  await screen.findByText('Too many searches from your connection. Try again in 30 seconds.');

  // The wait is over; the filters are unchanged, so the URL does not change either.
  await user.click(screen.getByRole('button', { name: 'Search' }));

  expect(await screen.findByText('Showing 1 flight')).toBeInTheDocument();
  expect(requests).toBe(2);
});

test('keeps the shown results when the same filters are searched again', async () => {
  const user = userEvent.setup();
  let requests = 0;
  server.use(
    http.get(SEARCH, () => {
      requests += 1;
      return HttpResponse.json(page({ itineraries: flights(1) }));
    }),
  );
  const { queryClient } = renderAt('/?origin=MAD');
  await screen.findByText('Showing 1 flight');

  await user.click(screen.getByRole('button', { name: 'Search' }));

  // Only a failed search is asked again; "Reload" is the way to fresh results (Q4-A). A request
  // the click started would be in flight or counted by now.
  expect(queryClient.isFetching()).toBe(0);
  expect(requests).toBe(1);
});

test('does not search while a filter is invalid', () => {
  let requests = 0;
  server.use(
    http.get(SEARCH, () => {
      requests += 1;
      return HttpResponse.json(page({ itineraries: flights(1) }));
    }),
  );
  const { queryClient } = renderAt('/?origin=MADR&maxPrice=-1&sort=best');

  expect(screen.getByLabelText('From')).toBeInvalid();
  // An enabled query starts fetching as the page mounts.
  expect(queryClient.isFetching()).toBe(0);
  expect(requests).toBe(0);
  expect(screen.queryByText('Loading flights…')).not.toBeInTheDocument();
  expect(screen.queryByRole('article')).not.toBeInTheDocument();
});

test('offers a reload when the inventory changed', async () => {
  const user = userEvent.setup();
  const cursors: (string | null)[] = [];
  server.use(
    http.get(SEARCH, ({ request }) => {
      const cursor = new URL(request.url).searchParams.get('cursor');
      cursors.push(cursor);
      return cursor === 'page-2'
        ? // Read on a new generation (plan 5 Q1-A): flagged, with one row the first page showed.
          HttpResponse.json(page({ itineraries: flights(20, 19), inventoryChanged: true }))
        : HttpResponse.json(page({ itineraries: flights(20), nextCursor: 'page-2' }));
    }),
  );
  renderAt('/');
  await user.click(await screen.findByRole('button', { name: 'Load more' }));

  expect(await screen.findByText('Results updated.')).toBeInTheDocument();
  expect(screen.getByText('Showing 39 flights')).toBeInTheDocument();

  await user.click(screen.getByRole('button', { name: 'Reload' }));

  expect(await screen.findByText('Showing 20 flights')).toBeInTheDocument();
  expect(cursors).toEqual([null, 'page-2', null]);
  expect(screen.queryByText('Results updated.')).not.toBeInTheDocument();
});

test('reloads only the search shown', async () => {
  // A cached search whose filters include the shown one's must survive its "Reload".
  const user = userEvent.setup();
  const toNewYork: string[] = [];
  server.use(
    http.get(SEARCH, ({ request }) => {
      const params = new URL(request.url).searchParams;
      if (params.get('destination') === 'JFK') {
        toNewYork.push(request.url);
        return HttpResponse.json(page({ itineraries: flights(1) }));
      }
      return params.get('cursor') === 'page-2'
        ? HttpResponse.json(page({ itineraries: flights(1, 1), inventoryChanged: true }))
        : HttpResponse.json(page({ itineraries: flights(1), nextCursor: 'page-2' }));
    }),
  );
  renderAt('/?origin=MAD&destination=JFK');
  await screen.findByText('Showing 1 flight');
  await user.clear(screen.getByLabelText('To'));
  await user.click(screen.getByRole('button', { name: 'Search' }));
  await user.click(await screen.findByRole('button', { name: 'Load more' }));
  await user.click(await screen.findByRole('button', { name: 'Reload' }));
  await screen.findByRole('button', { name: 'Load more' });

  await user.click(screen.getByRole('button', { name: 'Back' }));

  expect(await screen.findByText('Showing 1 flight')).toBeInTheDocument();
  expect(toNewYork).toHaveLength(1);
});

test('does not reload the results by itself when the window regains focus', async () => {
  // Q4-A: the page offers a reload; it never swaps the list under the customer's eyes.
  let requests = 0;
  server.use(
    http.get(SEARCH, () => {
      requests += 1;
      return HttpResponse.json(page({ itineraries: flights(1) }));
    }),
  );
  const { queryClient } = renderAt('/?origin=MAD');
  await screen.findByRole('article');

  await refocus();

  expect(queryClient.isFetching()).toBe(0);
  expect(requests).toBe(1);
});

test('does not search again when the window regains focus after a failure', async () => {
  // During a rate limit's wait, a tab switch must not spend the limit again.
  let requests = 0;
  server.use(
    http.get(SEARCH, () => {
      requests += 1;
      return problem(429, 'Too many requests from this address.', { 'Retry-After': '30' });
    }),
  );
  renderAt('/?origin=MAD');
  await screen.findByRole('alert');

  await refocus();

  expect(requests).toBe(1);
});

test('does not search again when the connection comes back after a failure', async () => {
  let requests = 0;
  server.use(
    http.get(SEARCH, () => {
      requests += 1;
      return problem(429, 'Too many requests from this address.', { 'Retry-After': '30' });
    }),
  );
  renderAt('/?origin=MAD');
  await screen.findByRole('alert');

  await act(async () => {
    onlineManager.setOnline(false);
    onlineManager.setOnline(true);
    await Promise.resolve();
  });

  expect(requests).toBe(1);
});

test('restores the previous search on back', async () => {
  const user = userEvent.setup();
  renderAt('/?origin=MAD&sort=departure');

  await user.clear(screen.getByLabelText('From'));
  await user.type(screen.getByLabelText('From'), 'bcn');
  await user.click(screen.getByRole('button', { name: 'Search' }));
  expect(screen.getByLabelText('location')).toHaveTextContent('?origin=BCN&sort=departure');

  await user.click(screen.getByRole('button', { name: 'Back' }));

  expect(screen.getByLabelText('location')).toHaveTextContent('?origin=MAD&sort=departure');
  expect(screen.getByLabelText('From')).toHaveValue('MAD');
  expect(screen.getByRole('radio', { name: 'Departure' })).toBeChecked();
});

test('adds no history entry for the search already shown', async () => {
  // "mad" normalises to the MAD already in the URL: Back must not seem to do nothing.
  const user = userEvent.setup();
  renderAt('/');
  await user.type(screen.getByLabelText('From'), 'MAD');
  await user.click(screen.getByRole('button', { name: 'Search' }));
  await user.clear(screen.getByLabelText('From'));
  await user.type(screen.getByLabelText('From'), 'mad');
  await user.click(screen.getByRole('button', { name: 'Search' }));

  await user.click(screen.getByRole('button', { name: 'Back' }));

  expect(screen.getByLabelText('location')).toBeEmptyDOMElement();
});

test('shows the normalised value when the search is already shown', async () => {
  const user = userEvent.setup();
  renderAt('/?origin=MAD');
  await user.clear(screen.getByLabelText('From'));
  await user.type(screen.getByLabelText('From'), ' mad ');

  await user.click(screen.getByRole('button', { name: 'Search' }));

  expect(screen.getByLabelText('From')).toHaveValue('MAD');
});

test('searches the airports of the cities typed in From and To by their codes', async () => {
  const user = userEvent.setup();
  renderAt('/');
  await user.type(screen.getByLabelText('From'), 'madrid');
  await user.type(screen.getByLabelText('To'), 'New York');

  await user.click(screen.getByRole('button', { name: 'Search' }));

  expect(screen.getByLabelText('location')).toHaveTextContent('?origin=MAD&destination=JFK');
  expect(screen.getByLabelText('From')).toHaveValue('MAD');
  expect(screen.getByLabelText('To')).toHaveValue('JFK');
});

test('searches the airline whose name is typed in Airline by its code', async () => {
  const user = userEvent.setup();
  renderAt('/');
  await user.type(screen.getByLabelText('Airline'), 'iberia');

  await user.click(screen.getByRole('button', { name: 'Search' }));

  expect(screen.getByLabelText('location')).toHaveTextContent(/^\?carrier=IB$/);
  expect(screen.getByLabelText('Airline')).toHaveValue('IB');
});

test('searches a maximum price typed with a decimal comma with a dot', async () => {
  const user = userEvent.setup();
  renderAt('/');
  await user.type(screen.getByLabelText('Max price'), '55,5');

  await user.click(screen.getByRole('button', { name: 'Search' }));

  expect(screen.getByLabelText('location')).toHaveTextContent('?maxPrice=55.5');
  expect(screen.getByLabelText('Max price')).toHaveValue('55.5');
});

test('keeps the searched values in the inputs while a new search is applied', async () => {
  // The router applies the new URL in a transition, after the submit's own render.
  const user = userEvent.setup();
  const shown: string[] = [];
  renderAt('/', () => {
    shown.push(screen.getByLabelText<HTMLInputElement>('From').value);
  });
  await user.type(screen.getByLabelText('From'), 'BCN');
  shown.length = 0;

  await user.click(screen.getByRole('button', { name: 'Search' }));

  expect(screen.getByLabelText('location')).toHaveTextContent('?origin=BCN');
  expect(new Set(shown)).toEqual(new Set(['BCN']));
});

test('restores the previous sort on back', async () => {
  const user = userEvent.setup();
  renderAt('/?origin=MAD');

  await user.click(screen.getByRole('radio', { name: 'Duration' }));
  expect(screen.getByLabelText('location')).toHaveTextContent('?origin=MAD&sort=duration');

  await user.click(screen.getByRole('button', { name: 'Back' }));

  expect(screen.getByLabelText('location')).toHaveTextContent(/^\?origin=MAD$/);
  expect(screen.getByRole('radio', { name: 'Price' })).toBeChecked();
});

test('keeps the focus in the form after a search changes the URL', async () => {
  const user = userEvent.setup();
  renderAt('/');

  await user.type(screen.getByLabelText('From'), 'mad{Enter}');

  expect(screen.getByLabelText('location')).toHaveTextContent('?origin=MAD');
  expect(screen.getByLabelText('From')).toHaveFocus();
  expect(screen.getByLabelText('From')).toHaveValue('MAD');
});

test('announces an error that appears after a submit', async () => {
  const user = userEvent.setup();
  renderAt('/');

  await user.type(screen.getByLabelText('From'), 'madr{Enter}');

  const message = screen.getByText('Enter a 3-letter airport code, like MAD');
  expect(message.closest('[aria-live="polite"]')).not.toBeNull();
});

test('shows invalid URL values as field errors', () => {
  renderAt('/?origin=MADR&maxPrice=-1&sort=best');

  const origin = screen.getByLabelText('From');
  expect(origin).toBeInvalid();
  expect(origin).toHaveAccessibleDescription('Enter a 3-letter airport code, like MAD');
  expect(origin).toHaveValue('MADR');
  expect(screen.getByLabelText('Max price')).toHaveAccessibleDescription(
    'Enter an amount in euros above 0, like 150 or 149.99',
  );
  expect(screen.getByLabelText('To')).toBeValid();
  expect(screen.getByLabelText('To')).not.toHaveAccessibleDescription();
  expect(screen.getByRole('radio', { name: 'Price' })).toBeChecked();
});

test('writes a submitted search to the URL', async () => {
  const user = userEvent.setup();
  renderAt('/');

  await user.type(screen.getByLabelText('From'), 'mad');
  await user.type(screen.getByLabelText('To'), 'JFK');
  await user.type(screen.getByLabelText('Max price'), '150');
  await user.click(screen.getByRole('button', { name: 'Search' }));
  await user.click(screen.getByRole('radio', { name: 'Duration' }));

  expect(screen.getByLabelText('location')).toHaveTextContent(
    '?origin=MAD&destination=JFK&maxPrice=150&sort=duration',
  );
});
