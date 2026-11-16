// @vitest-environment node
// A Node test of the Playwright config's browser choice (type-checked by tsconfig.node.json).
import { expect, test } from 'vitest';
import { selectBrowsers } from './browsers.js';

const ALL = ['chromium', 'firefox', 'webkit'] as const;

test('runs every browser when E2E_BROWSERS is not set', () => {
  expect(selectBrowsers(undefined, ALL)).toEqual(['chromium', 'firefox', 'webkit']);
});

test('runs only the browsers E2E_BROWSERS names, in the config order', () => {
  // The order spaces the searches out (playwright.config.ts), so it is the config's, not the list's.
  expect(selectBrowsers('webkit, chromium', ALL)).toEqual(['chromium', 'webkit']);
});

test('fails on a browser it does not know, naming the valid ones', () => {
  // A typo would otherwise run fewer browsers than asked, and the run would still pass.
  expect(() => selectBrowsers('chromium,safari', ALL)).toThrow(
    'E2E_BROWSERS names an unknown browser, "safari"; the valid ones are chromium, firefox, webkit',
  );
});
