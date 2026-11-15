import type { components } from './schema';

// The contract's schemas under the dashboard's names. Tasks 6 and 8 add the re-price and status
// schemas once plan 5 commits them to the contract.
type Schemas = components['schemas'];

export type Itinerary = Schemas['ItineraryResponse'];
export type Leg = Schemas['Leg'];
export type SearchResponse = Schemas['SearchResponse'];
