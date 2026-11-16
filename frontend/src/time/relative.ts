// Relative times shared by the screens (plan 6, Q7): the cards' freshness, the status page's times.

const MILLIS_PER_MINUTE = 60_000;

/** Whole minutes between two instants: the offsets take part, the wall clocks do not. */
export function minutesBetween(from: string | Date, to: string | Date): number {
  return Math.floor((new Date(to).getTime() - new Date(from).getTime()) / MILLIS_PER_MINUTE);
}

/** Minutes from `now` until an instant, a started minute counted whole; 0 or less once it came. */
export function minutesUntil(instant: string, now: Date): number {
  return Math.ceil((new Date(instant).getTime() - now.getTime()) / MILLIS_PER_MINUTE);
}

/** "18 min", or "1 h 30 min" and "2 h" from an hour on: a span of whole minutes. */
export function hoursAndMinutes(minutes: number): string {
  if (minutes < 60) {
    return `${String(minutes)} min`;
  }
  const hours = `${String(Math.floor(minutes / 60))} h`;
  return minutes % 60 === 0 ? hours : `${hours} ${String(minutes % 60)} min`;
}

/** "just now", "4 min ago", "1 h ago": how long before `now` an instant was (plan 6, Q7). */
export function timeAgo(instant: string, now: Date): string {
  const minutes = minutesBetween(instant, now);
  if (minutes < 1) {
    return 'just now';
  }
  if (minutes < 60) {
    return `${String(minutes)} min ago`;
  }
  return `${String(Math.floor(minutes / 60))} h ago`;
}
