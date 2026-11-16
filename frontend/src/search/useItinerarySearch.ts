import { useInfiniteQuery } from '@tanstack/react-query';
import { api, bodyOf } from '../api/client';
import type { SearchResponse } from '../api/types';
import type { SearchCriteria } from './searchParams';

/** The cache key of one search: its filters and sort, not its pages. */
export function itinerarySearchKey(criteria: SearchCriteria) {
  return ['itineraries', criteria] as const;
}

/** The search's pages, one more on each "Load more" (spec 5.4). */
export function useItinerarySearch(criteria: SearchCriteria, enabled: boolean) {
  return useInfiniteQuery({
    queryKey: itinerarySearchKey(criteria),
    queryFn: ({ pageParam }): Promise<SearchResponse> =>
      bodyOf(
        api.GET('/api/itineraries', { params: { query: { ...criteria, cursor: pageParam } } }),
      ),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor ?? undefined,
    enabled,
    // Loaded pages are never fetched again by themselves (on focus, reconnect or remount): the
    // page offers "Reload" instead (Q4-A), and each refetch would spend the rate limit.
    staleTime: Infinity,
  });
}
