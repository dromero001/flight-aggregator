import { ApiError } from '../api/apiError';

/** A signed-in account without the ADMIN role: the API's 403. */
export function lacksAdminRole(error: Error | null): boolean {
  return error instanceof ApiError && error.status === 403;
}

/**
 * What a failed admin call tells the admin: Q7's sentence for a signed-in account without the
 * ADMIN role (the API's 403); a 401 that is still a 401 after the admin client renewed the session
 * means the API refuses the tokens this sign-in gets, which a new sign-in would not change (the
 * user's decision of 2026-11-16); otherwise the screen's own sentence.
 */
export function adminFailure(error: Error, otherwise: string): string {
  if (lacksAdminRole(error)) {
    return 'This account does not have the admin role.';
  }
  if (error instanceof ApiError && error.status === 401) {
    return 'The API refused your session. Sign out and try again.';
  }
  return otherwise;
}
