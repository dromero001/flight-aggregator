import { ApiError } from '../api/apiError';
import { ItineraryCard } from '../itinerary/ItineraryCard';
import { useNow } from '../time/useNow';
import { useWaitOver } from '../time/useWaitOver';
import styles from './ResultsList.module.css';
import { mergePages } from './mergePages';
import type { SearchCriteria } from './searchParams';
import { useItinerarySearch, useSearchResets } from './useItinerarySearch';

/** How often the cards' "Updated N min ago" is worked out again (Q7). */
const FRESHNESS_TICK_MS = 60_000;

/** The wait a rate limit asks for, in seconds; none for any other failure, or a 429 without one. */
function rateLimitWait(error: Error | null): number | undefined {
  return error instanceof ApiError && error.status === 429 ? error.retryAfterSeconds : undefined;
}

/**
 * What a failed search tells the customer (plan 6, Q7), and whether "Try again" (E9) may help: a
 * rejected filter would be rejected again, and a rate limit says how long to wait.
 */
function describeFailure(error: Error): { message: string; canTryAgain: boolean } {
  // A 400's detail names the parameter the API rejected.
  if (error instanceof ApiError && error.status === 400 && error.detail) {
    return { message: error.detail, canTryAgain: false };
  }
  const wait = rateLimitWait(error);
  if (wait !== undefined) {
    return {
      message: `Too many searches from your connection. Try again in ${String(wait)} seconds.`,
      canTryAgain: false,
    };
  }
  return { message: 'Flights could not be loaded. Search again in a moment.', canTryAgain: true };
}

/** The line above the cards: the first page loading, no result, or how many are shown. */
function statusText(isLoading: boolean, loaded: number | undefined): string {
  if (isLoading) return 'Loading flights…';
  if (loaded === undefined) return '';
  if (loaded === 0) {
    return 'No flights match these filters. Remove a filter or raise the maximum price.';
  }
  return loaded === 1 ? 'Showing 1 flight' : `Showing ${String(loaded)} flights`;
}

interface ResultsListProps {
  criteria: SearchCriteria;
  enabled: boolean;
}

export function ResultsList({ criteria, enabled }: ResultsListProps) {
  const search = useItinerarySearch(criteria, enabled);
  const { reload } = useSearchResets();
  const now = useNow(FRESHNESS_TICK_MS);
  const { itineraries, inventoryChanged } = mergePages(search.data?.pages ?? []);
  const failure = search.error && describeFailure(search.error);
  // After a rate-limited "Load more", the button comes back once the wait is over, so the pages
  // already loaded stay; "Search" would start again from the first page.
  const nextPageWaitOver = useWaitOver(
    search.errorUpdateCount,
    search.isFetchNextPageError ? rateLimitWait(search.error) : undefined,
  );
  const showsNextPageFailure = search.isFetchNextPageError && !nextPageWaitOver;

  function tryAgain() {
    // Repeats the request that failed: "Load more", or the first page.
    void (search.isFetchNextPageError ? search.fetchNextPage() : search.refetch());
  }

  const failureNotice = failure && (
    <div className={styles.failure}>
      <p role="alert">{failure.message}</p>
      {failure.canTryAgain && (
        <button
          className={styles.button}
          type="button"
          disabled={search.isFetching}
          onClick={tryAgain}
        >
          Try again
        </button>
      )}
    </div>
  );

  return (
    <section className={styles.results} aria-label="Flights" aria-busy={search.isLoading}>
      <p className={styles.status} role="status">
        {statusText(search.isLoading, search.data ? itineraries.length : undefined)}
      </p>
      {!search.isFetchNextPageError && failureNotice}
      {/* Always in the page, so the notice is announced when it appears. */}
      <div aria-live="polite">
        {inventoryChanged && (
          <p className={styles.notice}>
            <span>Results updated.</span>
            <button
              className={styles.button}
              type="button"
              onClick={() => {
                reload(criteria);
              }}
            >
              Reload
            </button>
          </p>
        )}
      </div>
      <ol className={styles.list}>
        {itineraries.map((itinerary) => (
          <li key={itinerary.id}>
            <ItineraryCard itinerary={itinerary} now={now} />
          </li>
        ))}
      </ol>
      {/* A failed "Load more" is shown where it was pressed, in its place (one way to ask again). */}
      {showsNextPageFailure
        ? failureNotice
        : search.hasNextPage && (
            <button
              className={`${styles.button} ${styles.loadMore}`}
              type="button"
              disabled={search.isFetchingNextPage}
              onClick={() => {
                void search.fetchNextPage();
              }}
            >
              {search.isFetchingNextPage ? 'Loading…' : 'Load more'}
            </button>
          )}
    </section>
  );
}
