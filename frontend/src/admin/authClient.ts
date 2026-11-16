import { InMemoryWebStorage, UserManager, WebStorageStateStore, type User } from 'oidc-client-ts';
import type { AppConfig } from '../config';

/** The part of `oidc-client-ts`'s `UserManager` the dashboard uses, so tests can fake it. */
export interface AuthClient {
  getUser(): Promise<User | null>;
  signinRedirect(): Promise<void>;
  signinRedirectCallback(): Promise<User>;
  signinSilent(): Promise<User | null>;
}

/**
 * Keycloak's sign-in through standard OIDC (plan 6, Q2-A), so any other provider fits by
 * configuration: Authorization Code with PKCE (`oidc-client-ts` always sends an `S256`
 * challenge), back to `/admin`. The tokens stay in memory, where no other script can read them;
 * a reload passes back through Keycloak's own session without the password. Only the PKCE
 * verifier is kept in `localStorage` (the library's default), to survive the trip to Keycloak.
 */
export function createAuthClient(config: AppConfig): AuthClient {
  return new UserManager({
    authority: config.oidcAuthority,
    client_id: config.oidcClientId,
    redirect_uri: `${window.location.origin}/admin`,
    response_type: 'code',
    scope: 'openid',
    userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }),
  });
}
