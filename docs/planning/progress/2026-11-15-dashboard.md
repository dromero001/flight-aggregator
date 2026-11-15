# Progress — plan 6 (dashboard), 2026-11-15

## Task 1 — evidence

Scaffold of `frontend/` (a standalone npm project, Q1-A), its quality gate (`npm run verify`) and the CI job `frontend`.

### Versions (spec 6.1)

Rule applied: the newest stable GA whose registry publication date is on or before 2026-11-01 (at least 14 days before today, 2026-11-15), no prerelease, accepting its peers. Dates come from `npm view <pkg> time --json`; Node comes from `https://nodejs.org/dist/index.json`. Every version is pinned exactly (`.npmrc` `save-exact=true`) and `package-lock.json` is committed.

| Package | Pinned | Published | Note |
|---|---|---|---|
| Node (`frontend/.nvmrc`, `engines` `^26.13.0`) | 26.13.0 | 2026-10-28 | The active LTS line (26, "Lithium"); 26.13.1 (2026-11-10) is too new |
| react, react-dom | 19.3.1 | 2026-10-14 | |
| @tanstack/react-query | 5.105.1 | 2026-11-01 | 5.106+ are newer than 14 days |
| vite | 8.3.5 | 2026-10-21 | |
| @vitejs/plugin-react | 6.1.2 | 2026-10-05 | |
| vitest | 5.0.5 | 2026-10-15 | |
| jsdom | 30.1.3 | 2026-10-15 | |
| @testing-library/react | 16.3.3 | 2026-08-27 | |
| @testing-library/dom | 10.4.2 | 2026-09-13 | peer of `@testing-library/react` |
| @testing-library/jest-dom | 7.0.1 | 2026-08-09 | |
| @testing-library/user-event | 14.6.8 | 2026-10-13 | |
| msw | 3.0.3 | 2026-10-10 | v3 renames `onUnhandledRequest` to `onUnhandledFrame` (below) |
| typescript | **6.0.3** | 2026-04-16 | The newest, 7.0.2 (2026-07-08), breaks `typescript-eslint`'s peer range (`typescript >=4.8.4 <6.1.0`), so the newest that fits is 6.0.3 |
| eslint, @eslint/js | **9.39.5** | 2026-07-10 | The newest, 10.14.0 (2026-10-30), breaks `eslint-plugin-jsx-a11y`'s peer range (`eslint ^3 ... ^9`), and that plugin is the approved E7; 9.39.5 is the newest 9.x |
| typescript-eslint | 8.72.1 | 2026-10-26 | |
| eslint-plugin-react-hooks | 7.1.1 | 2026-04-17 | |
| eslint-plugin-react-refresh | 0.5.7 | 2026-09-14 | |
| eslint-plugin-jsx-a11y | 6.10.2 | 2024-10-26 | E7; the last release |
| globals | 17.14.0 | 2026-11-01 | |
| prettier | 3.9.10 | 2026-10-10 | |
| @types/react, @types/react-dom | 19.3.0 | 2026-09-09 | |
| @types/node | 26.6.6 | 2026-10-28 | matches Node 26 |

Later tasks install from this table (newest stable GA at least 14 days old, checked on 2026-11-15, not yet installed):

| Package | Version | Published | Note |
|---|---|---|---|
| react-router | 8.5.1 | 2026-10-27 | needs react >=19.2.7 (fits) |
| openapi-typescript | 7.13.0 | 2026-02-11 | peer `typescript ^5.x`; the repository pins TypeScript 6.0.3, so Task 2 must check it (an npm `overrides` or a peer warning) |
| openapi-fetch | 0.17.0 | 2026-02-11 | |
| oidc-client-ts | 3.5.0 | 2026-03-13 | |
| @playwright/test | 1.64.1 | 2026-10-14 | |
| @axe-core/playwright | 4.14.0 | 2026-10-15 | |
| axe-core | 4.14.0 | 2026-10-05 | |
| vitest-axe | 0.1.0 | 2022-10-21 | E7; unmaintained since 2022 (peer `vitest >=0.16.0`); to re-check when the first component test uses it |
| create-vite | 9.3.3 | 2026-10-27 | used once for the template (see below) |
| @fontsource packages | not chosen | | depend on Q6's font, decided in Task 3 |

