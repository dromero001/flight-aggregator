import { useQuery } from '@tanstack/react-query';
import { useEffect, useState } from 'react';
import { loadConfig, type AppConfig } from '../config';
import { createAuthClient } from './authClient';
import { AdminSession, SignInFailed, SigningIn } from './AdminSession';
import { StatusPage } from './StatusPage';

/** The admin screen (`/admin`): reads the sign-in settings, then signs the admin in. */
export function AdminPage() {
  // A static file, read once per page: failing to read it is a deployment fault that trying again
  // will not fix, and a later read (on focus, on reconnect) that fails must not end a signed-in
  // screen, which holds the tokens in memory.
  const config = useQuery({
    queryKey: ['config'],
    queryFn: loadConfig,
    retry: false,
    staleTime: Infinity,
  });

  useEffect(() => {
    // The screen says only that the sign-in failed; the console says why (a missing setting).
    if (config.error) console.error(config.error);
  }, [config.error]);

  if (config.isError) {
    return <SignInFailed />;
  }
  if (!config.data) {
    return <SigningIn />;
  }
  return <AdminScreen config={config.data} />;
}

function AdminScreen({ config }: { config: AppConfig }) {
  // One client for the screen's life: it holds the tokens in memory.
  const [auth] = useState(() => createAuthClient(config));

  return (
    <AdminSession auth={auth}>
      <StatusPage />
      {/* Ends Keycloak's session as well, which then sends the browser back to /admin. */}
      <button
        type="button"
        onClick={() => {
          void auth.signoutRedirect();
        }}
      >
        Sign out
      </button>
    </AdminSession>
  );
}
