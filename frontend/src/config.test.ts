import { http, HttpResponse } from 'msw';
import { expect, test } from 'vitest';
import { loadConfig } from './config';
import { server } from './test/server';

const CONFIG = '*/config.json';

test('loads the sign-in settings', async () => {
  server.use(
    http.get(CONFIG, () =>
      HttpResponse.json({
        oidcAuthority: 'http://localhost:8180/realms/flight-aggregator',
        oidcClientId: 'dashboard',
      }),
    ),
  );

  await expect(loadConfig()).resolves.toEqual({
    oidcAuthority: 'http://localhost:8180/realms/flight-aggregator',
    oidcClientId: 'dashboard',
  });
});

test('names a missing setting', async () => {
  server.use(
    http.get(CONFIG, () =>
      HttpResponse.json({ oidcAuthority: 'http://localhost:8180/realms/flight-aggregator' }),
    ),
  );

  await expect(loadConfig()).rejects.toThrow('/config.json has no oidcClientId');
});

test('names an empty setting', async () => {
  server.use(
    http.get(CONFIG, () => HttpResponse.json({ oidcAuthority: '', oidcClientId: 'dashboard' })),
  );

  await expect(loadConfig()).rejects.toThrow('/config.json has no oidcAuthority');
});
