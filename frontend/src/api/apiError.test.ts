import { expect, onTestFinished, test, vi } from 'vitest';
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

test('reads a problem whatever the case of its media type', async () => {
  // Media types are case-insensitive (RFC 9110).
  const response = new Response(JSON.stringify({ status: 400, detail: 'origin is not valid' }), {
    status: 400,
    headers: { 'Content-Type': 'Application/Problem+JSON; charset=utf-8' },
  });

  const error = await toApiError(response);

  expect(error.detail).toBe('origin is not valid');
});

test('leaves the detail empty for a problem body it cannot read', async () => {
  // Still an ApiError with its status: a SyntaxError would be retried as a network failure.
  const response = new Response('{"detail": "cut sho', {
    status: 429,
    headers: { 'Content-Type': 'application/problem+json', 'Retry-After': '17' },
  });

  const error = await toApiError(response);

  expect(error.status).toBe(429);
  expect(error.detail).toBeUndefined();
  expect(error.retryAfterSeconds).toBe(17);
});

test('reads a Retry-After given as a date', async () => {
  // RFC 9110 allows an HTTP date as well as seconds; a proxy could send one.
  vi.useFakeTimers({ now: new Date('2026-11-16T10:00:00Z') });
  onTestFinished(() => {
    vi.useRealTimers();
  });
  const response = new Response(null, {
    status: 429,
    headers: { 'Retry-After': 'Mon, 16 Nov 2026 10:00:30 GMT' },
  });

  const error = await toApiError(response);

  expect(error.retryAfterSeconds).toBe(30);
});

test('ignores a Retry-After it cannot read', async () => {
  // The screens then show their sentence without a wait, never "NaN seconds".
  const response = new Response(null, { status: 429, headers: { 'Retry-After': 'soon' } });

  const error = await toApiError(response);

  expect(error.retryAfterSeconds).toBeUndefined();
});

test.each(['', '-5', '1.5'])(
  'ignores a Retry-After of %j, which is not whole seconds',
  async (value) => {
    // `Number` reads "" as 0, and `Date.parse` reads "-5" as a date in 2001: "Try again in 0 seconds".
    const response = new Response(null, { status: 429, headers: { 'Retry-After': value } });

    const error = await toApiError(response);

    expect(error.retryAfterSeconds).toBeUndefined();
  },
);
