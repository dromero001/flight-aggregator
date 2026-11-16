import { render, renderHook, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { http, HttpResponse } from 'msw';
import { StrictMode, useState, type ReactNode } from 'react';
import { MemoryRouter, useLocation } from 'react-router';
import { expect, onTestFinished, test, vi } from 'vitest';
import { axe } from '../test/axe';
import { adminUser, fakeAuth } from '../test/fakeAuth';
import { statusReport } from '../test/fixtures';
import { server } from '../test/server';
import type { AuthClient } from './authClient';
import { useAdminClient } from './adminClientContext';
import { useAdminToken } from './adminToken';
import { AdminSession } from './AdminSession';

function Location() {
  const { pathname, search } = useLocation();
  return <p data-testid="location">{pathname + search}</p>;
}

/** In StrictMode, as `main.tsx` renders the app: React then runs every effect twice. */
function renderAt(
  url: string,
  auth: AuthClient,
  screenContent: ReactNode = <p>The admin screen</p>,
) {
  return render(
    <StrictMode>
      <MemoryRouter initialEntries={[url]}>
        <AdminSession auth={auth}>{screenContent}</AdminSession>
        <Location />
      </MemoryRouter>
    </StrictMode>,
  );
}

test('starts the sign-in when there is no session', async () => {
  const auth = fakeAuth();

  renderAt('/admin', auth);

  await waitFor(() => {
    expect(auth.signinRedirect).toHaveBeenCalledOnce();
  });
  expect(screen.getByRole('status')).toHaveTextContent('Signing in…');
  expect(screen.queryByText('The admin screen')).not.toBeInTheDocument();
});

test('completes the sign-in on the way back and cleans the URL', async () => {
  const auth = fakeAuth();

  renderAt('/admin?state=the-state&session_state=the-session&code=the-code', auth);

  expect(await screen.findByText('The admin screen')).toBeInTheDocument();
  expect(auth.signinRedirectCallback).toHaveBeenCalledOnce();
  expect(auth.signinRedirect).not.toHaveBeenCalled();
  expect(screen.getByTestId('location')).toHaveTextContent(/^\/admin$/);
});

test('shows a sign-in failure', async () => {
  // What Keycloak sends back when it refuses the sign-in; the callback then rejects.
  const auth = fakeAuth();
  auth.signinRedirectCallback.mockRejectedValue(new Error('access_denied'));

  renderAt('/admin?error=access_denied&state=the-state', auth);

  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Sign-in failed. Reload the page to try again.',
  );
  expect(auth.signinRedirect).not.toHaveBeenCalled();
  expect(screen.queryByText('The admin screen')).not.toBeInTheDocument();
  // A reload then starts a new sign-in instead of repeating the failed answer.
  expect(screen.getByTestId('location')).toHaveTextContent(/^\/admin$/);
});

test('shows a sign-in failure when Keycloak cannot be reached', async () => {
  // `oidc-client-ts` reads Keycloak's discovery document before it leaves the page.
  const auth = fakeAuth();
  auth.signinRedirect.mockRejectedValue(new TypeError('Failed to fetch'));

  renderAt('/admin', auth);

  expect(await screen.findByRole('alert')).toHaveTextContent(
    'Sign-in failed. Reload the page to try again.',
  );
});

test('shows the admin screen to an admin already signed in', async () => {
  // Coming back to /admin from another screen of the app: the tokens are still in memory.
  const auth = fakeAuth();
  auth.getUser.mockResolvedValue(adminUser());

  renderAt('/admin', auth);

  expect(await screen.findByText('The admin screen')).toBeInTheDocument();
  expect(auth.signinRedirect).not.toHaveBeenCalled();
});

test('gives the admin screen the access token', async () => {
  function TokenProbe() {
    const adminToken = useAdminToken();
    const [token, setToken] = useState<string | null>();
    return (
      <>
        <button
          type="button"
          onClick={() => {
            void adminToken().then(setToken);
          }}
        >
          Read the token
        </button>
        <p data-testid="token">{token}</p>
      </>
    );
  }
  const auth = fakeAuth();
  auth.getUser.mockResolvedValue(adminUser('the-access-token'));
  renderAt('/admin', auth, <TokenProbe />);

  await userEvent.click(await screen.findByRole('button', { name: 'Read the token' }));

  await waitFor(() => {
    expect(screen.getByTestId('token')).toHaveTextContent('the-access-token');
  });
});

test('gives the admin screen the admin client', async () => {
  const seen: (string | null)[] = [];
  server.use(
    http.get('*/api/admin/status', ({ request }) => {
      seen.push(request.headers.get('Authorization'));
      return HttpResponse.json(statusReport());
    }),
  );
  function ClientProbe() {
    const client = useAdminClient();
    const [generation, setGeneration] = useState<number>();
    return (
      <>
        <button
          type="button"
          onClick={() => {
            void client.GET('/api/admin/status').then(({ data }) => {
              setGeneration(data?.generation);
            });
          }}
        >
          Read the status
        </button>
        <p data-testid="generation">{generation}</p>
      </>
    );
  }
  const auth = fakeAuth();
  auth.getUser.mockResolvedValue(adminUser('the-access-token'));
  renderAt('/admin', auth, <ClientProbe />);

  await userEvent.click(await screen.findByRole('button', { name: 'Read the status' }));

  await waitFor(() => {
    expect(screen.getByTestId('generation')).toHaveTextContent('7');
  });
  expect(seen).toEqual(['Bearer the-access-token']);
});

test('refuses the token outside the admin session', () => {
  // React reports the thrown render error on the console as well.
  const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined);
  onTestFinished(() => {
    consoleError.mockRestore();
  });

  expect(() => renderHook(() => useAdminToken())).toThrow(
    'useAdminToken is used outside AdminSession',
  );
});

test('refuses the admin client outside the admin session', () => {
  const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined);
  onTestFinished(() => {
    consoleError.mockRestore();
  });

  expect(() => renderHook(() => useAdminClient())).toThrow(
    'useAdminClient is used outside AdminSession',
  );
});

test('has no accessibility violations while the sign-in fails', async () => {
  const auth = fakeAuth();
  auth.signinRedirect.mockRejectedValue(new TypeError('Failed to fetch'));
  const { container } = renderAt('/admin', auth);
  await screen.findByRole('alert');

  expect(await axe(container)).toHaveNoViolations();
});
