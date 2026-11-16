import type { components } from './schema';

// The contract's schemas under the dashboard's names.
type Schemas = components['schemas'];

export type Itinerary = Schemas['ItineraryResponse'];
export type Leg = Schemas['Leg'];
export type SearchResponse = Schemas['SearchResponse'];
export type RepriceResponse = Schemas['RepriceResponse'];
export type StatusReport = Schemas['StatusResponse'];
export type ProviderStatus = Schemas['ProviderStatusResponse'];
