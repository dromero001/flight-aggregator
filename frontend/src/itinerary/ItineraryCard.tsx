import type { Itinerary } from '../api/types';
import styles from './ItineraryCard.module.css';
import {
  duration,
  freshness,
  isMultiAirline,
  layovers,
  localDate,
  localTime,
  price,
  type Layover,
} from './format';

interface ItineraryCardProps {
  itinerary: Itinerary;
  now: Date;
}

/** "Direct", "1 stop: LHR, 1h 10m", "2 stops: LHR, 1h 10m; JFK, 1h 20m" (Q6). */
function stopsSummary(connections: Layover[]): string {
  if (connections.length === 0) {
    return 'Direct';
  }
  const count = connections.length === 1 ? '1 stop' : `${String(connections.length)} stops`;
  const where = connections
    .map((connection) => `${connection.airport}, ${duration(connection.minutes)}`)
    .join('; ');
  return `${count}: ${where}`;
}

/**
 * One result, read like a boarding pass (plan 6, Q6): the times and airports on a route line,
 * then the date, duration, stops and flights; the price and its freshness on the stub.
 */
export function ItineraryCard({ itinerary, now }: ItineraryCardProps) {
  const { origin, destination, departure, arrival, legs } = itinerary;
  const connections = layovers(legs);

  return (
    <article className={styles.card}>
      <div className={styles.pass}>
        <span className={styles.route}>
          <time className={styles.time} dateTime={departure}>
            {localTime(departure)}
          </time>
          <span className={styles.code}>{origin}</span>
          <span className={styles.line} aria-hidden="true">
            {/* An airport may come back in one itinerary, so the position is the key. */}
            {connections.map((_, index) => (
              <span key={index} className={styles.stop} />
            ))}
          </span>
          <span className={styles.hidden}> to </span>
          <span className={styles.code}>{destination}</span>
          <time className={styles.time} dateTime={arrival}>
            {localTime(arrival)}
          </time>
        </span>
        <span className={styles.details}>
          <time dateTime={departure}>{localDate(departure)}</time>
          <span>{duration(itinerary.durationMinutes)}</span>
          <span>{stopsSummary(connections)}</span>
        </span>
        <span className={styles.flights}>
          <span>{legs.map((leg) => `${leg.carrier} ${String(leg.flightNumber)}`).join(', ')}</span>
          {isMultiAirline(legs) && <span className={styles.multiAirline}>Multiple airlines</span>}
        </span>
      </div>
      <div className={styles.stub}>
        <span className={styles.price}>{price(itinerary.price.amount)}</span>
        <span className={styles.freshness}>{freshness(itinerary.quotedAt, now)}</span>
      </div>
    </article>
  );
}
