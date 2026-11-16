import { expect, test } from 'vitest';
import { hoursAndMinutes } from './relative';

test.each([
  [18, '18 min'],
  [90, '1 h 30 min'],
  [120, '2 h'],
])('writes %i minutes as %j', (minutes, expected) => {
  expect(hoursAndMinutes(minutes)).toBe(expected);
});
