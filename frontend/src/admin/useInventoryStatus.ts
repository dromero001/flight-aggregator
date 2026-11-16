import { useQuery } from '@tanstack/react-query';
import type { StatusReport } from '../api/types';
import { useAdminClient } from './adminClientContext';

/** How often the status is read again, so a started run is seen ending (plan 6, Q7). */
export const STATUS_REFRESH_MS = 10_000;

export const inventoryStatusKey = ['admin', 'status'] as const;

/**
 * The inventory's and each provider's status (`GET /api/admin/status`), read again every 10 s
 * while the tab is visible: TanStack Query pauses the interval in a background tab by default.
 */
export function useInventoryStatus() {
  const client = useAdminClient();
  return useQuery<StatusReport>({
    queryKey: inventoryStatusKey,
    queryFn: async () => {
      const { data } = await client.GET('/api/admin/status');
      if (data === undefined) {
        throw new Error('The status answered without a body');
      }
      return data;
    },
    refetchInterval: STATUS_REFRESH_MS,
  });
}
