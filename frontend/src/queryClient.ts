import { QueryClient } from '@tanstack/react-query';
import { ApiError } from './api/apiError';

/** TanStack Query's own default number of retries. */
const RETRIES = 3;

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      queries: {
        // Only a network error or a 5xx may pass on retrying. A 4xx answers the same again, and
        // retrying a 429 would spend more of the per-address limit (spec 6.6). Mutations keep
        // TanStack's default: no retry.
        retry: (failureCount, error) =>
          failureCount < RETRIES && !(error instanceof ApiError && error.status < 500),
      },
    },
  });
}
