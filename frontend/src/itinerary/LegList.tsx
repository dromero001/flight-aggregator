import { useId } from 'react';
import type { Leg } from '../api/types';
import { DayMark } from './DayMark';
import { flight, localTime } from './format';
import styles from './LegList.module.css';

interface LegListProps {
  legs: Leg[];
  /** The list's name; shown above it as its caption when `captioned`. */
  label: string;
  captioned?: boolean;
}

/** Each leg's flight, airports and local times, in flying order (spec 6.8). */
export function LegList({ legs, label, captioned = false }: LegListProps) {
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
