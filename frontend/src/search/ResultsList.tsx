import { useQueryClient } from '@tanstack/react-query';
import { ApiError } from '../api/apiError';
import { ItineraryCard } from '../itinerary/ItineraryCard';
import { useNow } from '../itinerary/useNow';
import styles from './ResultsList.module.css';
import { mergePages } from './mergePages';
import type { SearchCriteria } from './searchParams';
import { itinerarySearchKey, useItinerarySearch } from './useItinerarySearch';

/** How often the cards' "Updated N min ago" is worked out again (Q7). */
const FRESHNESS_TICK_MS = 60_000;

/** What a failed search tells the customer (plan 6, Q7). */
function failureMessage(error: Error): string {
  if (error instanceof ApiError) {
    // A 400's detail names the parameter the API rejected.
    if (error.status === 400 && error.detail) return error.detail;
    if (error.status === 429 && error.retryAfterSeconds !== undefined) {
      return `Too many searches from your connection. Try again in ${String(error.retryAfterSeconds)} seconds.`;
    }
  }
  return 'Flights could not be loaded. Search again in a moment.';
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
  const queryClient = useQueryClient();
  const search = useItinerarySearch(criteria, enabled);
  const now = useNow(FRESHNESS_TICK_MS);
  const { itineraries, inventoryChanged } = mergePages(search.data?.pages ?? []);

  function reload() {
    // Drops every loaded page and reads the first one again, on the current generation.
    void queryClient.resetQueries({ queryKey: itinerarySearchKey(criteria) });
  }

  return (
    <section className={styles.results} aria-label="Flights" aria-busy={search.isLoading}>
      <p className={styles.status} role="status">
        {statusText(search.isLoading, search.data ? itineraries.length : undefined)}
      </p>
      {search.error && (
        <p className={styles.failure} role="alert">
          {failureMessage(search.error)}
        </p>
      )}
      {/* Always in the page, so the notice is announced when it appears. */}
      <div aria-live="polite">
        {inventoryChanged && (
          <p className={styles.notice}>
            <span>Results updated.</span>
            <button className={styles.button} type="button" onClick={reload}>
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
      {search.hasNextPage && (
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
