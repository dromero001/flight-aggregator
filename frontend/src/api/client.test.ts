import { http, HttpResponse } from 'msw';
import { expect, test } from 'vitest';
import { adminUser, fakeAuth } from '../test/fakeAuth';
import { server } from '../test/server';
import { adminClient, api } from './client';

// The admin client's middleware does not look at the path. The committed contract has no admin
// path until plan 5 Task 12, so these tests call the one path it has.
const ANY_PATH = '*/api/itineraries';
const EMPTY_PAGE = { itineraries: [], nextCursor: null, inventoryChanged: false };

/** Answers every call with an empty page and records the `Authorization` header of each one. */
function recordAuthorization(answer: (authorization: string | null) => Response) {
  const seen: (string | null)[] = [];
  server.use(
    http.get(ANY_PATH, ({ request }) => {
      const authorization = request.headers.get('Authorization');
      seen.push(authorization);
      return answer(authorization);
    }),
  );
  return seen;
}

test('sends the token only to admin calls', async () => {
  const seen = recordAuthorization(() => HttpResponse.json(EMPTY_PAGE));
  const auth = fakeAuth();
  auth.getUser.mockResolvedValue(adminUser('the-access-token'));

  await api.GET('/api/itineraries');
  await adminClient(auth).GET('/api/itineraries');

  expect(seen).toEqual([null, 'Bearer the-access-token']);
});

test('renews the session once when an admin call answers 401', async () => {
  // Review Focus 5: the access token expired while the admin screen was open.
  const seen = recordAuthorization((authorization) =>
    authorization === 'Bearer renewed-token'
      ? HttpResponse.json(EMPTY_PAGE)
      : new HttpResponse(null, { status: 401 }),
  );
  const auth = fakeAuth();
  auth.getUser.mockResolvedValue(adminUser('expired-token'));
  auth.signinSilent.mockResolvedValue(adminUser('renewed-token'));

  const { data } = await adminClient(auth).GET('/api/itineraries');

  expect(data).toEqual(EMPTY_PAGE);
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

  await expect(adminClient(auth).GET('/api/itineraries')).rejects.toMatchObject({ status: 401 });

  expect(auth.signinRedirect).toHaveBeenCalledOnce();
  expect(seen).toEqual(['Bearer expired-token']);
});

test('signs in again when the renewed token is refused too, without a loop', async () => {
  const seen = recordAuthorization(() => new HttpResponse(null, { status: 401 }));
  const auth = fakeAuth();
  auth.getUser.mockResolvedValue(adminUser('expired-token'));
  auth.signinSilent.mockResolvedValue(adminUser('renewed-token'));

  await expect(adminClient(auth).GET('/api/itineraries')).rejects.toMatchObject({ status: 401 });

  expect(auth.signinSilent).toHaveBeenCalledOnce();
  expect(seen).toEqual(['Bearer expired-token', 'Bearer renewed-token']);
  expect(auth.signinRedirect).toHaveBeenCalledOnce();
});
