import { QueryClient } from '@tanstack/react-query';
import { ApiError } from './api/apiError';

/** TanStack Query's own default number of retries. */
const RETRIES = 3;

/**
 * Only a network error or a 5xx may pass on retrying. A 4xx answers the same again, and retrying a
 * 429 would spend more of the per-address limit (spec 6.6); a 5xx with `Retry-After` (a 503) says
 * it will not pass before then.
 */
function mayPassOnRetrying(error: Error): boolean {
  return (
    !(error instanceof ApiError) || (error.status >= 500 && error.retryAfterSeconds === undefined)
  );
}

export function createQueryClient(): QueryClient {
  return new QueryClient({
    defaultOptions: {
      // Mutations keep TanStack's default: no retry.
      queries: {
        retry: (failureCount, error) => failureCount < RETRIES && mayPassOnRetrying(error),
      },
    },
  });
}
