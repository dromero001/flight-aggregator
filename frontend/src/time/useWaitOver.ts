import { useEffect, useState } from 'react';

/**
 * Whether the wait of `seconds` named `waitId` is over; the component renders again when it ends.
 * A new `waitId` starts a new wait, and no wait (`undefined`) never ends.
 */
export function useWaitOver(waitId: number, seconds: number | undefined): boolean {
  const [endedWait, setEndedWait] = useState<number>();

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
