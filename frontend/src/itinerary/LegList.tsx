import { useId } from 'react';
import type { Leg } from '../api/types';
import { DayMark } from './DayMark';
import { daysLater, flight, localDate, localTime } from './format';
import styles from './LegList.module.css';

interface LegListProps {
  legs: Leg[];
  /** The list's name; shown above it as its caption when `captioned`. */
  label: string;
  captioned?: boolean;
  /**
   * A departure whose date the reader already knows (for an alternative, the chosen itinerary's): a
   * leg that leaves on another local date shows its date before its time. Without it, times only.
   */
  datedFrom?: string;
}

/** Each leg's flight, airports and local times, in flying order (spec 6.8). */
export function LegList({ legs, label, captioned = false, datedFrom }: LegListProps) {
  const captionId = useId();
  return (
    <>
      {captioned && (
        <p id={captionId} className={styles.caption}>
          {label}
        </p>
      )}
      <ol
        className={styles.legs}
        aria-label={captioned ? undefined : label}
        aria-labelledby={captioned ? captionId : undefined}
      >
        {/* A flight may come back in one itinerary, so the position is the key. */}
        {legs.map((leg, index) => (
          <li key={index}>
            <span className={styles.flight}>{flight(leg)}</span> {leg.origin}{' '}
            {datedFrom !== undefined && daysLater(datedFrom, leg.departure) !== 0 && (
              <>{localDate(leg.departure)} </>
            )}
            <time dateTime={leg.departure}>{localTime(leg.departure)}</time> to {leg.destination}{' '}
            <time dateTime={leg.arrival}>{localTime(leg.arrival)}</time>{' '}
            {/* "+1 day": the leg lands on a later local date than it took off, as on the card. */}
            <DayMark departure={leg.departure} arrival={leg.arrival} />
          </li>
        ))}
      </ol>
    </>
  );
}
