import type { Itinerary, Leg, ProviderStatus, StatusReport } from '../api/types';

// Friday 20 November 2026. Each time is written at its airport's own offset, as the API writes
// it (spec 4.2): Madrid +01:00, London Z, New York -05:00.

/** IB 3166 Madrid 08:15 → London 09:40 (2 h 25 min). */
function madridToLondon(): Leg {
  return {
    carrier: 'IB',
    flightNumber: 3166,
    origin: 'MAD',
    destination: 'LHR',
    departure: '2026-11-20T08:15:00+01:00',
    arrival: '2026-11-20T09:40:00Z',
  };
}

/** A direct flight: IB 3166 alone. */
export function direct(overrides: Partial<Itinerary> = {}): Itinerary {
  return {
    id: '3f2c8a3e-7b1d-4c5e-9a60-1d2e3f4a5b6c',
    origin: 'MAD',
    destination: 'LHR',
    departure: '2026-11-20T08:15:00+01:00',
    arrival: '2026-11-20T09:40:00Z',
    durationMinutes: 145,
    stops: 0,
    price: { amount: '129.90', currency: 'EUR' },
    quotedAt: '2026-11-15T09:00:00Z',
    legs: [madridToLondon()],
    ...overrides,
  };
}

/**
 * One stop, two airlines: IB 3166, 1 h 10 min in London, then BA 117 London 10:50 → New York
 * 13:40 (11 h 25 min in all).
 */
export function connecting(overrides: Partial<Itinerary> = {}): Itinerary {
  return {
    id: '8d4e6f10-2a3b-4c5d-8e9f-0a1b2c3d4e5f',
    origin: 'MAD',
    destination: 'JFK',
    departure: '2026-11-20T08:15:00+01:00',
    arrival: '2026-11-20T13:40:00-05:00',
    durationMinutes: 685,
    stops: 1,
    price: { amount: '412.60', currency: 'EUR' },
    quotedAt: '2026-11-15T08:55:00Z',
    legs: [
      madridToLondon(),
      {
        carrier: 'BA',
        flightNumber: 117,
        origin: 'LHR',
        destination: 'JFK',
        departure: '2026-11-20T10:50:00Z',
        arrival: '2026-11-20T13:40:00-05:00',
      },
    ],
    ...overrides,
  };
}

/** A provider whose last run downloaded fresh offers: up, its breaker closed. */
export function providerStatus(overrides: Partial<ProviderStatus> = {}): ProviderStatus {
  return {
    provider: 'ALPHA',
    health: 'UP',
    breaker: 'CLOSED',
    newestQuotedAt: '2026-11-15T09:00:00Z',
    legs: 412,
    discarded: {},
    stale: false,
    ...overrides,
  };
}

/** Generation 7, published at 09:00 UTC, the next run due at 09:30 and none going on; Alpha. */
export function statusReport(overrides: Partial<StatusReport> = {}): StatusReport {
  return {
    generation: 7,
    publishedAt: '2026-11-15T09:00:00Z',
    nextIngestionAt: '2026-11-15T09:30:00Z',
    ingestionRunning: false,
    providers: [providerStatus()],
    ...overrides,
  };
}
