import { QueryClientProvider } from '@tanstack/react-query';
import { render } from '@testing-library/react';
import type { ReactElement, ReactNode } from 'react';
import { createQueryClient } from '../queryClient';

/** Renders under the app's own query client, a fresh one per call; `rerender` keeps it. */
export function renderWithClient(ui: ReactElement) {
  const client = createQueryClient();
  return render(ui, {
    wrapper: ({ children }: { children: ReactNode }) => (
      <QueryClientProvider client={client}>{children}</QueryClientProvider>
    ),
  });
}
