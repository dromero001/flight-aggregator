import { focusManager, QueryClientProvider } from '@tanstack/react-query';
import { act, render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { delay, http, HttpResponse } from 'msw';
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

function renderAt(url: string) {
  const queryClient = createQueryClient();
  // Retries keep the app's rule but not its wait, so a test does not sleep through them.
  const defaults = queryClient.getDefaultOptions();
  queryClient.setDefaultOptions({ ...defaults, queries: { ...defaults.queries, retryDelay: 0 } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[url]}>
        <SearchPage />
        <Location />
      </MemoryRouter>
    </QueryClientProvider>,
  );
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
  // A 5xx may pass, so it is retried as TanStack does by default: three more times.
  expect(requests).toBe(4);
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

test('does not search while a filter is invalid', async () => {
  let requests = 0;
  server.use(
    http.get(SEARCH, () => {
      requests += 1;
      return HttpResponse.json(page({ itineraries: flights(1) }));
    }),
  );
  renderAt('/?origin=MADR&maxPrice=-1&sort=best');

  expect(screen.getByLabelText('From')).toBeInvalid();
  // Long enough for a request the page should not have sent to arrive.
  await new Promise((resolve) => setTimeout(resolve, 50));
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

test('does not reload the results by itself when the window regains focus', async () => {
  // Q4-A: the page offers a reload; it never swaps the list under the customer's eyes.
  let requests = 0;
  server.use(
    http.get(SEARCH, () => {
      requests += 1;
      return HttpResponse.json(page({ itineraries: flights(1) }));
    }),
  );
  renderAt('/?origin=MAD');
  await screen.findByRole('article');

  act(() => {
    focusManager.setFocused(false);
    focusManager.setFocused(true);
  });
  await new Promise((resolve) => setTimeout(resolve, 50));

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
