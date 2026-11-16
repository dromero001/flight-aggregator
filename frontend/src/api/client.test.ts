import { http, HttpResponse } from 'msw';
import { expect, test } from 'vitest';
import { adminUser, fakeAuth } from '../test/fakeAuth';
import { statusReport } from '../test/fixtures';
import { server } from '../test/server';
import { adminClient, api } from './client';

const STATUS = '*/api/admin/status';
const REPORT = statusReport();

/** Answers every status call as `answer` says and records the `Authorization` header of each one. */
function recordAuthorization(answer: (authorization: string | null) => Response) {
  const seen: (string | null)[] = [];
  server.use(
    http.get(STATUS, ({ request }) => {
      const authorization = request.headers.get('Authorization');
      seen.push(authorization);
      return answer(authorization);
    }),
  );
  return seen;
}

test('sends the token only to admin calls', async () => {
  const seen = recordAuthorization(() => HttpResponse.json(REPORT));
  const auth = fakeAuth();
  auth.getUser.mockResolvedValue(adminUser('the-access-token'));

  // The public client sends none, even to an admin path.
  await api.GET('/api/admin/status');
  await adminClient(auth).GET('/api/admin/status');

  expect(seen).toEqual([null, 'Bearer the-access-token']);
});

test('renews the session once when an admin call answers 401', async () => {
  // Review Focus 5: the access token expired while the admin screen was open.
  const seen = recordAuthorization((authorization) =>
    authorization === 'Bearer renewed-token'
      ? HttpResponse.json(REPORT)
      : new HttpResponse(null, { status: 401 }),
  );
  const auth = fakeAuth();
  auth.getUser.mockResolvedValue(adminUser('expired-token'));
  auth.signinSilent.mockResolvedValue(adminUser('renewed-token'));

  const { data } = await adminClient(auth).GET('/api/admin/status');

  expect(data).toEqual(REPORT);
  expect(auth.signinSilent).toHaveBeenCalledOnce();
  expect(seen).toEqual(['Bearer expired-token', 'Bearer renewed-token']);
  expect(auth.signinRedirect).not.toHaveBeenCalled();
});

test('signs in again when the renewal fails', async () => {
  // Keycloak's session ended too, so the refresh token is refused.
  const seen = recordAuthorization(() => new HttpResponse(null, { status: 401 }));
  const auth = fakeAuth();
  auth.getUser.mockResolvedValue(adminUser('expired-token'));
  auth.signinSilent.mockRejectedValue(new Error('invalid_grant'));

  await expect(adminClient(auth).GET('/api/admin/status')).rejects.toMatchObject({ status: 401 });

  expect(auth.signinRedirect).toHaveBeenCalledOnce();
  expect(seen).toEqual(['Bearer expired-token']);
});

test('answers 401 without signing in again when the renewed token is refused too', async () => {
  // The API does not accept this issuer's tokens (a wrong issuer or signing keys, a clock ahead):
  // a new sign-in would get the same kind of token, and Keycloak's session would complete it
  // without asking, so signing in again would loop (the user's decision of 2026-11-16).
  const seen = recordAuthorization(() => new HttpResponse(null, { status: 401 }));
  const auth = fakeAuth();
  auth.getUser.mockResolvedValue(adminUser('expired-token'));
  auth.signinSilent.mockResolvedValue(adminUser('renewed-token'));

  await expect(adminClient(auth).GET('/api/admin/status')).rejects.toMatchObject({ status: 401 });

  expect(auth.signinSilent).toHaveBeenCalledOnce();
  expect(seen).toEqual(['Bearer expired-token', 'Bearer renewed-token']);
  expect(auth.signinRedirect).not.toHaveBeenCalled();
});
