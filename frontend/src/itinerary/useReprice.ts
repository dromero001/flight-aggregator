import { useMutation } from '@tanstack/react-query';
import { api, bodyOf } from '../api/client';
import type { RepriceResponse } from '../api/types';

/**
 * Confirms a chosen itinerary's price with the providers (spec 6.5), by its id. A failure is the
 * client's `ApiError` for an HTTP answer, or the `fetch` error when none came.
 */
export function useReprice() {
  return useMutation<RepriceResponse, Error, string>({
    mutationFn: (id) =>
      bodyOf(api.POST('/api/itineraries/{id}/reprice', { params: { path: { id } } })),
  });
}

/** One card's re-price: its state and its answer, as the hook gives them. */
export type Reprice = ReturnType<typeof useReprice>;
