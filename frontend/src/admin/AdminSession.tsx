import { useCallback, useEffect, useRef, useState, type ReactNode } from 'react';
import { useLocation, useNavigate } from 'react-router';
import { AdminTokenContext, type AdminToken } from './adminToken';
import type { AuthClient } from './authClient';

interface AdminSessionProps {
  auth: AuthClient;
  children: ReactNode;
}

type SessionState = 'signing-in' | 'signed-in' | 'failed';

/** Keycloak's answer to a sign-in comes back with its `state` and either a `code` or an `error`. */
function isSignInAnswer(params: URLSearchParams): boolean {
  return params.has('state') && (params.has('code') || params.has('error'));
}

/** The sign-in in progress, while the page leaves for Keycloak or comes back from it. */
export function SigningIn() {
  return <p role="status">Signing in…</p>;
}

export function SignInFailed() {
  return <p role="alert">Sign-in failed. Reload the page to try again.</p>;
}

/** Shows its children only to a signed-in admin, starting Keycloak's sign-in when there is none. */
export function AdminSession({ auth, children }: AdminSessionProps) {
  const { pathname, search } = useLocation();
  const navigate = useNavigate();
  const [state, setState] = useState<SessionState>('signing-in');
  // Signing in leaves the page and a sign-in answer can be completed only once, so the effect
  // must not run twice (React's StrictMode runs every effect twice in development).
  const started = useRef(false);
  const adminToken = useCallback<AdminToken>(
    () => auth.getUser().then((user) => user?.access_token ?? null),
    [auth],
  );

  useEffect(() => {
    if (started.current) return;
    started.current = true;

    async function signIn(): Promise<SessionState> {
      if (isSignInAnswer(new URLSearchParams(search))) {
        try {
          await auth.signinRedirectCallback();
          return 'signed-in';
        } finally {
          // The answer is spent either way: a reload must start a new sign-in, not repeat it.
          void navigate(pathname, { replace: true });
        }
      }
      if (await auth.getUser()) {
        return 'signed-in';
      }
      // The page leaves for Keycloak here; it comes back as a new page with the answer.
      await auth.signinRedirect();
      return 'signing-in';
    }

    signIn().then(setState, () => {
      setState('failed');
    });
  }, [auth, navigate, pathname, search]);

  switch (state) {
    case 'signed-in':
      return <AdminTokenContext value={adminToken}>{children}</AdminTokenContext>;
    case 'failed':
      return <SignInFailed />;
    case 'signing-in':
      return <SigningIn />;
  }
}
