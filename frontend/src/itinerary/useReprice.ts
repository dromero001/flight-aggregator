import { useMutation } from '@tanstack/react-query';
import { api } from '../api/client';
import type { RepriceResponse } from '../api/types';

/**
 * Confirms a chosen itinerary's price with the providers (spec 6.5), by its id. A failure is the
 * client's `ApiError` for an HTTP answer, or the `fetch` error when none came.
 */
export function useReprice() {
  return useMutation<RepriceResponse, Error, string>({
    mutationFn: async (id) => {
      const { data } = await api.POST('/api/itineraries/{id}/reprice', {
        params: { path: { id } },
      });
      if (data === undefined) {
        throw new Error('The re-price answered without a body');
      }
      return data;
    },
  });
}

/** One card's re-price: its state and its answer, as the hook gives them. */
export type Reprice = ReturnType<typeof useReprice>;
