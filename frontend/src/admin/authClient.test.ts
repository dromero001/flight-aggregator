import { UserManager } from 'oidc-client-ts';
import { afterEach, expect, test } from 'vitest';
import type { AppConfig } from '../config';
import { adminUser } from '../test/fakeAuth';
import { createAuthClient } from './authClient';

const CONFIG: AppConfig = {
  oidcAuthority: 'http://localhost:8180/realms/flight-aggregator',
  oidcClientId: 'dashboard',
};

afterEach(() => {
  localStorage.clear();
  sessionStorage.clear();
});

test('signs in with Authorization Code back to /admin, and signs out back there', () => {
  const auth = createAuthClient(CONFIG);

  expect(auth).toBeInstanceOf(UserManager);
  const { settings } = auth as UserManager;
  expect(settings).toMatchObject({
    authority: CONFIG.oidcAuthority,
    client_id: CONFIG.oidcClientId,
    response_type: 'code',
    scope: 'openid',
    redirect_uri: `${window.location.origin}/admin`,
    post_logout_redirect_uri: `${window.location.origin}/admin`,
  });
});

test('keeps the tokens in memory only', async () => {
  // Q2-A: no script can read a stored admin token. The library's default is sessionStorage.
  const auth = createAuthClient(CONFIG) as UserManager;

  await auth.storeUser(adminUser('the-access-token'));

  expect((await auth.getUser())?.access_token).toBe('the-access-token');
  expect(sessionStorage.length).toBe(0);
  expect(localStorage.length).toBe(0);
});
