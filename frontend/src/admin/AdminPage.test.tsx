import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { expect, test, vi } from 'vitest';
import App from '../App';
import type { AppConfig } from '../config';
import { axe } from '../test/axe';
import { adminUser, fakeAuth } from '../test/fakeAuth';
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

function renderAdmin() {
  window.history.pushState({}, '', '/admin');
  return render(<App />);
}

test('shows a sign-in failure when the settings cannot be read', async () => {
  server.use(http.get('*/config.json', () => HttpResponse.json({ oidcClientId: 'dashboard' })));

  renderAdmin();

  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Sign-in failed. Reload the page to try again.',
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
