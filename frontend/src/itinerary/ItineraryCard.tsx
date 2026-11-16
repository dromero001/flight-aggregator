import { type Ref, useId, useState } from 'react';
import type { Itinerary } from '../api/types';
import styles from './ItineraryCard.module.css';
import {
  duration,
  flight,
  freshness,
  isMultiAirline,
  layovers,
  localDate,
  localTime,
  price,
  stopsSummary,
} from './format';
import { DayMark } from './DayMark';
import { LegList } from './LegList';
import { RepricePanel } from './RepricePanel';
import { useReprice } from './useReprice';

interface ItineraryCardProps {
  itinerary: Itinerary;
  now: Date;
  /** The card's summary, the button that opens it. */
  summaryRef?: Ref<HTMLButtonElement>;
}

/**
 * One result, read like a boarding pass (plan 6, Q6): the times and airports on a route line,
 * then the date, duration, stops and flights; the price and its freshness on the stub.
 */
export function ItineraryCard({ itinerary, now, summaryRef }: ItineraryCardProps) {
  const { origin, destination, departure, arrival, legs } = itinerary;
  const connections = layovers(legs);
  const [open, setOpen] = useState(false);
  const panelId = useId();
  const reprice = useReprice();
  // The stub shows the latest quote of this itinerary with the time it was quoted: the search's or
  // the last re-price that confirmed or changed it (Q5-A), unless the search's is newer. It is kept here
  // rather than read from the mutation, which forgets its answer while the next opening is checked.
  const [requote, setRequote] = useState<Itinerary | null>(null);
  const quote =
    requote && Date.parse(requote.quotedAt) >= Date.parse(itinerary.quotedAt) ? requote : itinerary;
  // The price on the stub when the card was opened: the answer is worded against it (code I2).
  const [shownPrice, setShownPrice] = useState(itinerary.price);

  function toggle() {
    // Each opening asks again: a price confirmed a while ago may have changed since (spec 6.8).
    if (!open) {
      setShownPrice(quote.price);
      reprice.mutate(itinerary.id, {
        onSuccess: (answer) => {
          // An alternative is another itinerary: this one's quote stays as it was.
          if (answer.outcome !== 'ALTERNATIVE') setRequote(answer.itinerary);
        },
      });
    }
    setOpen(!open);
  }

  return (
    <article className={styles.card}>
      <button
        ref={summaryRef}
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
            <span className="visually-hidden"> to </span>
            <span className={styles.code}>{destination}</span>
            <time className={styles.time} dateTime={arrival}>
              {localTime(arrival)}
            </time>
            {/* "+1 day": the arrival's local date is after the departure's (plan 6 E1). */}
            <DayMark departure={departure} arrival={arrival} className={styles.days} />
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
          <span className={styles.price}>{price(quote.price.amount)}</span>
          <span className={styles.freshness}>{freshness(quote.quotedAt, now)}</span>
        </span>
      </button>
      {/* Always in the page, so the button's aria-controls names an element while closed too. */}
      <div id={panelId} className={styles.panel} hidden={!open}>
        {open && (
          <>
            <LegList legs={legs} label="Legs" />
            <RepricePanel result={reprice} shownPrice={shownPrice} />
          </>
        )}
      </div>
    </article>
  );
}
