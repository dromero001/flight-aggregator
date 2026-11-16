import createClient, { type Client, type Middleware } from 'openapi-fetch';
import type { AuthClient } from '../admin/authClient';
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
 * The page's own origin: nginx (or Vite's dev server) proxies /api. It is written out because
 * Node's `fetch`, which the component tests run on, rejects a relative URL.
 */
const baseUrl = window.location.origin;

/** The public API. It never sends a token. */
export const api = createClient<paths>({ baseUrl });
api.use(apiErrors);

function withToken(request: Request, accessToken: string): Request {
  // A new request, so that a repeat does not reuse the one already sent. The admin calls carry
  // no body; one already sent could not be copied, and the copy would throw.
  const authorized = new Request(request);
  authorized.headers.set('Authorization', `Bearer ${accessToken}`);
  return authorized;
}

/**
 * Sends the signed-in admin's access token. A 401 means it expired (Keycloak's lasts 5 minutes):
 * the session is renewed once and the call repeated once with the new token.
 */
function bearerToken(auth: AuthClient): Middleware {
  return {
    async onRequest({ request }) {
      const user = await auth.getUser();
      return user ? withToken(request, user.access_token) : undefined;
    },
    async onResponse({ request, response, options }) {
      if (response.status !== 401) {
        return undefined;
      }
      const renewed = await auth.signinSilent().catch(() => null);
      if (!renewed) {
        // Keycloak's own session ended: one more sign-in. The page leaves for Keycloak.
        await auth.signinRedirect();
        return undefined;
      }
      // A renewed token refused too means the API does not accept this issuer's tokens: a new
      // sign-in would get the same kind of token without asking for anything, and loop. The 401
      // goes to the screen instead (the user's decision of 2026-11-16).
      return options.fetch(withToken(request, renewed.access_token));
    },
  };
}

/**
 * The admin API (`/api/admin/**`), with the admin's token on every call. `openapi-fetch` runs
 * `onResponse` in reverse order of registration, so `apiErrors` comes first: the token's
 * middleware sees a 401 before it is thrown as an `ApiError`.
 */
export function adminClient(auth: AuthClient): Client<paths> {
  const client = createClient<paths>({ baseUrl });
  client.use(apiErrors, bearerToken(auth));
  return client;
}
