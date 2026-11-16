import { useInfiniteQuery, useQueryClient } from '@tanstack/react-query';
import { api, bodyOf } from '../api/client';
import type { SearchResponse } from '../api/types';
import type { SearchCriteria } from './searchParams';

/** The cache key of one search: its filters and sort, not its pages. */
function itinerarySearchKey(criteria: SearchCriteria) {
  return ['itineraries', criteria] as const;
}

/** The search's pages, one more on each "Load more" (spec 5.4). */
export function useItinerarySearch(criteria: SearchCriteria, enabled: boolean) {
  return useInfiniteQuery({
    queryKey: itinerarySearchKey(criteria),
    // TanStack aborts the signal when no one waits for the answer any more (the filters changed).
    queryFn: ({ pageParam, signal }): Promise<SearchResponse> =>
      bodyOf(
        api.GET('/api/itineraries', {
          params: { query: { ...criteria, cursor: pageParam } },
          signal,
        }),
      ),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    enabled,
    // Loaded pages are never fetched again by themselves (on focus, reconnect or remount): the
    // page offers "Reload" instead (Q4-A), and each refetch would spend the rate limit. A failed
    // search has no data, which TanStack counts stale whatever the staleTime, so focus and
    // reconnect are turned off too: during a rate limit's wait, a tab switch must not search.
    staleTime: Infinity,
    refetchOnWindowFocus: false,
    refetchOnReconnect: false,
  });
}

/**
 * The two ways a search is read again from its first page, each on that search alone (`exact`: a
 * key is also a prefix of every cached search with more filters).
 */
export function useSearchResets() {
  const queryClient = useQueryClient();
  return {
    /** "Reload" (Q4-A): drops every loaded page and reads the first one on the current generation. */
    reload: (criteria: SearchCriteria) => {
      void queryClient.resetQueries({ queryKey: itinerarySearchKey(criteria), exact: true });
    },
    /**
     * The same search submitted again keeps its key, so nothing would be asked; if it failed (a
     * rate limit whose wait is over, an outage), it is asked again. Fresh results stay Reload's job.
     */
    retryIfFailed: (criteria: SearchCriteria) => {
      void queryClient.resetQueries({
        queryKey: itinerarySearchKey(criteria),
        exact: true,
        predicate: (query) => query.state.status === 'error',
      });
    },
  };
}
