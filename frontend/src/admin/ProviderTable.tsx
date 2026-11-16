import type { ProviderStatus } from '../api/types';
import { timeAgo } from '../time/relative';
import { label } from './labels';
import styles from './StatusPage.module.css';

/** The colour repeating each health's word; "Degraded" keeps the text's own. */
const HEALTH_CLASS: Record<ProviderStatus['health'], string | undefined> = {
  UP: styles.up,
  DEGRADED: undefined,
  DOWN: styles.down,
};

/** The total, then each reason with its count ("Unknown airport: 3"). */
function Discarded({ discarded }: { discarded: ProviderStatus['discarded'] }) {
  const reasons = Object.entries(discarded);
  const total = reasons.reduce((sum, [, count]) => sum + count, 0);
  return (
    <>
      {total}
      {reasons.length > 0 && (
        <ul className={styles.reasons}>
          {reasons.map(([reason, count]) => (
            <li key={reason}>
              {label(reason)}: {count}
            </li>
          ))}
        </ul>
      )}
    </>
  );
}

interface ProviderTableProps {
  providers: ProviderStatus[];
  now: Date;
}

/** One row per provider, as the last ingestion left it (spec 6.8). */
export function ProviderTable({ providers, now }: ProviderTableProps) {
  return (
    <table className={styles.table}>
      <caption>Providers</caption>
      <thead>
        <tr>
          <th scope="col">Provider</th>
          <th scope="col">Health</th>
          <th scope="col">Circuit breaker</th>
          <th scope="col">Last successful ingestion</th>
          <th scope="col">Legs</th>
          <th scope="col">Discarded records</th>
        </tr>
      </thead>
      <tbody>
        {providers.map((status) => (
          <tr key={status.provider}>
            <th scope="row">{label(status.provider)}</th>
            <td className={HEALTH_CLASS[status.health]}>{label(status.health)}</td>
            <td>{label(status.breaker)}</td>
            <td>{status.newestQuotedAt ? timeAgo(status.newestQuotedAt, now) : 'Never'}</td>
            <td>
              {status.legs}
              {/* Its last download failed, so its legs are the previous run's (spec 6.8). */}
              {status.stale && <p className={styles.note}>Kept from the last good ingestion</p>}
            </td>
            <td>
              <Discarded discarded={status.discarded} />
            </td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}
