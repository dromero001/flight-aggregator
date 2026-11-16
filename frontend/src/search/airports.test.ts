// @vitest-environment node
// A Node test (type-checked by tsconfig.node.json): it reads files, and needs no DOM.
import { readFileSync } from 'node:fs';
import { expect, test } from 'vitest';
import { AIRPORT_CITIES } from './airports.js';

const catalogue = new URL(
  '../../../adapters/persistence/src/main/resources/db/migration/V2__airport_catalogue.sql',
  import.meta.url,
);

const readme = new URL('../../../README.md', import.meta.url);

function codesIn(text: string, pattern: RegExp): string[] {
  return [...text.matchAll(pattern)].map(([, code]) => code).sort();
}

/** The first column of the README's "Airports in the dataset" table. */
function readmeCodes(): string[] {
  const section = readFileSync(readme, 'utf8')
    .split(/^## /m)
    .find((text) => text.startsWith('Airports in the dataset\n'));
  return codesIn(section ?? '', /^\| ([A-Z]{3}) \|/gm);
}

test("the dashboard's airports, the aggregator's catalogue and the README hold the same codes", () => {
  const catalogueCodes = codesIn(readFileSync(catalogue, 'utf8'), /\('([A-Z]{3})'\)/g);

  expect(catalogueCodes).toHaveLength(32);
  expect([...AIRPORT_CITIES.keys()].sort()).toEqual(catalogueCodes);
  expect(readmeCodes(), 'README.md\'s "Airports in the dataset" table').toEqual(catalogueCodes);
});
