import { useState } from 'react';
import { ApiError, rateLimitWait } from '../api/apiError';
import type { Itinerary, RepriceResponse } from '../api/types';
import { price } from './format';
import { LegList } from './LegList';
import styles from './RepricePanel.module.css';
import type { Reprice } from './useReprice';

/** Spec 6.5's words for an alternative; the API sends them as `notice`, marked optional. */
const ALTERNATIVE_NOTICE =
  "We couldn't confirm this flight. This is the best alternative we found.";

/** Spec 6.5's words when nothing could be confirmed (the 503). */
const CANNOT_CONFIRM = "We can't confirm the price right now. Please try again in a few minutes.";

/**
 * The sentence for a failed re-price (plan 6, Q7). Anything else that went wrong (the 503, a 502
 * from nginx, a network error, a rate limit without a wait) means the same to the customer: the
 * price could not be confirmed now.
 */
function failure(error: Error): string {
  if (error instanceof ApiError && error.status === 404) {
    return 'This flight is no longer available. Search again for current flights.';
  }
  const wait = rateLimitWait(error);
  if (wait !== undefined) {
    return `Too many price checks from your connection. Try again in ${String(wait)} seconds.`;
  }
  return CANNOT_CONFIRM;
}

/** The sentence for a price the API has just confirmed (Q5-A), against the price the card showed. */
function confirmation(response: RepriceResponse, shown: Itinerary['price']): string {
  const now = price(response.itinerary.price.amount);
  const changed = (before: string) => `Price changed: was ${before}, now ${now}`;
  switch (response.outcome) {
    case 'PRICE_CHANGED':
      return changed(price(shown.amount));
    case 'CONFIRMED': {
      // The API confirms against its stored price, which a cached page may not show yet (final
      // review, code I2): for the customer, a price other than the card's has changed.
      const before = price(shown.amount);
      return before === now ? `Price confirmed: ${now}` : changed(before);
    }
    case 'ALTERNATIVE':
      // Another itinerary, whose price the card never showed.
      return `Price confirmed: ${now}`;
  }
}

interface RepricePanelProps {
  result: Reprice;
  /** The price the card showed when it was opened. */
  shownPrice: Itinerary['price'];
}

/** What the re-price of an opened card answered (spec 6.5 and 6.8; plan 6, Q5-A). */
export function RepricePanel({ result, shownPrice }: RepricePanelProps) {
  const { data, error, isPending } = result;
  // Booking is out of scope (spec 6.8): "Select" goes no further, and says so (plan 6 E8).
  const [selected, setSelected] = useState(false);
  return (
    <div className={styles.panel} role="status">
      {isPending && <p>Checking the price…</p>}
      {error && <p>{failure(error)}</p>}
      {data && (
        <>
          {data.outcome === 'ALTERNATIVE' && (
            <>
              <p>{data.notice ?? ALTERNATIVE_NOTICE}</p>
              <LegList legs={data.itinerary.legs} label="Alternative legs" />
            </>
          )}
          <div className={styles.offer}>
            <p>{confirmation(data, shownPrice)}</p>
            <button
              className={styles.select}
              type="button"
              onClick={() => {
                setSelected(true);
              }}
            >
              Select
            </button>
          </div>
          {selected && <p>Booking is not part of this demo.</p>}
        </>
      )}
    </div>
  );
}
