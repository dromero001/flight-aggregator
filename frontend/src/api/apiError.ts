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
 * Reads an RFC 9457 problem body and `Retry-After` (whole seconds, as the API sends it). Any other
 * body, such as nginx's HTML 502 while the app is down, leaves the detail empty.
 */
export async function toApiError(response: Response): Promise<ApiError> {
  const isProblem = response.headers.get('Content-Type')?.startsWith('application/problem+json');
  const problem = isProblem ? ((await response.json()) as ProblemDetail) : undefined;
  const retryAfter = response.headers.get('Retry-After');
  return new ApiError({
    status: response.status,
    detail: problem?.detail,
    retryAfterSeconds: retryAfter === null ? undefined : Number(retryAfter),
  });
}
