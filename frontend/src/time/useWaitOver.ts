import { useEffect, useState } from 'react';

/**
 * Whether the wait of `seconds` named `waitId` is over; the component renders again when it ends.
 * A `waitId` the hook has not seen before (compared by identity, such as each failure's own error)
 * starts a new wait, and no wait (`undefined`) never ends.
 */
export function useWaitOver(waitId: unknown, seconds: number | undefined): boolean {
  const [endedWait, setEndedWait] = useState<unknown>();

  useEffect(() => {
    if (seconds === undefined) return;
    const timer = setTimeout(() => {
      setEndedWait(waitId);
    }, seconds * 1000);
    return () => {
      clearTimeout(timer);
    };
  }, [waitId, seconds]);

  return seconds !== undefined && endedWait === waitId;
}
