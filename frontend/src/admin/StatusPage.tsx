import type { StatusReport } from '../api/types';
import { minutesUntil, timeAgo } from '../time/relative';
import { useNow } from '../time/useNow';
import { adminFailure } from './adminFailure';
import { ProviderTable } from './ProviderTable';
import { RunIngestionButton } from './RunIngestionButton';
import styles from './StatusPage.module.css';
import { STATUS_REFRESH_MS, useInventoryStatus } from './useInventoryStatus';

/** "18 min", or "1 h 30 min" and "2 h" from an hour on. */
function hoursAndMinutes(minutes: number): string {
  if (minutes < 60) {
    return `${String(minutes)} min`;
  }
  const hours = `${String(Math.floor(minutes / 60))} h`;
  return minutes % 60 === 0 ? hours : `${hours} ${String(minutes % 60)} min`;
}

/** "Next ingestion in 18 min", or "Next ingestion due" once its time has come (plan 6, Q7). */
function nextIngestion(at: string, now: Date): string {
  const minutes = minutesUntil(at, now);
  return minutes > 0 ? `Next ingestion in ${hoursAndMinutes(minutes)}` : 'Next ingestion due';
}

/**
 * Why the status is missing. Anything but a missing role may pass by the next refresh, which
 * TanStack Query keeps running after a failure.
 */
function failure(error: Error): string {
  return adminFailure(
    error,
    `Provider status could not be loaded. Retrying every ${String(STATUS_REFRESH_MS / 1000)} seconds.`,
  );
}

/** The inventory's generation and schedule, and the providers as the last run left them. */
function Report({ report, now }: { report: StatusReport; now: Date }) {
  // The API schedules the next run from the end of the last one: without it, none has ended.
  if (report.nextIngestionAt === null) {
    return (
      <p>{report.ingestionRunning ? 'Ingestion in progress.' : 'No ingestion has run yet.'}</p>
    );
  }
  return (
    <>
      <ul className={styles.facts}>
        <li>Generation {report.generation}</li>
        {report.publishedAt && <li>Published {timeAgo(report.publishedAt, now)}</li>}
        <li>
          {report.ingestionRunning
            ? 'Ingestion in progress'
            : nextIngestion(report.nextIngestionAt, now)}
        </li>
      </ul>
      <ProviderTable providers={report.providers} now={now} />
    </>
  );
}

/** The provider-status screen (spec 6.8), for a signed-in admin. */
export function StatusPage() {
  const status = useInventoryStatus();
  const now = useNow(STATUS_REFRESH_MS);

  return (
    <div className={styles.page}>
      <h1>Provider status</h1>
      {status.error && (
        <p className={styles.failure} role="alert">
          {failure(status.error)}
        </p>
      )}
      <RunIngestionButton />
      {status.isLoading && <p role="status">Loading…</p>}
      {status.data && <Report report={status.data} now={now} />}
    </div>
  );
}
