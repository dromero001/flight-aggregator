import { expect, test } from 'vitest';
import { ApiError, toApiError } from './apiError';

test('reads the problem detail and Retry-After', async () => {
  const response = new Response(
    JSON.stringify({
      type: 'about:blank',
      title: 'Too Many Requests',
      status: 429,
      detail: 'Too many requests from this address.',
    }),
    {
      status: 429,
      headers: { 'Content-Type': 'application/problem+json', 'Retry-After': '17' },
    },
  );

  const error = await toApiError(response);

  expect(error).toBeInstanceOf(ApiError);
  expect(error.status).toBe(429);
  expect(error.detail).toBe('Too many requests from this address.');
  expect(error.retryAfterSeconds).toBe(17);
});

test('leaves the detail empty for a body that is not a problem', async () => {
  // What nginx answers while the app is down.
  const response = new Response('<html><body><h1>502 Bad Gateway</h1></body></html>', {
    status: 502,
    headers: { 'Content-Type': 'text/html' },
  });

  const error = await toApiError(response);

  expect(error.status).toBe(502);
  expect(error.detail).toBeUndefined();
  expect(error.retryAfterSeconds).toBeUndefined();
});
