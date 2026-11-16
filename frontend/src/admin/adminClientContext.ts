import { createContext, useContext } from 'react';
import type { Client } from 'openapi-fetch';
import type { paths } from '../api/schema';

/** Provided by `AdminSession` once the admin is signed in: `adminClient` over its `AuthClient`. */
export const AdminClientContext = createContext<Client<paths> | null>(null);

/** The admin API with the signed-in admin's token, for the screens inside `AdminSession`. */
export function useAdminClient(): Client<paths> {
  const client = useContext(AdminClientContext);
  if (!client) {
    throw new Error('useAdminClient is used outside AdminSession');
  }
  return client;
}
