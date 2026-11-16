import type { components } from './schema';

type ProblemDetail = components['schemas']['ProblemDetail'];

interface ApiErrorFields {
  status: number;
  detail?: string;
  retryAfterSeconds?: number;
}

/** An API answer other than 2xx, with what the dashboard shows from it. */
export class ApiError extends Error implements ApiErrorFields {
  readonly status: number;
  readonly detail?: string;
  readonly retryAfterSeconds?: number;

  constructor({ status, detail, retryAfterSeconds }: ApiErrorFields) {
    super(detail ?? `HTTP ${String(status)}`);
    this.name = 'ApiError';
    this.status = status;
    this.detail = detail;
    this.retryAfterSeconds = retryAfterSeconds;
  }
}

/**
 * `Retry-After` in seconds: whole seconds, as the API sends it, or an HTTP date (RFC 9110), which a
 * proxy may send instead. Anything else is left out, so no screen shows "NaN seconds".
 */
function retryAfterSeconds(value: string | null): number | undefined {
  if (value === null) return undefined;
  const seconds = Number(value);
  if (!Number.isNaN(seconds)) return seconds;
  const date = Date.parse(value);
  return Number.isNaN(date) ? undefined : Math.max(0, Math.ceil((date - Date.now()) / 1000));
}

/** The body's RFC 9457 problem, or nothing for any other body or one that is not valid JSON. */
async function problemOf(response: Response): Promise<ProblemDetail | undefined> {
  // Media types are case-insensitive (RFC 9110).
  const isProblem = response.headers
    .get('Content-Type')
    ?.toLowerCase()
    .startsWith('application/problem+json');
  if (!isProblem) return undefined;
  try {
    return (await response.json()) as ProblemDetail;
  } catch {
    return undefined;
  }
}

/**
 * Reads an RFC 9457 problem body and `Retry-After`. Any other body, such as nginx's HTML 502 while
 * the app is down, leaves the detail empty.
 */
export async function toApiError(response: Response): Promise<ApiError> {
  const problem = await problemOf(response);
  return new ApiError({
    status: response.status,
    detail: problem?.detail,
    retryAfterSeconds: retryAfterSeconds(response.headers.get('Retry-After')),
  });
}
