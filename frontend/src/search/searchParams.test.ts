import { expect, test } from 'vitest';
import { parseSearch, toSearchParams, type SearchCriteria } from './searchParams';

test('round-trips every filter and the sort', () => {
  const criteria: SearchCriteria = {
    origin: 'MAD',
    destination: 'JFK',
    carrier: 'IB',
    maxPrice: '149.99',
    sort: 'duration',
  };

  const parsed = parseSearch(toSearchParams(criteria));

  expect(parsed.criteria).toEqual(criteria);
  expect(parsed.errors).toEqual({});
});

test('reports each invalid value as a field error', () => {
  const params = new URLSearchParams({
    origin: 'MADR',
    destination: 'M1D',
    carrier: 'IBX',
    maxPrice: '-1',
  });

  expect(parseSearch(params).errors).toEqual({
    origin: 'Enter a 3-letter airport code, like MAD',
    destination: 'Enter a 3-letter airport code, like MAD',
    carrier: 'Enter a 2-character airline code, like IB',
    maxPrice: 'Enter an amount in euros above 0, like 150 or 149.99',
  });
  for (const maxPrice of ['0', '1e3', 'abc', '1.234', '0.00', '1234567890', '55,555', '1,2,3']) {
    expect(parseSearch(new URLSearchParams({ maxPrice })).errors).toHaveProperty('maxPrice');
  }
  expect(parseSearch(new URLSearchParams({ maxPrice: '0.50', origin: 'LHR' })).errors).toEqual({});
});

test('falls back to price for an unknown sort', () => {
  const { criteria, errors } = parseSearch(new URLSearchParams({ sort: 'best', page: '2' }));

  expect(criteria).toEqual({ sort: 'price' });
  expect(errors).toEqual({});
});

test('normalises codes and amounts', () => {
  const params = new URLSearchParams({
    origin: ' mad ',
    destination: 'jfk',
    carrier: 'ib',
    maxPrice: ' 150 ',
    sort: 'departure',
  });

  const { criteria, errors } = parseSearch(params);

  expect(criteria).toEqual({
    origin: 'MAD',
    destination: 'JFK',
    carrier: 'IB',
    maxPrice: '150',
    sort: 'departure',
  });
  expect(errors).toEqual({});
});

test.each([
  ['55,5', '55.5'],
  ['149,99', '149.99'],
])('reads the decimal comma of %j as a dot', (maxPrice, expected) => {
  const { criteria, errors } = parseSearch(new URLSearchParams({ maxPrice }));

  expect(criteria.maxPrice).toBe(expected);
  expect(errors).toEqual({});
});

test('keeps an amount with more than one comma as typed, and rejects it', () => {
  const { criteria, errors } = parseSearch(new URLSearchParams({ maxPrice: '1,2,3' }));

  expect(criteria.maxPrice).toBe('1,2,3');
  expect(errors).toEqual({ maxPrice: 'Enter an amount in euros above 0, like 150 or 149.99' });
});
