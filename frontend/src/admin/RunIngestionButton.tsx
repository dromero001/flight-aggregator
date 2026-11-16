import { useMutation, useQueryClient } from '@tanstack/react-query';
import { ApiError } from '../api/apiError';
import { adminFailure } from './adminFailure';
import styles from './StatusPage.module.css';
import { useAdminClient } from './adminClientContext';
import { inventoryStatusKey } from './useInventoryStatus';

/**
 * Why no run started. The API answers 409 only while a run is really going on (plan 5 Task 12),
 * and its detail says so.
 */
function failure(error: Error): string {
  if (error instanceof ApiError && error.status === 409 && error.detail) {
    return error.detail;
  }
  return adminFailure(error, 'The ingestion could not be started. Try again in a moment.');
}

/**
 * "Run ingestion now" (spec 6.6 and 6.8): starts a run in the background (202), then reads the
 * status again; the 10-second refresh shows the run ending.
 */
export function RunIngestionButton() {
  const client = useAdminClient();
  const queryClient = useQueryClient();
  const run = useMutation({
    mutationFn: async () => {
      await client.POST('/api/admin/ingestions');
    },
    // Not awaited: the run has started whether or not the status can be read again at once.
    onSuccess: () => {
      void queryClient.invalidateQueries({ queryKey: inventoryStatusKey });
    },
  });

  return (
    <div className={styles.run}>
      <button
        className={styles.button}
        type="button"
        disabled={run.isPending}
        onClick={() => {
          run.mutate();
        }}
      >
        Run ingestion now
      </button>
      <p role="status">{run.isSuccess && 'Ingestion started.'}</p>
      {run.error && (
        <p className={styles.failure} role="alert">
          {failure(run.error)}
        </p>
      )}
    </div>
  );
}
