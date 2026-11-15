import { useId, useState } from 'react';
import type { Itinerary } from '../api/types';
import styles from './ItineraryCard.module.css';
import {
  daysLater,
  duration,
  flight,
  freshness,
  isMultiAirline,
  layovers,
  localDate,
  localTime,
  price,
  type Layover,
} from './format';
import { LegList } from './LegList';
import { RepricePanel } from './RepricePanel';
import { useReprice } from './useReprice';

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
  const arrivalDays = daysLater(departure, arrival);
  const [open, setOpen] = useState(false);
  const panelId = useId();
  const reprice = useReprice();
  // A changed price replaces the one the search showed (Q5-A).
  const shownPrice =
    reprice.data?.outcome === 'PRICE_CHANGED' ? reprice.data.itinerary.price : itinerary.price;

  function toggle() {
    // Each opening asks again: a price confirmed a while ago may have changed since (spec 6.8).
    if (!open) {
      reprice.mutate(itinerary.id);
    }
    setOpen(!open);
  }

  return (
    <article className={styles.card}>
      <button
        className={styles.summary}
        type="button"
        aria-expanded={open}
        aria-controls={panelId}
        onClick={toggle}
      >
        <span className={styles.pass}>
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
            {/* "+1": the arrival's local date is the day after the departure's (plan 6 E1). */}
            {arrivalDays > 0 && <span className={styles.days}>{`+${String(arrivalDays)}`}</span>}
          </span>
          <span className={styles.details}>
            <time dateTime={departure}>{localDate(departure)}</time>
            <span>{duration(itinerary.durationMinutes)}</span>
            <span>{stopsSummary(connections)}</span>
          </span>
          <span className={styles.flights}>
            <span>{legs.map(flight).join(', ')}</span>
            {isMultiAirline(legs) && <span className={styles.multiAirline}>Multiple airlines</span>}
          </span>
        </span>
        <span className={styles.stub}>
          <span className={styles.price}>{price(shownPrice.amount)}</span>
          <span className={styles.freshness}>{freshness(itinerary.quotedAt, now)}</span>
        </span>
      </button>
      {/* Always in the page, so the button's aria-controls names an element while closed too. */}
      <div id={panelId} className={styles.panel} hidden={!open}>
        {open && (
          <>
            <LegList legs={legs} label="Legs" />
            <RepricePanel result={reprice} />
          </>
        )}
      </div>
    </article>
  );
}
