import { act, render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { focusManager } from '@tanstack/react-query';
import { http, HttpResponse } from 'msw';
import { beforeEach, expect, onTestFinished, test, vi } from 'vitest';
import App from '../App';
import type { AppConfig } from '../config';
import { axe } from '../test/axe';
import { adminUser, fakeAuth } from '../test/fakeAuth';
import { statusReport } from '../test/fixtures';
import { server } from '../test/server';
import type { AuthClient } from './authClient';

// The real client would leave the page for Keycloak, which jsdom cannot follow; the end-to-end
// admin test signs in for real (plan 6 Task 10).
const { createAuthClient } = vi.hoisted(() => ({
  createAuthClient: vi.fn<(config: AppConfig) => AuthClient>(),
}));
vi.mock('./authClient', () => ({ createAuthClient }));

const CONFIG: AppConfig = {
  oidcAuthority: 'http://localhost:8180/realms/flight-aggregator',
  oidcClientId: 'dashboard',
};

beforeEach(() => {
  // The status screen reads the status as soon as the admin is signed in.
  server.use(http.get('*/api/admin/status', () => HttpResponse.json(statusReport())));
});

function renderAdmin() {
  window.history.pushState({}, '', '/admin');
  return render(<App />);
}

test('shows a sign-in failure when the settings cannot be read', async () => {
  // The cause goes to the console as well ('logs which setting is missing').
  const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined);
  onTestFinished(() => {
    consoleError.mockRestore();
  });
  server.use(http.get('*/config.json', () => HttpResponse.json({ oidcClientId: 'dashboard' })));

  renderAdmin();

  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Sign-in failed. Reload the page to try again.',
  );
});

test('logs which setting is missing', async () => {
  // Only the console can say which: the image refuses such a value at start, so only
  // `npm run dev` with an edited public/config.json meets it.
  const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined);
  onTestFinished(() => {
    consoleError.mockRestore();
  });
  server.use(http.get('*/config.json', () => HttpResponse.json({ oidcClientId: 'dashboard' })));

  renderAdmin();

  await screen.findByRole('alert');
  expect(consoleError).toHaveBeenCalledWith(
    expect.objectContaining({ message: '/config.json has no oidcAuthority' }),
  );
});

test('signs in with the served settings', async () => {
  server.use(http.get('*/config.json', () => HttpResponse.json(CONFIG)));
  const auth = fakeAuth();
  auth.getUser.mockResolvedValue(adminUser());
  createAuthClient.mockReturnValue(auth);

  renderAdmin();

  expect(
    await screen.findByRole('heading', { level: 1, name: 'Provider status' }),
  ).toBeInTheDocument();
  expect(createAuthClient).toHaveBeenCalledWith(CONFIG);
});

test('shows the provider status once signed in', async () => {
  server.use(http.get('*/config.json', () => HttpResponse.json(CONFIG)));
  const auth = fakeAuth();
  auth.getUser.mockResolvedValue(adminUser());
  createAuthClient.mockReturnValue(auth);

  renderAdmin();

  expect(await screen.findByRole('table', { name: 'Providers' })).toBeInTheDocument();
  expect(screen.getByRole('button', { name: 'Run ingestion now' })).toBeInTheDocument();
});

test('keeps the admin screen when the window regains focus', async () => {
  // The settings were read once; a later read that fails (nginx restarting) must not matter.
  let configReads = 0;
  server.use(
    http.get('*/config.json', () => {
      configReads += 1;
      return configReads === 1 ? HttpResponse.json(CONFIG) : HttpResponse.error();
    }),
  );
  const auth = fakeAuth();
  auth.getUser.mockResolvedValue(adminUser());
  createAuthClient.mockReturnValue(auth);
  renderAdmin();
  await screen.findByRole('table', { name: 'Providers' });

  act(() => {
    focusManager.setFocused(false);
    focusManager.setFocused(true);
  });

  expect(await screen.findByRole('table', { name: 'Providers' })).toBeInTheDocument();
  expect(
    screen.queryByText('Sign-in failed. Reload the page to try again.'),
  ).not.toBeInTheDocument();
  expect(configReads).toBe(1);
  act(() => {
    focusManager.setFocused(undefined);
  });
});

test('says when the API refuses the session, without signing in again', async () => {
  // The renewed token is refused too: the API does not accept this issuer's tokens, and a new
  // sign-in would loop through Keycloak's session (the user's decision of 2026-11-16).
  server.use(
    http.get('*/config.json', () => HttpResponse.json(CONFIG)),
    http.get('*/api/admin/status', () => new HttpResponse(null, { status: 401 })),
  );
  const auth = fakeAuth();
  auth.getUser.mockResolvedValue(adminUser('refused-token'));
  auth.signinSilent.mockResolvedValue(adminUser('renewed-token'));
  createAuthClient.mockReturnValue(auth);

  renderAdmin();

  expect(await screen.findByRole('alert')).toHaveTextContent(
    'The API refused your session. Sign out and try again.',
  );
  expect(screen.getByRole('button', { name: 'Sign out' })).toBeInTheDocument();
  expect(auth.signinRedirect).not.toHaveBeenCalled();
});

test('signs out through Keycloak', async () => {
  // E4: ends Keycloak's session too, so the next visit asks for the password again.
  server.use(http.get('*/config.json', () => HttpResponse.json(CONFIG)));
  const auth = fakeAuth();
  auth.getUser.mockResolvedValue(adminUser());
  createAuthClient.mockReturnValue(auth);
  renderAdmin();

  await userEvent.click(await screen.findByRole('button', { name: 'Sign out' }));

  expect(auth.signoutRedirect).toHaveBeenCalledOnce();
});

test('has no accessibility violations once signed in', async () => {
  server.use(http.get('*/config.json', () => HttpResponse.json(CONFIG)));
  const auth = fakeAuth();
  auth.getUser.mockResolvedValue(adminUser());
  createAuthClient.mockReturnValue(auth);
  const { container } = renderAdmin();
  await screen.findByRole('heading', { level: 1, name: 'Provider status' });

  expect(await axe(container)).toHaveNoViolations();
});
