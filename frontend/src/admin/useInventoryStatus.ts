import { useQuery } from '@tanstack/react-query';
import type { StatusReport } from '../api/types';
import { bodyOf } from '../api/client';
import { useAdminClient } from './adminClientContext';
import { lacksAdminRole } from './adminFailure';

/** How often the status is read again, so a started run is seen ending (plan 6, Q7). */
export const STATUS_REFRESH_MS = 10_000;

export const inventoryStatusKey = ['admin', 'status'] as const;

/**
 * The inventory's and each provider's status (`GET /api/admin/status`), read again every 10 s
 * while the tab is visible: TanStack Query pauses the interval in a background tab by default. An
 * account without the admin role does not gain it by asking again, so its 403 stops the reading.
 */
export function useInventoryStatus() {
  const client = useAdminClient();
  return useQuery<StatusReport>({
    queryKey: inventoryStatusKey,
    queryFn: () => bodyOf(client.GET('/api/admin/status')),
    refetchInterval: (query) => (lacksAdminRole(query.state.error) ? false : STATUS_REFRESH_MS),
  });
}
