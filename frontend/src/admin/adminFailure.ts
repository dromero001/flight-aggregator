import { ApiError } from '../api/apiError';

/**
 * What a failed admin call tells the admin: Q7's sentence for a signed-in account without the
 * ADMIN role (the API's 403), otherwise the screen's own sentence. A 401 never gets here as such:
 * the admin client has renewed the session or sent the browser back to the sign-in.
 */
export function adminFailure(error: Error, otherwise: string): string {
  if (error instanceof ApiError && error.status === 403) {
    return 'This account does not have the admin role.';
  }
  return otherwise;
}
