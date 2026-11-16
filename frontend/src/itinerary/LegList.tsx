import type { Leg } from '../api/types';
import { flight, laterDayMark, localTime } from './format';
import styles from './LegList.module.css';

/** Each leg's flight, airports and local times, in flying order (spec 6.8). */
export function LegList({ legs, label }: { legs: Leg[]; label: string }) {
  return (
    <ol className={styles.legs} aria-label={label}>
      {/* A flight may come back in one itinerary, so the position is the key. */}
      {legs.map((leg, index) => {
        // "+1": the leg lands on a later local date than it took off, as on the card.
        const arrivalMark = laterDayMark(leg.departure, leg.arrival);
        return (
          <li key={index}>
            <span className={styles.flight}>{flight(leg)}</span> {leg.origin}{' '}
            <time dateTime={leg.departure}>{localTime(leg.departure)}</time> to {leg.destination}{' '}
            <time dateTime={leg.arrival}>{localTime(leg.arrival)}</time>
            {arrivalMark && ` ${arrivalMark}`}
          </li>
        );
      })}
    </ol>
  );
}
