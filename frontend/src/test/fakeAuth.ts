import { User } from 'oidc-client-ts';
import { vi } from 'vitest';
import type { AuthClient } from '../admin/authClient';

/** A signed-in admin holding `accessToken`, as `oidc-client-ts` returns it. */
export function adminUser(accessToken = 'admin-token'): User {
  return new User({
    access_token: accessToken,
    token_type: 'Bearer',
    profile: {
      sub: 'admin',
      iss: 'http://localhost:8180/realms/flight-aggregator',
      aud: 'dashboard',
      exp: 0,
      iat: 0,
    },
  });
}

/**
 * An `AuthClient` with no session whose calls succeed: the sign-in callback returns `adminUser()`.
 * A test changes what it needs with the mocks' own methods (`mockResolvedValue`, ...).
 */
export function fakeAuth() {
  return {
    getUser: vi.fn<AuthClient['getUser']>(() => Promise.resolve(null)),
    signinRedirect: vi.fn<AuthClient['signinRedirect']>(() => Promise.resolve()),
    signinRedirectCallback: vi.fn<AuthClient['signinRedirectCallback']>(() =>
      Promise.resolve(adminUser()),
    ),
    signinSilent: vi.fn<AuthClient['signinSilent']>(() => Promise.resolve(null)),
    signoutRedirect: vi.fn<AuthClient['signoutRedirect']>(() => Promise.resolve()),
  } satisfies AuthClient;
}
