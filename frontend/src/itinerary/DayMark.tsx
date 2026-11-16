import styles from './DayMark.module.css';
import { laterDayMark } from './format';

interface DayMarkProps {
  departure: string;
  arrival: string;
  /** Where the mark sits in its container; its look is the mark's own. */
  className?: string;
}

/**
 * "+1 day" after an arrival on a later local date than its departure (plan 6 E1), styled the same on
 * a card's summary and on each of its legs; nothing for an arrival the same day.
 */
export function DayMark({ departure, arrival, className }: DayMarkProps) {
  const mark = laterDayMark(departure, arrival);
  if (mark === undefined) {
    return null;
  }
  return <span className={className ? `${styles.mark} ${className}` : styles.mark}>{mark}</span>;
}
