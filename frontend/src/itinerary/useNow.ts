import { useEffect, useState } from 'react';

/** The current time, renewed every `intervalMs`, so "Updated N min ago" stays true (Q7). */
export function useNow(intervalMs: number): Date {
  const [now, setNow] = useState(() => new Date());

  useEffect(() => {
    const timer = setInterval(() => {
      setNow(new Date());
    }, intervalMs);
    return () => {
      clearInterval(timer);
    };
  }, [intervalMs]);

  return now;
}
