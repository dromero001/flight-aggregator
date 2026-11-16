import { QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import type { ReactElement } from 'react';
import { MemoryRouter } from 'react-router';
import { AdminSession } from '../admin/AdminSession';
import { createQueryClient } from '../queryClient';
import { adminUser, fakeAuth } from './fakeAuth';

/**
 * Renders an admin screen at `/admin` for an admin already signed in, under the app's own query
 * client. Retries keep the app's rule but not its wait, so a test does not sleep through them.
 */
export function renderAsAdmin(ui: ReactElement) {
  const queryClient = createQueryClient();
  const defaults = queryClient.getDefaultOptions();
  queryClient.setDefaultOptions({ ...defaults, queries: { ...defaults.queries, retryDelay: 0 } });
  const auth = fakeAuth();
  auth.getUser.mockResolvedValue(adminUser());
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={['/admin']}>
        <AdminSession auth={auth}>{ui}</AdminSession>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}
