import { act, renderHook } from '@testing-library/react';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import { useNow } from './useNow';

beforeEach(() => {
  vi.useFakeTimers();
  vi.setSystemTime(new Date('2026-11-15T09:00:00Z'));
});
afterEach(() => {
  vi.useRealTimers();
});

test('ticks every minute', () => {
  const { result } = renderHook(() => useNow(60_000));
  expect(result.current).toEqual(new Date('2026-11-15T09:00:00Z'));

  act(() => {
    vi.advanceTimersByTime(59_000);
  });
  expect(result.current).toEqual(new Date('2026-11-15T09:00:00Z'));

  act(() => {
    vi.advanceTimersByTime(1_000);
  });
  expect(result.current).toEqual(new Date('2026-11-15T09:01:00Z'));

  act(() => {
    vi.advanceTimersByTime(60_000);
  });
  expect(result.current).toEqual(new Date('2026-11-15T09:02:00Z'));
});
