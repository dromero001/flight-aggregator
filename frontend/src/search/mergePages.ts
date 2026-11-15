import type { Itinerary, SearchResponse } from '../api/types';

/**
 * The loaded pages as one list, in order. A page read after the inventory changed can repeat an
 * itinerary an earlier page showed; only its first occurrence is kept (plan 6, Q4-A), so each
 * card has a unique key.
 */
export function mergePages(pages: SearchResponse[]): {
  itineraries: Itinerary[];
  inventoryChanged: boolean;
} {
  const shown = new Map<string, Itinerary>();
  for (const itinerary of pages.flatMap((page) => page.itineraries)) {
    if (!shown.has(itinerary.id)) shown.set(itinerary.id, itinerary);
  }
  return {
    itineraries: [...shown.values()],
    inventoryChanged: pages.some((page) => page.inventoryChanged),
  };
}
