// @vitest-environment node
// A Node test (type-checked by tsconfig.node.json): it reads files, and needs no DOM.
import { existsSync, readFileSync } from 'node:fs';
import openapiTS, { astToString, COMMENT_HEADER } from 'openapi-typescript';
import { format, resolveConfig } from 'prettier';
import { expect, test } from 'vitest';

const contract = new URL('../../../docs/architecture/openapi.json', import.meta.url);
const committedFile = new URL('./schema.d.ts', import.meta.url);

test('committed types match the contract', async () => {
  // The same steps as `npm run api:generate`: the CLI's output, then Prettier.
  const generated = COMMENT_HEADER + astToString(await openapiTS(contract));
  const formatted = await format(generated, {
    ...(await resolveConfig(committedFile)),
    filepath: committedFile.pathname,
  });
  // Read when the test runs, never imported: Vitest's module cache would keep an old copy.
  const committed = existsSync(committedFile) ? readFileSync(committedFile, 'utf8') : undefined;

  expect(
    committed,
    'src/api/schema.d.ts is missing or out of date with docs/architecture/openapi.json: run `npm run api:generate`',
  ).toBe(formatted);
});
