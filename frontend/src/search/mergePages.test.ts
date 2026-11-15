import { expect, test } from 'vitest';
import type { SearchResponse } from '../api/types';
import { connecting, direct } from '../test/fixtures';
import { mergePages } from './mergePages';

function page(overrides: Partial<SearchResponse>): SearchResponse {
  return { itineraries: [], nextCursor: null, inventoryChanged: false, ...overrides };
}

test('appends only itineraries not already shown', () => {
  const later = direct({ id: '5b6c7d8e-9f0a-4b1c-8d2e-3f4a5b6c7d8e' });
  // A flagged page, read on a new generation, can repeat a row the first page showed (Q4-A).
  const pages = [
    page({ itineraries: [direct(), connecting()], nextCursor: 'c1' }),
    page({ itineraries: [connecting(), later], inventoryChanged: true }),
  ];

  const ids = mergePages(pages).itineraries.map((itinerary) => itinerary.id);

  expect(ids).toEqual([
    '3f2c8a3e-7b1d-4c5e-9a60-1d2e3f4a5b6c',
    '8d4e6f10-2a3b-4c5d-8e9f-0a1b2c3d4e5f',
    '5b6c7d8e-9f0a-4b1c-8d2e-3f4a5b6c7d8e',
  ]);
});

test('flags a changed inventory from any page', () => {
  const unflagged = page({ itineraries: [direct()], nextCursor: 'c1' });
  const flagged = page({ itineraries: [connecting()], inventoryChanged: true });

  expect(mergePages([unflagged]).inventoryChanged).toBe(false);
  expect(mergePages([unflagged, flagged]).inventoryChanged).toBe(true);
  // A later unflagged page does not clear the notice.
  expect(mergePages([unflagged, flagged, page({})]).inventoryChanged).toBe(true);
});
