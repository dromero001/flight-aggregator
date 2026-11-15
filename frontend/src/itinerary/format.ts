import type { Leg } from '../api/types';

// Display formats for the itinerary card (plan 6, Q7: en-GB, EUR, the 24-hour clock).

// The API writes each time at its airport's own offset (spec 4.2): the wall clock is the text
// before the offset, so it is read from the string and never converted to the browser's zone.
const WALL_CLOCK = /^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2})/;

function wallClock(iso: string): { date: string; time: string } {
  const match = WALL_CLOCK.exec(iso);
  if (!match) {
    throw new Error(`Not an ISO-8601 date-time: ${iso}`);
  }
  const [, date, time] = match;
  return { date, time };
}

// The airport's calendar date, formatted as a UTC midnight so no zone can move it to another day.
const DAY = new Intl.DateTimeFormat('en-GB', {
  weekday: 'short',
  day: 'numeric',
  month: 'short',
  timeZone: 'UTC',
});

/** "08:15": the time written in the string, at its own airport. */
export function localTime(iso: string): string {
  return wallClock(iso).time;
}

/** "Fri 20 Nov": the date written in the string, at its own airport. */
export function localDate(iso: string): string {
  return DAY.format(new Date(`${wallClock(iso).date}T00:00:00Z`));
}

/** "7h 25m", "45m", "2h". */
export function duration(minutes: number): string {
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  if (hours === 0) {
    return `${String(rest)}m`;
  }
  return rest === 0 ? `${String(hours)}h` : `${String(hours)}h ${String(rest)}m`;
}

const EUROS = new Intl.NumberFormat('en-GB', { style: 'currency', currency: 'EUR' });

/**
 * "€1,234.50". Intl formats the API's decimal string as written, so no amount passes through a
 * binary number on its way to the screen.
 */
export function price(amount: string): string {
  return EUROS.format(amount as Intl.StringNumericLiteral);
}

const MILLIS_PER_MINUTE = 60_000;

/** Whole minutes between two instants: the offsets take part, the wall clocks do not. */
function minutesBetween(from: string | Date, to: string | Date): number {
  return Math.floor((new Date(to).getTime() - new Date(from).getTime()) / MILLIS_PER_MINUTE);
}

/** "Updated just now", "Updated 4 min ago", "Updated 1 h ago" (plan 6, Q7). */
export function freshness(quotedAt: string, now: Date): string {
  const minutes = minutesBetween(quotedAt, now);
  if (minutes < 1) {
    return 'Updated just now';
  }
  if (minutes < 60) {
    return `Updated ${String(minutes)} min ago`;
  }
  return `Updated ${String(Math.floor(minutes / 60))} h ago`;
}

/** A connection: the airport and the minutes from one leg's arrival to the next departure. */
export interface Layover {
  airport: string;
  minutes: number;
}

export function layovers(legs: Leg[]): Layover[] {
  return legs.slice(1).map((next, index) => {
    const previous = legs[index];
    return {
      airport: previous.destination,
      minutes: minutesBetween(previous.arrival, next.departure),
    };
  });
}

/** True when the legs are flown by more than one carrier ("Multiple airlines", spec 6.8). */
export function isMultiAirline(legs: Leg[]): boolean {
  return new Set(legs.map((each) => each.carrier)).size > 1;
}
