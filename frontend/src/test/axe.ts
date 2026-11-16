import { expect } from 'vitest';
import { configureAxe } from 'vitest-axe';
import * as matchers from 'vitest-axe/matchers';
import { AXE_TAGS } from './axeTags';

/**
 * axe with the rules the end-to-end tests apply (plan 6, Q7): WCAG 2.0 and 2.1, levels A and AA.
 * Colour contrast is left to the end-to-end tests: jsdom neither lays out nor paints the page, so
 * the rule cannot decide here (and reaches for a canvas jsdom does not have).
 */
export const axe = configureAxe({
  runOnly: { type: 'tag', values: AXE_TAGS },
  rules: { 'color-contrast': { enabled: false } },
});

expect.extend(matchers);

// vitest-axe 0.1.0 types its matcher for Vitest 0.x's `Vi` namespace; this is the same matcher
// declared the way Vitest 5 reads custom matchers.
declare module 'vitest' {
  // eslint-disable-next-line @typescript-eslint/no-unused-vars -- merging needs Vitest's own parameters
  interface Matchers<R extends void | Promise<void> = void | Promise<void>, T = unknown> {
    toHaveNoViolations: () => R;
  }
}
