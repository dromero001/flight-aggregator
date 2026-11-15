import { afterEach, beforeEach, expect, test, vi } from 'vitest';
import type { Leg } from '../api/types';
import { connecting, direct } from '../test/fixtures';
import {
  daysLater,
  duration,
  freshness,
  isMultiAirline,
  layovers,
  localDate,
  localTime,
  price,
} from './format';

// Auckland is 18 hours ahead of New York in November: a time read in the browser's zone would
// land on another hour and another day.
// (Vitest sets process.env.TZ, which Node applies at once, and restores it afterwards.)
beforeEach(() => {
  vi.stubEnv('TZ', 'Pacific/Auckland');
});
afterEach(() => {
  vi.unstubAllEnvs();
});

test("shows the airport's local time whatever the browser's zone", () => {
  expect(localTime('2026-11-20T08:15:00-05:00')).toBe('08:15');
  expect(localDate('2026-11-20T08:15:00-05:00')).toBe('Fri 20 Nov');
  expect(localTime('2026-11-20T09:20:00Z')).toBe('09:20');
});

test('formats durations', () => {
  expect(duration(445)).toBe('7h 25m');
  expect(duration(45)).toBe('45m');
  expect(duration(120)).toBe('2h');
});

test('formats euro prices from decimal strings', () => {
  expect(price('1234.50')).toBe('€1,234.50');
  expect(price('412.6')).toBe('€412.60');
  expect(price('129')).toBe('€129.00');
});

test('describes freshness', () => {
  const quotedAt = '2026-11-15T09:00:00Z';

  expect(freshness(quotedAt, new Date('2026-11-15T09:00:30Z'))).toBe('Updated just now');
  expect(freshness(quotedAt, new Date('2026-11-15T09:04:00Z'))).toBe('Updated 4 min ago');
  expect(freshness(quotedAt, new Date('2026-11-15T10:01:00Z'))).toBe('Updated 1 h ago');
});

function leg(origin: string, destination: string, departure: string, arrival: string): Leg {
  return { carrier: 'IB', flightNumber: 1, origin, destination, departure, arrival };
}

test('computes layovers across time zones', () => {
  const madrid = leg('MAD', 'LHR', '2026-11-20T07:00:00+01:00', '2026-11-20T09:20:00Z');
  const london = leg('LHR', 'JFK', '2026-11-20T10:30:00+00:00', '2026-11-20T13:40:00-05:00');
  expect(layovers([madrid, london])).toEqual([{ airport: 'LHR', minutes: 70 }]);

  // The same instants written at other offsets: the wall clocks differ by 10 minutes.
  const sameArrivalInMadridTime = { ...madrid, arrival: '2026-11-20T10:20:00+01:00' };
  expect(layovers([sameArrivalInMadridTime, london])).toEqual([{ airport: 'LHR', minutes: 70 }]);

  expect(layovers([madrid])).toEqual([]);
});

test('detects a multi-airline itinerary', () => {
  expect(isMultiAirline(connecting().legs)).toBe(true);
  expect(isMultiAirline(direct().legs)).toBe(false);

  const iberiaTwice = connecting().legs.map((each) => ({ ...each, carrier: 'IB' }));
  expect(isMultiAirline(iberiaTwice)).toBe(false);
});

test('counts the local days from departure to arrival', () => {
  // 22:40 in Madrid to 06:15 in London: the next day at the airport of arrival.
  expect(daysLater('2026-11-20T22:40:00+01:00', '2026-11-21T06:15:00Z')).toBe(1);
  expect(daysLater('2026-11-20T08:15:00+01:00', '2026-11-20T13:40:00-05:00')).toBe(0);
  // 18:00 in New York to 21:30 in Los Angeles is the same local day, although it lands at 05:30
  // UTC the next day: each date is read from its own string.
  expect(daysLater('2026-11-20T18:00:00-05:00', '2026-11-20T21:30:00-08:00')).toBe(0);
  // Across the end of a month.
  expect(daysLater('2026-11-30T23:50:00Z', '2026-12-01T00:40:00Z')).toBe(1);
});
