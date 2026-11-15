import '@testing-library/jest-dom/vitest';
import { cleanup } from '@testing-library/react';
import { afterAll, afterEach } from 'vitest';
import { server } from './server';

// Listening here, before the test file is imported, rather than in `beforeAll`: `openapi-fetch`
// keeps the `fetch` it finds when `src/api/client.ts` is first imported, so MSW has to have
// replaced it by then.
server.listen({ onUnhandledFrame: 'error' });
afterEach(() => {
  cleanup();
  server.resetHandlers();
});
afterAll(() => {
  server.close();
});
