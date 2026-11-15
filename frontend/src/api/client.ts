import createClient from 'openapi-fetch';
import type { paths } from './schema';

/** The public API, on the page's own origin: nginx (or Vite's dev server) proxies /api. */
export const api = createClient<paths>({ baseUrl: '' });
