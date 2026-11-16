import { expect, test } from 'vitest';
import { label } from './labels';

test('turns an enum value into sentence case', () => {
  expect(label('UNKNOWN_AIRPORT')).toBe('Unknown airport');
  expect(label('UP')).toBe('Up');
  expect(label('ALPHA')).toBe('Alpha');
});

test('writes the half-open breaker with a hyphen', () => {
  expect(label('HALF_OPEN')).toBe('Half-open');
});
