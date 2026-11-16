import AxeBuilder from '@axe-core/playwright';
import { expect, type Page } from '@playwright/test';
import { AXE_TAGS } from '../src/test/axeTags.js';

/** How many of a violation's elements its line names; a broken token repeats on every card. */
const ELEMENTS_NAMED = 3;

/**
 * Fails with every axe violation on the page as it stands, one line each: the rule, what it asks
 * and the first elements that break it. Unlike the component tests, the page is laid out and
 * painted here, so colour contrast is checked too.
 */
export async function expectNoAxeViolations(page: Page): Promise<void> {
  const { violations } = await new AxeBuilder({ page }).withTags(AXE_TAGS).analyze();
  const found = violations.map(({ id, help, nodes }) => {
    const named = nodes.slice(0, ELEMENTS_NAMED).map((node) => node.target.join(' '));
    const more =
      nodes.length > ELEMENTS_NAMED ? ` and ${String(nodes.length - ELEMENTS_NAMED)} more` : '';
    return `${id}: ${help} (${named.join(', ')}${more})`;
  });
  expect(found, `axe violations on ${page.url()}`).toEqual([]);
}
