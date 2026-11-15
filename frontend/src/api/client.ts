import createClient, { type Middleware } from 'openapi-fetch';
import { toApiError } from './apiError';
import type { paths } from './schema';

/**
 * Rejects every answer that is not 2xx with an `ApiError`, so queries and mutations fail with it
 * and callers read only `data`. It runs before `openapi-fetch` reads the body, which
 * `toApiError` needs to read itself.
 */
const apiErrors: Middleware = {
  async onResponse({ response }) {
    if (!response.ok) {
      throw await toApiError(response);
    }
    return undefined;
  },
};

/**
 * The public API, on the page's own origin: nginx (or Vite's dev server) proxies /api. The origin
 * is written out because Node's `fetch`, which the component tests run on, rejects a relative URL.
 */
export const api = createClient<paths>({ baseUrl: window.location.origin });
api.use(apiErrors);
