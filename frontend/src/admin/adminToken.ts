import { createContext, useContext } from 'react';

/** Reads the signed-in admin's current access token, renewed tokens included. */
export type AdminToken = () => Promise<string | null>;

/** Provided by `AdminSession` once the admin is signed in. */
export const AdminTokenContext = createContext<AdminToken | null>(null);

/** The admin's access token, for the screens inside `AdminSession`. */
export function useAdminToken(): AdminToken {
  const adminToken = useContext(AdminTokenContext);
  if (!adminToken) {
    throw new Error('useAdminToken is used outside AdminSession');
  }
  return adminToken;
}