Not chosen: `create-vite` 9.3.3's `react-ts` template ships `oxlint` and `typescript ~7.0.2`; the brief and spec ask for ESLint with `typescript-eslint`, and TypeScript 7 conflicts with it, so only the template's layout (`tsconfig` split, `vite.config.ts`, `index.html`, `main.tsx`) was followed and the files were written by hand.

Deviations from the brief's text:

- `src/test/setup.ts` passes `onUnhandledFrame: 'error'`, MSW 3's name for what the brief calls `onUnhandledRequest: 'error'` (the old option does not exist in `msw@3.0.3`; `tsc` rejects it). Proven below: an unmocked request fails the test.
- `tsconfig.app.json` also sets `noUncheckedIndexedAccess` (strictness beyond `strict`; spec 6.11 asks for strict mode, and this is part of "strict TypeScript" in the brief's commit message). It is cheap to remove if the user does not want it.
- The `test` script writes the JUnit report to `test-results/junit.xml` (brief).

### The test, red then green

`App.test.tsx` `'renders the search page heading'`, written against the template-style page (`<h1>Vite + React</h1>`).

- Red, for the expected reason: `TestingLibraryElementError: Unable to find an accessible element with the role "main"` (the page had no `<main>`), 1 failed.
- Green: `App` renders `<main><h1>Find flights</h1></main>` inside a `QueryClientProvider` (the client from `createQueryClient()`); `npm run verify` green.

`createQueryClient`, `server.ts` and `setup.ts` have no test of their own: they are the test harness and a one-line factory that Task 5 gives its retry rule and tests; `App.test.tsx` exercises the factory and the setup (it fails if either breaks).

### Each gate failing once

A throwaway file was written, the gate run, and the file removed (each gate was green again afterwards, then `npm run verify` and `npm run build` green).

- Type check: `src/bad.ts` with `export const a = (x) => x;` gives `error TS7006: Parameter 'x' implicitly has an 'any' type.`
- Format: `src/bad.ts` with `export const b   =   1;` gives `[warn] src/bad.ts`, `Code style issues found`.
- Lint (and E7, the jsx-a11y plugin): `src/bad.tsx` gives `jsx-a11y/alt-text` (`<img src="x.png" />`), `no-debugger` and `@typescript-eslint/no-unnecessary-type-assertion`.
- Test: a test that fetches an URL no MSW handler covers failed (`promise rejected "TypeError: fetch failed"`), which proves `onUnhandledFrame: 'error'` stops unmocked requests and that `npm test` exits red.

### CI job `frontend`

`actions/setup-node` v7.1.0 (2026-10-08; commit `949feb2413d6458794dcd2491c4babbbce0c15c1`) with `node-version-file: frontend/.nvmrc` and the npm cache keyed on `frontend/package-lock.json`; `npm ci`; `npm run verify`; `frontend/test-results/` uploaded on failure or cancel (`actions/upload-artifact` at the commit the backend job already uses). Dependabot has an npm entry for `/frontend`, paused like the rest. Not yet seen running: the branch is pushed by the controller after the review (CLAUDE.md, "Pushing").

### Checks before the commit

- `npm run verify` in `frontend/`: green (typecheck, ESLint, Prettier, 1 test).
- `./gradlew build` in the worktree: `BUILD SUCCESSFUL` without Node on the path of the build.
- Self-review with `thermo-nuclear-code-quality-review` on the diff: no finding to fix. The only production code is `App` (7 lines) and the `createQueryClient` factory; no duplicated mechanism exists in the repository (there was no frontend), nothing fails silently (`main.tsx` throws if `#root` is missing, MSW errors on unmocked requests) and no state is half-applied. Kept deliberately thin: `queryClient.ts` has no options until Task 5 needs them.

### Findings and concerns

- `npm audit` reports 7 moderate findings, all one chain: `minimatch` 7.0.0-11.0.0 (a denial of service through glob-parts amplification) inside `@typescript-eslint/typescript-estree` 8.72.1, a lint-time development dependency. The suggested fix is a downgrade of `typescript-eslint`, so it is not applied; to be re-checked when `typescript-eslint` 8.72.2 or newer passes 14 days.
- Dependabot, when resumed, would propose TypeScript 7 and ESLint 10, which break the peers above; an `ignore` for those majors (like the Java one) is advisable then.

### Extras for the user to decide

- `ignore` of the TypeScript and ESLint major versions in the npm Dependabot entry (value: keeps its pull requests installable; cost: two lines; left out unless you approve).
