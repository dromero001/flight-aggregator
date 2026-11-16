// @vitest-environment node
// A Node test (type-checked by tsconfig.node.json): it reads files, and needs no DOM.
import { readFileSync } from 'node:fs';
import { expect, test } from 'vitest';
import { CARRIER_NAMES } from '../itinerary/carriers.js';
import { AIRPORT_CITIES } from './airports.js';

const catalogue = new URL(
  '../../../adapters/persistence/src/main/resources/db/migration/V2__airport_catalogue.sql',
  import.meta.url,
);

const mockCatalogue = new URL(
  '../../../mock-gds/src/main/java/com/flightaggregator/mockgds/reference/StandardCatalogue.java',
  import.meta.url,
);

const readme = new URL('../../../README.md', import.meta.url);

function codesIn(text: string, pattern: RegExp): string[] {
  return [...text.matchAll(pattern)].map(([, code]) => code).sort();
}

/** The codes of the first column of the README's table under `## {title}`. */
function readmeCodes(title: string): string[] {
  const section = readFileSync(readme, 'utf8')
    .split(/^## /m)
    .find((text) => text.startsWith(`${title}\n`));
  return codesIn(section ?? '', /^\| ([A-Z0-9]+) \|/gm);
}

test("the dashboard's airports, the aggregator's catalogue and the README hold the same codes", () => {
  const catalogueCodes = codesIn(readFileSync(catalogue, 'utf8'), /\('([A-Z]{3})'\)/g);

  expect(catalogueCodes).toHaveLength(32);
  expect([...AIRPORT_CITIES.keys()].sort()).toEqual(catalogueCodes);
  expect(
    readmeCodes('Airports in the dataset'),
    'README.md\'s "Airports in the dataset" table',
  ).toEqual(catalogueCodes);
});

test("the dashboard's airlines, the mock's catalogue and the README hold the same codes and names", () => {
  // `new NetworkCarrier("IB", "Iberia", …)` or `new LowCostCarrier(\n "FR",\n "Ryanair", …)`.
  const mockAirlines = [
    ...readFileSync(mockCatalogue, 'utf8').matchAll(
      /new (?:NetworkCarrier|LowCostCarrier)\(\s*"([A-Z0-9]{2})",\s*"([^"]+)"/g,
    ),
  ]
    .map(([, code, name]) => [code, name])
    .sort();
  const mockCodes = mockAirlines.map(([code]) => code);

  expect(mockAirlines).toHaveLength(12);
  expect([...CARRIER_NAMES].sort()).toEqual(mockAirlines);
  expect(
    readmeCodes('Airlines in the dataset'),
    'README.md\'s "Airlines in the dataset" table',
  ).toEqual(mockCodes);
});
