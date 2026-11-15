import { ApiError } from '../api/apiError';
import type { RepriceResponse } from '../api/types';
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
  if (error instanceof ApiError) {
    if (error.status === 404) {
      return 'This flight is no longer available. Search again for current flights.';
    }
    if (error.status === 429 && error.retryAfterSeconds !== undefined) {
      return `Too many price checks from your connection. Try again in ${String(error.retryAfterSeconds)} seconds.`;
    }
  }
  return CANNOT_CONFIRM;
}

/** The sentence for a price the API has just confirmed (Q5-A). */
function confirmation(response: RepriceResponse): string {
  const now = price(response.itinerary.price.amount);
  if (response.outcome === 'PRICE_CHANGED') {
    // The contract sends the price shown before with this outcome only, and marks it optional.
    const before = response.previousPrice;
    return before
      ? `Price changed: was ${price(before.amount)}, now ${now}`
      : `Price changed: now ${now}`;
  }
  return `Price confirmed: ${now}`;
}

interface RepricePanelProps {
  result: Reprice;
}

/** What the re-price of an opened card answered (spec 6.5 and 6.8; plan 6, Q5-A). */
export function RepricePanel({ result }: RepricePanelProps) {
  const { data, error, isPending } = result;
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
          <p>{confirmation(data)}</p>
          <button className={styles.select} type="button">
            Select
          </button>
        </>
      )}
    </div>
  );
}
