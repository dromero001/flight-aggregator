# Progress — plan 6 (dashboard), 2026-11-15

Plan 6 keeps its evidence in this file of its own, rather than in a "Dashboard run (plan 6)" section of the day's report as its Global Constraints say: its tasks run in parallel worktrees beside plans 5 and 7, which append to their own reports, and one file per plan avoids their conflicts (as plan 5's file does).

## Task 1 — evidence

Scaffold of `frontend/` (a standalone npm project, Q1-A), its quality gate (`npm run verify`) and the CI job `frontend`.

### Versions (spec 6.1)

Rule applied: the newest stable GA whose registry publication date is on or before 2026-11-01 (at least 14 days before today, 2026-11-15), no prerelease, accepting its peers. Dates come from `npm view <pkg> time --json`; Node comes from `https://nodejs.org/dist/index.json`. Every version is pinned exactly (`.npmrc` `save-exact=true`) and `package-lock.json` is committed.

| Package | Pinned | Published | Note |
|---|---|---|---|
| Node (`frontend/.nvmrc`, `engines` `^26.13.0`) | 26.13.0 | 2026-10-28 | The active LTS line (26, "Lithium"); 26.13.1 (2026-11-10) is too new (a security release, `security: true` in `index.json`; eligible from 2026-11-24) |
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
| typescript | **5.9.3** | 2025-09-30 | Corrected in fix round 1 (was 6.0.3). 7.0.2 and 6.x break `openapi-typescript` 7.13.0's peer (`typescript ^5.x`), and 7.0.2 also `typescript-eslint`'s (`<6.1.0`); the newest 5.x is 5.9.3. `msw` 3.0.3 needs `>=5.9.x`: fits |
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
| react-router | 8.5.1 | 2026-10-27 | needs react >=19.2.7 (fits); installed in Task 3. Peers react, react-dom `>=19.2.7`, @types/react, @types/react-dom `>=19.2.0` all met by 19.3.1 / 19.3.0; engines node `>=22.22.0` met by 26.13.0; `npm ls` clean |
| openapi-typescript | 7.13.0 | 2026-02-11 | peer `typescript ^5.x`: fits the pinned 5.9.3 (shown installing without `ERESOLVE`, fix round 1) |
| openapi-fetch | 0.17.0 | 2026-02-11 | |
| oidc-client-ts | 3.5.0 | 2026-03-13 | |
| @playwright/test | 1.64.1 | 2026-10-14 | |
| @axe-core/playwright | 4.14.0 | 2026-10-15 | |
| axe-core | 4.14.0 | 2026-10-05 | |
| vitest-axe | 0.1.0 | 2022-10-21 | E7; unmaintained since 2022 (peer `vitest >=0.16.0`); to re-check when the first component test uses it |
| create-vite | 9.3.3 | 2026-10-27 | used once for the template (see below) |
| @fontsource/barlow, @fontsource/barlow-condensed | 5.3.0 | 2026-07-19 | Q6's two fonts, installed in Task 3; the newest release of both (`npm view <pkg> time --json`), no peers; only weights 400 and 600 (text) and 600 (codes) are imported |

Not chosen: `create-vite` 9.3.3's `react-ts` template ships `oxlint` and `typescript ~7.0.2`; the brief and spec ask for ESLint with `typescript-eslint`, and TypeScript 7 conflicts with it, and 6.x with `openapi-typescript`, so only the template's layout (`tsconfig` split, `vite.config.ts`, `index.html`, `main.tsx`) was followed and the files were written by hand.

Deviations from the brief's text:

- `src/test/setup.ts` passes `onUnhandledFrame: 'error'`, MSW 3's name for what the brief calls `onUnhandledRequest: 'error'` (the old option does not exist in `msw@3.0.3`; `tsc` rejects it). Proven below: an unmocked request fails the test.
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
- Test: a test that fetches an URL no MSW handler covers failed (`promise rejected "TypeError: fetch failed"`), which proves `onUnhandledFrame: 'error'` stops unmocked requests and that `npm test` exits red. That message alone is also what an unreachable host gives; the cause proves MSW stopped it (re-established by a throwaway probe in the end-of-plan fix round, 2026-11-16): `[MSW] Error: intercepted a request without a matching request handler`, `Caused by: InternalError: [MSW] Cannot bypass a request when using the "error" strategy for the "onUnhandledFrame" option.`

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

- `noUncheckedIndexedAccess` in `tsconfig.app.json` (not part of `strict` nor of the template; removed in fix round 1). Value: an index read (`array[i]`, `record[key]`) is typed `T | undefined`, so a missing element is a compile error. Cost: every index read in Tasks 3-8 needs a guard, since `no-non-null-assertion` is on. Left out unless you approve.
- `ignore` of the TypeScript and ESLint major versions in the npm Dependabot entry (value: keeps its pull requests installable; cost: two lines; left out unless you approve).

## Task 1 — fix round 1

Review: functional CHANGES REQUESTED, code APPROVED (`task-1-review.md`). Two findings fixed; the minors wait for the end-of-plan review.

- F1: `typescript` pinned 6.0.3 refused `openapi-typescript@7.13.0` (peer `^5.x`, `ERESOLVE`). Step 1's peer rule applied to the whole package set gives `typescript` 5.9.3 (published 2025-09-30, far over 14 days, newest 5.x: `npm view typescript time --json`). No `overrides`. The version table and the reason above are corrected. Evidence: in a scratch copy of the new `package.json` and lockfile (nothing added to `frontend/package.json`, the brief does not list it), `npm install --save-dev --save-exact --package-lock-only openapi-typescript@7.13.0` succeeds with no `ERESOLVE` and leaves `typescript` 5.9.3. In `frontend/`: `rm -rf node_modules && npm ci`, `npm run verify` (typecheck, ESLint, Prettier, 1 test passed) and `npm run build` all green.
- F2: `noUncheckedIndexedAccess` removed from `tsconfig.app.json` and listed under "Extras for the user to decide" with its cost and benefit.
- Checks before the commit: `npm run verify` green; `./gradlew build` and the thermo-nuclear self-review of the diff are recorded in the report (a version pin, one flag removed, doc edits: no finding).

### User decisions (2026-11-15, after Task 1)

- **ESLint stays on 9** (9.39.5) with the original `eslint-plugin-jsx-a11y` 6.10.2 (E7). ESLint 9 is out of support since 2026-08-06, and ESLint 10 is the newest major, but `eslint-plugin-jsx-a11y`'s peer range does not accept it; the only plugin that does is a little-known fork (`eslint-plugin-jsx-a11y-x`). The user kept the original plugin: the linter runs only while developing and never ships. This is the recorded deviation from §6.1's newest-stable rule for this one package.
- **`noUncheckedIndexedAccess` stays off.** The listed extra is rejected: TypeScript `strict`, as the plan says.

## Task 3 — evidence

Worktree branch `worktree-agent-a156d29a52ec86545`, from `main` at `4096c26`. Packages added (exact pins, lockfile committed, table above): `react-router` 8.5.1, `@fontsource/barlow` 5.3.0, `@fontsource/barlow-condensed` 5.3.0.

### Red → green, one test at a time

`searchParams.test.ts`
- `'round-trips every filter and the sort'`: red `Failed to resolve import "./searchParams"` (module missing). Green with a first `parseSearch`/`toSearchParams`.
- `'normalises codes and amounts'`: red `expected { Object (sort, origin, ...) } to deeply equal { origin: 'MAD', …(4) }` (no trim or upper-case yet, only `duration` accepted as sort). Green with `normalise` and the sort whitelist.
- `'reports each invalid value as a field error'`: red `expected {} to deeply equal { …(4) }`. Green with the per-field rules (plan 5 Task 9's regexes; "above 0" is checked as "has a non-zero digit", so no number conversion of a price). First run also showed a mistake in my test (`IBX` is a valid 3-letter airport); the destination in the test became `M1D`, `IBX` stays as the invalid carrier.
- `'falls back to price for an unknown sort'`: passed on first run (the whitelist already existed), so shown red by mutation: replacing the whitelist with `if (sort) criteria.sort = sort as SortOrder` gives `expected { sort: 'best' } to deeply equal { sort: 'price' }`; restored, green.

`FilterBar.test.tsx`
- `'submits the four filters'`: red `Failed to resolve import "./FilterBar"`. Green with the form (uncontrolled inputs read through `FormData`; the page normalises).
- `'labels every control'`: passed on first run, red by mutation (the "To" label text removed): `Unable to find an accessible element with the role "textbox" and name "To"`; restored.

`SortTabs.test.tsx`
- `'changes the sort with the arrow keys'`: red `Failed to resolve import "./SortTabs"`. Green with a `<fieldset>` and three controlled radios sharing one `name` (native arrow-key behaviour, no key handler).

`SearchPage.test.tsx` (memory router)
- `'writes a submitted search to the URL'`: red `Failed to resolve import "./SearchPage"`. Green with `useSearchParams` (`setSearchParams` pushes an entry); the submitted values go through `parseSearch` so the URL holds `MAD`, not `mad`.
- `'restores the previous search on back'`: red `expect(element).toHaveValue(MAD)` (the uncontrolled input kept the typed text after Back). Green with a `key` on `FilterBar` built from the filters only, so Back and Forward remount it while a sort change does not discard unsent typing.
- `'shows invalid URL values as field errors'` (Review Focus 4): red `expect(element).toBeInvalid()`. Green with `aria-invalid`, `aria-describedby` and a visible message per field (text, so colour is not the only signal). The invalid values stay in the inputs.

`App.test.tsx` (the Task 1 smoke test, made meaningful): now sets the address to `/?origin=MAD&sort=duration`, and requires the h1, the "From" input (value `MAD`) and the checked "Duration" radio inside `<main>`, plus the `banner`. Red `Unable to find a label with the text of: From`; green with the router and the page.

### Task 1's deferred minors
- `QueryClient`: created inside `App` with `useState(createQueryClient)`, so each mounted app and each test has its own cache.
- Smoke test: the h1 is asserted inside `main`; it no longer passes by accident, since it needs the router, the page and the URL state.

### Choices to review
- The top bar says "Flight aggregator" (a text Q7 does not list; Q6 asks for a top bar). Change it if you want other wording.
- Sort tabs: the radios are visually hidden and their labels are drawn as tabs (focus ring on the label); they stay native radios.
- Only the weights used are imported: Barlow 400 and 600, Barlow Condensed 600. Task 4 may add weights for times and codes.
- `SearchFilters` (`Omit<SearchCriteria, 'sort'>`) is exported as well: it is `FilterBar`'s `onSubmit` argument.
- No component test for the stacking under 640 px: jsdom does not evaluate media queries; Task 10's phone test checks it (as the brief says).

### Checks before the commit
- `npm run verify` in `frontend/`: green (typecheck, ESLint, Prettier, 11 tests in 5 files); `npm run build` green.
- `./gradlew build`: `BUILD SUCCESSFUL`.
- Self-review (thermo-nuclear): no existing search-state mechanism in the repository (there was no frontend); the URL is the only state, no duplicate copy; invalid values never fail silently (each gets a message); one rule table drives the validation, the form and the sort are data-driven lists. Accepted minor: the airport message and regex are shared by two table rows via a constant for the message only.

### Extras for the user to decide
- None found in this task.

## Task 3 — fix round 1

Review: functional and code CHANGES REQUESTED (`task-3-review.md`). F-1 and C-1 fixed; the minors (F-2 to F-5, C-2 to C-5) are deferred to the end-of-plan review.

- F-1, `'restores the previous sort on back'`: passed on first run (the sort already pushes), so shown red by mutation: `setParams(..., { replace: true })` in `sortBy` gives `expect(element).toHaveTextContent()` failing (Back stays on `?origin=MAD&sort=duration`); restored, green.
- C-1 focus, `'keeps the focus in the form after a search changes the URL'`: red `expect(element).toHaveFocus()` (the re-keyed form remounted, focus on `BODY`). Green: the `key` is gone; `FilterBar` holds the four inputs as controlled drafts and resets the draft during render when the URL's filters change (Back, Forward, a normalised value such as `mad` to `MAD`); a sort change does not touch the URL's filters, so unsent typing stays. `'restores the previous search on back'` stays green.
- C-1 announcement, `'announces an error that appears after a submit'`: red `expected null not to be null` (the message was a plain `<p>` added after the submit). Green: each field has an `aria-live="polite"` region that is always in the page, with the message inside when there is one; `aria-invalid` and `aria-describedby` are unchanged (describedby points at the region only while invalid). `.error:empty` has no margin so empty regions take no space.
- Checks: `npm run verify` green (typecheck, ESLint, Prettier, 14 tests in 5 files), `npm run build` green, `./gradlew build` BUILD SUCCESSFUL.
- Self-review (thermo-nuclear): the draft state is the one copy of unsent typing and the URL stays the only source of the search; no field list was added (the draft is built from the same four fields). C-2's double build in `search()` was not touched (the key's string is gone). Not tested: that a sort change keeps unsent typing (it follows from the render-time sync comparing only the filters).

## Task 9 — evidence

### Image tags and digests (spec 6.1)
| Image | Tag | Multi-arch index digest | Age |
|---|---|---|---|
| `node` (build stage) | `26.13.0-alpine3.24` | `sha256:8996bb5821d62a8fd9bb27c1cb07462f3fdce6e47d640c6a881ead0b064ce216` | Node 26.13.0 released 2026-10-28 (nodejs.org/dist/index.json, 18 days); the tag's image was last pushed 2026-11-05 (10 days, Docker Hub tags API) |
| `nginx` (runtime) | `1.30.5-alpine3.24` | `sha256:0985e772fb9f729e6fa0980da05fca5d9c468e870eed43071545afa9d2e27d94` | pushed 2026-09-26 (50 days); 1.30 is the stable line (`stable-alpine` is 1.30.6, pushed 2026-11-13, 2 days: too new) |
| `@playwright/test` | 1.64.1 | npm | 2026-10-14 (32 days); 1.65.0 (2026-11-05) and 1.65.1 (2026-11-13) too new |

How checked: Docker Hub tags API (`last_updated` and `digest` per tag) and `docker buildx imagetools inspect <tag>` (the digest is the image index's, so it holds for amd64 and arm64). Controller's ruling on the `node` tag (ledger): section 6.1's 14-day rule applies to the release behind the tag (Node 26.13.0, 18 days old, the active LTS), and the tag's current multi-arch digest (an Alpine rebuild 10 days old) is pinned; the same reading holds for nginx 1.30.5.

### Red to green: `smoke.spec.ts` `'serves the dashboard and proxies the API'`
- Red, stack without `frontend` (`app`, `keycloak`, `postgres`, `mock-gds` up under `-p t9`; `globalSetup` skipped with a throwaway config): `page.goto: net::ERR_CONNECTION_REFUSED at http://localhost:3000/`.
- Green for what exists, with the image and the service: the h1 "Find flights", `GET /admin` equal to `GET /` (index.html), `/config.json` as `application/json` naming `dashboard`. The red then moved to the next assertion, see below.
- `/api/` reaches Spring, not nginx: `curl -i localhost:3000/api/itineraries` answers `404`, `Content-Type: application/json`, with Spring Security's headers (`X-Content-Type-Options`, `Cache-Control: no-cache, no-store...`) and the same body as the app on :8080 (`{"timestamp":...,"status":404,"error":"Not Found","path":"/api/itineraries"}`); nginx's own 404 would be HTML. `/api/nothing` answers 401 (the app's security).
- Health check: first run `unhealthy`; `wget` resolves `localhost` to `::1` and nginx listened on IPv4 only (the image's IPv6 script does not edit the file as non-root). Fixed with `listen [::]:8080;`; the service is healthy and runs as uid 101 (`nginx`).

### Pending plan 5 Task 9 (not weakened)
- The smoke assertion that `/api/itineraries` answers a page of itineraries (200, JSON, at least one itinerary) fails today: `Expected: 200, Received: 404`.
- `globalSetup`'s poll fails for the same reason: `Error: No itinerary after 180 s (last answer: HTTP 404). Is the first ingestion done?` Their green run is pending plan 5 Task 9 (the search reaching `main`).
- The `search`, `re-price`, `accessibility`, `admin` and `rate-limit` projects are declared in the order of the decisions table; their spec files come with the later tasks.

### Other changes
- `vite.config.ts`: Vitest excludes `e2e/` (it picked up the Playwright spec). `tsconfig.node.json` includes `playwright.config.ts` and `e2e`, so typecheck and lint cover them.
- Checks: `npm run verify` green (14 tests in 5 files), `npm run build` green, `docker build frontend` green, `./gradlew build` BUILD SUCCESSFUL.
- Self-review (thermo-nuclear): the compose service, the template and the health check follow the existing patterns; the repository had no templated-config mechanism to reuse (the image's own `/etc/nginx/templates` is the canonical one); nginx's variables survive the envsubst only because they are not environment variables (commented in the template). Nothing fails silently: `globalSetup` reports the last answer when it gives up.

### Extras for the user to decide
- None built. Not done on purpose: nginx security and caching headers (E5, rejected).

## Task 9 — fix round 1

Review: functional APPROVED, code CHANGES REQUESTED (`task-9-review.md`). C-1 fixed; the minors (F-1, C-2 to C-5) are deferred to the end-of-plan review. The node tag question is closed by the controller's ruling above.

- C-1, the environment values were pasted raw into `/config.json`'s JSON and into nginx's `set`. Controller's ruling: keep the template, validate in the image's entrypoint hook. Test: `frontend/nginx/test-config-validation.sh <image>` (`docker run` cases; the `images` job of CI builds the image as `frontend-image` and runs it).
  - Red, before the script existed: `FAIL: expected a refusal naming OIDC_AUTHORITY (exit 0)` for `OIDC_AUTHORITY='http://x/"y'` and the same for `OIDC_CLIENT_ID='a$uri'` (the container kept running); the compose values case was already `ok`.
  - Green with `frontend/nginx/10-validate-config.sh` in `/docker-entrypoint.d/` (POSIX `sh`, runs before `20-envsubst-on-templates.sh`): `API_UPSTREAM` and `OIDC_AUTHORITY` must be `http(s)://` plus `[A-Za-z0-9._~:/?#@!&=+,%-]`, `OIDC_CLIENT_ID` `[A-Za-z0-9._-]+`; a bad or missing value prints `10-validate-config.sh: OIDC_AUTHORITY is missing or not valid (expected ...)` and exits 1. All three cases `ok`.
- Checks: `npm run verify`, `npm run build`, `docker build frontend` and `./gradlew build` green.
- Self-review (thermo-nuclear): the entrypoint hook is the image's own extension point; the allowed-character set is one whitelist per kind, shared by the two URLs; a failure is loud and names the variable. The test script is the only addition beyond the ruling (it is what the ruling asked for).

## Task 9 — fix round 2

Re-review: C-1 not addressed (a line break bypassed the validation; `#` in the upstream).
- Line break: `grep` matches per line, so `OIDC_AUTHORITY=$'http://x\n"y'` passed. Test cases added to `test-config-validation.sh`, which now also requires the log line to come from `10-validate-config.sh` itself. Red: `FAIL: expected a refusal naming OIDC_AUTHORITY (exit 1)` and the same for `OIDC_CLIENT_ID='dash\nboard'` (nginx died later, or the validation named nothing). Green: `check` rejects any value containing a newline or carriage return before the pattern is applied, so the pattern sees the whole value.
- `#`: `API_UPSTREAM=http://app:8080#x` passed (red: `FAIL: expected a refusal naming API_UPSTREAM (exit 0)`). `API_UPSTREAM` reaches nginx unquoted in `set`, where `#` starts a comment, so it is no longer allowed there. `OIDC_AUTHORITY` only reaches the quoted JSON string, where `#` is plain text, so it keeps it (two patterns, commented in the script).
- All six cases of the script `ok`; `npm run verify`, `npm run build`, `docker build frontend` and `./gradlew build` green. Self-review (thermo-nuclear): the two URL patterns differ only by `#`, each with its reason in a comment; no new mechanism.

### User decision (2026-11-15, after Task 3)

- **The top bar's title is "Flight aggregator".** Q7 did not list it; the user kept it (`App.tsx`).

## Task 2 — evidence

Worktree branch `worktree-agent-a8e417b828cfc77d3`, from `main` at `9e6674e` (plan 6 Tasks 1, 3 and 9 integrated). Started on plan 5 Task 9's contract, as the line under the task's title says: `types.ts` aliases only the search's schemas (`Itinerary` = `ItineraryResponse`, `Leg`, `SearchResponse`); `RepriceResponse`, `StatusReport` and `ProviderStatus` come with Tasks 6 and 8.

### Versions (spec 6.1), checked on 2026-11-15
`npm view <pkg> time --json` and `dist-tags`, cut-off 2026-11-01 as in Task 1's table:

| Package | Pinned | Published | Note |
|---|---|---|---|
| openapi-typescript (dev) | 7.13.0 | 2026-02-11 | `latest`; no newer release. Peer `typescript ^5.x` met by 5.9.3 (`npm ls`: deduped, no `ERESOLVE`) |
| openapi-fetch | 0.17.0 | 2026-02-11 | `latest`, no newer release; the project has no 1.x yet. Its one dependency, `openapi-typescript-helpers` 0.1.0, is from the same day |

`npm audit`: the same 7 moderate findings as Task 1 (the `minimatch` chain under `typescript-eslint`), none from these packages.

### Red → green, one test at a time

`schema.test.ts` `'committed types match the contract'` (Node environment): generates in memory with `openapi-typescript`'s Node API (`COMMENT_HEADER + astToString(await openapiTS(contract))`, what the CLI writes), formats it with Prettier and the repository's settings, and compares it with the committed file.
- Red (no file): `` AssertionError: src/api/schema.d.ts is missing or out of date with docs/architecture/openapi.json: run `npm run api:generate`: expected undefined to be '/**\n * This file was auto-generated …' ``.
- Green after `npm run api:generate` (`openapi-typescript ../docs/architecture/openapi.json -o src/api/schema.d.ts && prettier --write src/api/schema.d.ts`). Running the script again leaves the file byte-identical (`cmp`).
- Red by mutation, one character in `schema.d.ts` (`inventoryChanged` → `inventoryChanges`): the same message, diff `- inventoryChanged: boolean;` / `+ inventoryChanges: boolean;`; restored, green. Also red by a contract mutation (`flightNumber` → `flightNo` in `openapi.json`): diff `- flightNo?: number;` / `+ flightNumber: number;`; restored.
- A first version read the committed file through `import.meta.glob(..., { query: '?raw', eager: true })`. It went green, then red again with the file in place: Vitest 5's module cache (`node_modules/.vitest-cache`) kept the transformed test with the glob's old result (no file), and deleting the cache made it green. A cache that can keep an old copy could also give a false green on drift, so the test now reads the file with `node:fs` when it runs. That needs Node's types, so the test is type-checked by `tsconfig.node.json` (`include`) and excluded from `tsconfig.app.json`; the app's code still has no Node globals.

`apiError.test.ts`
- `'reads the problem detail and Retry-After'` (429 `application/problem+json`, `Retry-After: 17`): red `Failed to resolve import "./apiError"`. Green with `ApiError` and `toApiError` reading the JSON body and `Number(Retry-After)`.
- `'leaves the detail empty for a body that is not a problem'` (502 `text/html`, nginx's page while the app is down): red `SyntaxError: Unexpected token '<', "<html><bod"... is not valid JSON`. Green: the body is parsed only when `Content-Type` is `application/problem+json`, and a missing `Retry-After` stays `undefined`. The second change shown red by mutation: `Number(retryAfter)` without the `null` check gives `AssertionError: expected +0 to be undefined`; restored, green.

`types.ts`, `client.ts`, `fixtures.ts` have no behaviour of their own; the compiler is their check. `fixtures.ts` was written first: `tsc -b` red `TS2307: Cannot find module '../api/types'`, green with `types.ts`. Typed from the schema: `flightNumber: "117"` gives `TS2322: Type 'string' is not assignable to type 'number'` (restored). The fixtures follow the brief: `direct()` IB 3166 MAD 08:15+01:00 → LHR 09:40Z; `connecting()` the same leg, 1 h 10 min in London, BA 117 LHR 10:50Z → JFK 13:40-05:00 (685 min); Friday 20 November 2026.

### CI and the other changes
- `ci.yml`, job `frontend` only: step "Contract drift" runs `npm run api:check` (`vitest run src/api/schema.test.ts`) after `npm run verify`, the types' half of §6.11 step 3; the backend job's `OpenApiContractTest` is the contract's half. `api:check` exits 1 on the mutated file and 0 restored. Not yet seen running in CI (the controller pushes after the review).
- `eslint.config.js`: `src/api/schema.d.ts` ignored. It is generated, and `typescript-eslint`'s stylistic rules flag its index signatures (`consistent-indexed-object-style`, 3 errors); Prettier still checks it, and the drift test guards its content.
- The image never regenerates (Docker's context is `frontend/`): `docker build frontend` green with the committed file.

### Checks before the commit
- `npm run verify` in `frontend/`: exit 0 (typecheck, ESLint, Prettier, 17 tests in 7 files).
- `./gradlew build`: exit 0, `BUILD SUCCESSFUL`.
- Self-review (thermo-nuclear): `ApiError`'s constructor takes one named object instead of two optional positional arguments, and the IB 3166 leg the two fixtures share is one function; no other finding. No existing mechanism to reuse (no API code in the frontend before); nothing fails silently (a missing or stale `schema.d.ts` names the command that fixes it); no state.

### Concerns for the later tasks
- **`toApiError` and `openapi-fetch` (Task 5).** On a non-2xx answer `openapi-fetch` reads the body itself (`response.text()`, then `JSON.parse`) before it returns `{ error, response }`, so `toApiError(response)` on that `response` would fail with a used body. The plan's interface still works if Task 5 calls it before the client reads the body: an `onResponse` middleware that throws `await toApiError(response)` for a non-2xx answer (queries then reject with `ApiError`), or `toApiError(response.clone())`. Not built: the plan gives Task 2 no consumer.
- **`baseUrl: ''` in component tests (Task 5).** In Vitest (Node's `fetch` under jsdom) a relative URL cannot be parsed: a throwaway probe of `api.GET('/api/itineraries', …)` against an MSW handler failed with `TypeError: Failed to parse URL from /api/itineraries?origin=MAD`. It works in the browser. Task 5 needs an absolute base in tests, for example `baseUrl: window.location.origin` (the page's own origin in the browser, `http://localhost:3000` in jsdom); the plan fixes `''`, so the choice is left to Task 5 and its reviewer.
- `Retry-After` is read as whole seconds, as plan 5 sends it (Q9); an HTTP-date form would become `NaN`. Nothing in the stack sends one.

### Extras for the user to decide
- None built. A possible simplification, not taken because the plan names the test: `openapi-typescript --check` compares the generated types with the file itself, so if `schema.d.ts` were left unformatted (in `.prettierignore`), `api:check` could be that one CLI call and `schema.test.ts` would go. Value: one file and the Node-test `tsconfig` exception less. Cost: the generated file stops following Prettier. Left out unless you approve.

## Task 2 — review

The double review (`.superpowers/sdd/2026-11-15-dashboard/task-2-review.md`) answered functional APPROVED and code APPROVED, with no Critical or Important finding. The reviewer ran `npm run verify`, `npm run api:check` and `./gradlew build` in a scratch clone (green), showed the drift test red on a changed `schema.d.ts`, a changed contract and a deleted file with Vitest's cache warm, and confirmed both pins with `npm view`. Both of the implementer's concerns are real and belong to Task 5 (the plan is amended under Tasks 5, 6 and 7): `baseUrl: ''` cannot be parsed under jsdom, and `toApiError` must run in an `onResponse` middleware, before `openapi-fetch` reads the body. Six Minors wait for the end-of-plan review (a Minor opens no round of its own):
- F-1: `npm run verify` already runs the drift test, so on drift the "Contract drift" step never runs; move it before `npm run verify` (`ci.yml:64-69`).
- F-2, F-3: the base URL and where `toApiError` is called; covered by the plan amendments.
- C-1: a `Retry-After` that is not whole seconds (an HTTP date) becomes `NaN` (`apiError.ts:37`).
- C-2: an invalid `problem+json` body makes `toApiError` reject with a `SyntaxError`; the media-type test is case-sensitive (`apiError.ts:31-32`).
- C-3: the drift test resolves Prettier without `.editorconfig`, unlike the CLI that writes the file; the path is built with `.pathname` instead of `fileURLToPath` (`schema.test.ts:15-16`).

## Task 4 — evidence

Worktree branch `worktree-agent-a7d9badffb86559a3`, from `main` at `59cdf88` (plan 6 Tasks 1, 2, 3 and 9 integrated). No new dependency. Files: `src/itinerary/format.ts`, `ItineraryCard.tsx`, `ItineraryCard.module.css` and their two tests; no shared file changed.

### Red → green, one test at a time

`format.test.ts`
- `'shows the airport's local time whatever the browser's zone'` (Review Focus 2; the browser's zone is `Pacific/Auckland`, 18 hours ahead of New York): red `Failed to resolve import "./format"`. Green with `localTime` and `localDate` reading the wall clock written before the offset (`^(\d{4}-\d{2}-\d{2})T(\d{2}:\d{2})`) and formatting the date as a UTC midnight. Shown red by two mutations, each restored: formatting `new Date(iso)` in the browser's zone gives `expected '02:15' to be '08:15'`, and the date without `timeZone: 'UTC'` gives `expected 'Sat 21 Nov' to be 'Fri 20 Nov'`. The zone is set with `vi.stubEnv('TZ', …)` and restored with `vi.unstubAllEnvs()` instead of the plan's `process.env.TZ = …`: same effect (Node applies `TZ` on assignment, as the mutations show), but the app's `tsconfig` has no Node types, and the restore cannot leave the string `"undefined"` behind.
- `'formats durations'` (`445` → `7h 25m`, `45` → `45m`, `120` → `2h`): red `TypeError: duration is not a function`; green.
- `'formats euro prices from decimal strings'` (`'1234.50'` → `€1,234.50`, `'412.6'` → `€412.60`, `'129'` → `€129.00`): red `TypeError: price is not a function`; green with one `Intl.NumberFormat('en-GB', EUR)` that formats the decimal string itself (ES2023), so no amount goes through a binary number.
- `'describes freshness'` (30 s, 4 min, 61 min → "Updated just now", "Updated 4 min ago", "Updated 1 h ago"): red `TypeError: freshness is not a function`; green.
- `'computes layovers across time zones'`: the plan's case (LHR in at `09:20Z`, out at `10:30+00:00` → 70), plus the same arrival written as `10:20+01:00`, whose wall clocks differ by only 10 minutes, and a direct flight (no layover). Red `TypeError: layovers is not a function`; green with the difference of the two instants. The extra case shown red by mutation: subtracting the wall clocks gives `expected [ { airport: 'LHR', minutes: 10 } ] to deeply equal [ { airport: 'LHR', minutes: 70 } ]`; restored. (The plan's case alone passes that mutation: `Z` and `+00:00` are the same offset.)
- `'detects a multi-airline itinerary'` (`connecting()` yes, `direct()` no, two IB legs no): red `TypeError: isMultiAirline is not a function`; green with a `Set` of carriers.

`ItineraryCard.test.tsx`
- `'shows a direct flight'` (`direct()`, five minutes after its quote): red `Failed to resolve import "./ItineraryCard"`. Green with an `<article>`: `<time dateTime>` for departure (08:15) and arrival (09:40) and for the date ("Fri 20 Nov", the departure's instant), MAD and LHR, "2h 25m", "Direct", "IB 3166", "€129.90", "Updated 5 min ago", no "Multiple airlines".
- `'shows each stop with its airport and layover'`: `connecting()` shows "1 stop: LHR, 1h 10m" (Q6's wording) and one dot on the route line; the same trip on to Boston shows "2 stops: LHR, 1h 10m; JFK, 1h 20m" and two dots. Red `Unable to find an element with the text: 1 stop: LHR, 1h 10m`; green with the stops summary from `layovers` and one dot per layover.
- `'marks a multi-airline itinerary'` ("IB 3166, BA 117" and "Multiple airlines"): red `Unable to find an element with the text: Multiple airlines`; green.

### Choices to review
- **A whole number of hours reads "2h"**, not "2h 0m" (the plan gives only "7h 25m" and "45m").
- **The stop count comes from the legs** (`layovers(legs).length`), not from the API's `stops` field, so the count and the listed airports cannot disagree.
- **Two or more stops are joined with "; "**: "2 stops: LHR, 1h 10m; JFK, 1h 20m" (Q6 shows only one stop).
- **The card's content is phrasing content only** (`span` and `time` inside two `div`s), so Task 6 can turn the pass and the stub into its disclosure `<button>` without moving block elements out of a button.
- **The route line is decorative** (`aria-hidden`); a visually hidden " to " between the codes makes it read "08:15 MAD to LHR 09:40".
- **A malformed date-time throws** (`Not an ISO-8601 date-time: …`) rather than showing something wrong; the contract types every time as `date-time`. A malformed amount shows `€NaN`.
- **Look (Q6):** a flat card with a 1 px border; the stub is torn off along a dashed perforation with a notch at each end, beside the pass on wide screens and under it below 640 px. Times and the price in Barlow Condensed 600 (the only weight imported), codes in tarmac, the route line and its stop dots in ink. Checked by eye in a throwaway Vite page with Playwright screenshots at 960 and 390 px (not committed).

### Checks before the commit
- `npm run verify` in `frontend/`: exit 0 (typecheck, ESLint, Prettier, 26 tests in 9 files).
- `./gradlew build`: exit 0, `BUILD SUCCESSFUL`.
- Self-review (thermo-nuclear): no existing formatting helper in the frontend to reuse (Tasks 1 to 3 format nothing); one `minutesBetween` serves freshness and layovers, one `duration` serves the total and each layover; the layover shape became a named `Layover` type instead of `ReturnType<typeof layovers>`. The one cast (`amount as Intl.StringNumericLiteral`) is what lets Intl take the decimal string; nothing holds state.

### Extras for the user to decide
- **E1 ("+1" next-day arrival) and E2 (airline names) are approved but no task builds them.** Both belong on this card; the plan's Task 4 does not list them, so they are not built here. E2 also needs a carrier catalogue the API does not expose yet. The controller decides which task takes them.

## Task 4 — review

The double review (`.superpowers/sdd/2026-11-15-dashboard/task-4-review.md`) answered functional APPROVED and code APPROVED, with no Critical or Important finding. The reviewer ran `npm run verify` in a scratch clone (26 tests, green), confirmed that `vi.stubEnv('TZ', …)` changes the zone for `Date` and `Intl` under Vitest's default pool with the machine set to New York, UTC and Madrid, saw the zone mutations go red, confirmed the added layover case is needed (the plan's own case uses two equal offsets), checked exact price formatting with a 19-digit value and the card at 960 and 390 px against Q6. The implementer's choices were judged harmless (the backend writes `stops` as legs minus one). E1 and E2 were rightly left out: no task assigns them. Six Minors wait for the end-of-plan review:
- F-1: under Vitest's `threads` or `vmThreads` pool the zone stub has no effect; assert the zone inside the test.
- F-2: the price test also passes with `Number(amount)`, so "no float" is not pinned.
- F-3: the freshness text wraps at 960 px; the notches do not cut through the border as the CSS comment says.
- C-1: a malformed date throws (no error boundary) while a malformed amount shows `€NaN`; pick one policy.
- C-2: the stops text and the "IB 3166" label are built in the component, not in `format.ts`; Task 6 needs the label again.
- C-3: the visually hidden class lives in the card's CSS module only.

## Task 5 — evidence

Worktree branch `worktree-agent-acab2caad1e1455b0`, from `main` at `986f8eb` (plan 6 Tasks 1 to 4 and 9 integrated). No new dependency for Task 5 itself: `@tanstack/react-query` 5.105.1 and `msw` 3.0.3 were already installed from Task 1's table. Files: `src/search/useItinerarySearch.ts`, `mergePages.ts`, `ResultsList.tsx`, `ResultsList.module.css`, `src/itinerary/useNow.ts` and their tests; modified `src/search/SearchPage.tsx`, `src/queryClient.ts`, `src/api/client.ts` (the amendment after Task 2's review), `src/test/setup.ts` and `src/App.test.tsx`.

### Red → green, one test at a time

`mergePages.test.ts`
- `'appends only itineraries not already shown'` (Review Focus 3): red `Failed to resolve import "./mergePages"`. Green with a `Map` keyed by id that keeps the first occurrence. Shown red by mutation too (the key made unique per row, so nothing is skipped): `AssertionError: expected [ …(4) ] to deeply equal [ …(3) ]`; restored.
- `'flags a changed inventory from any page'` (and a later unflagged page does not clear it): red `AssertionError: expected false to be true` (the flag was still a constant). Green with `pages.some((page) => page.inventoryChanged)`.

`SearchPage.test.tsx` (MSW; `renderAt` now wraps a `QueryClientProvider` with `createQueryClient()`, its retry wait set to 0 so a retried test does not sleep; a default handler answers an empty page, since every page searches as it opens)
- `'shows the loading state'` (the answer held with `delay('infinite')`): red `Unable to find an accessible element with the role "region" and name "Flights"`. Green with `useItinerarySearch` and `ResultsList`: a `<section aria-label="Flights" aria-busy>` and a `role="status"` line "Loading flights…".
- `'shows the first page and how many flights are shown'`: red `toHaveTextContent: Expected "Showing 20 flights", Received ""`. It stayed red after the count was written, with "Loading flights…" forever. A throwaway probe showed why: `TypeError: fetch failed … ECONNREFUSED 127.0.0.1:3000`, while a plain `fetch` to the same URL was answered by MSW. `openapi-fetch` keeps the `fetch` it finds when the client is created (`fetch: baseFetch = globalThis.fetch`, `dist/index.mjs:12`), and `src/api/client.ts` was imported before `beforeAll` started MSW. Fix in the test harness, not the app: `setup.ts` calls `server.listen()` when it loads, before the test file is imported. Green with `baseUrl: window.location.origin`, the cards in an `<ol>` and "Showing N flights".
- `'loads more until the last page'` (the second page held by a promise, so the button is seen reading "Loading…"; then "Showing 40 flights", 40 cards, no "Load more" once `nextCursor` is null): red `Unable to find role="button" and name "Load more"`. Green with the button while `hasNextPage`, disabled and labelled "Loading…" while the next page loads.
- `'searches again when the sort changes'` (`sort=duration` and `origin=MAD` in the second request): passed on its first run (the key already held the criteria), so shown red by mutation: the key `['itineraries', criteria.origin]` gives `AssertionError: expected [ …(1) ] to have a length of 2 but got 1`; restored.
- `'shows the empty state'`: red `Unable to find an element with the text: No flights match these filters. Remove a filter or raise the maximum price.`. Green: the status line says it instead of "Showing 0 flights", and there is no "Load more".
- `'shows the API's detail for a rejected filter'` (400 `application/problem+json`): red `Unable to find role="alert"`. With the alert and its message added it stayed red for the reason the plan's amendment names: `Expected: The cursor does not belong to this search; start again from the first page. Received: Flights could not be loaded. Search again in a moment.` (no `ApiError` reached the query). Green with the `apiErrors` `onResponse` middleware in `client.ts`, which throws `await toApiError(response)` for every non-2xx answer before `openapi-fetch` reads the body.
- `'shows a failure without a problem body'` (nginx's HTML 502): passed on its first run (the generic sentence was the fallback), so shown red by mutation: `if (error instanceof ApiError) return error.detail ?? error.message` gives `Received: HTTP 502`; restored. After the retry rule existed, the test also asserts the 5xx was retried (4 requests); mutation `retry: false` gives `AssertionError: expected 1 to be 4`; restored.
- `'does not retry a rate-limited search'` (Review Focus 1; `Retry-After: 30`): red on the message (`Received: Flights could not be loaded. Search again in a moment.`), then, with the message, red for the reason Review Focus 1 names: `AssertionError: expected 4 to be 1` (TanStack's default retried the 429 three times). Green: `createQueryClient()` retries a query (TanStack's count, 3) only when the error is not an `ApiError` below 500, so a network error or a 5xx; mutations keep TanStack's default of no retry.
- `'does not search while a filter is invalid'` (Review Focus 4; `?origin=MADR&maxPrice=-1&sort=best`): red `AssertionError: expected 1 to be +0`. Green: `SearchPage` enables the search only when there is no field error; no "Loading flights…" either (the loading state reads `isLoading`, not `isPending`, which a disabled query also is).
- `'offers a reload when the inventory changed'` (the second page flagged, repeating one row of the first): red `Unable to find an element with the text: Results updated.`. Green: "Results updated." with "Reload" in an `aria-live="polite"` region that is always in the page; the repeated row is skipped ("Showing 39 flights"); "Reload" calls `queryClient.resetQueries` on the search's key, so the cursors requested are `[null, 'page-2', null]` and the list is back to "Showing 20 flights" without the notice. Shown red by mutation as well: `refetchQueries` instead of `resetQueries` (it reads both pages again) gives `Unable to find an element with the text: Showing 20 flights`; restored.

Added beyond the plan's list (guards for what Q7 and the code already have, not features):
- `'counts a single flight in the singular'` (Q7's "Showing 1 flight"): red `Unable to find an element with the text: Showing 1 flight`; green with the singular.
- `'shows the failure for a rate limit without a wait'` (a 429 without `Retry-After`, which a proxy could send): passed on its first run (the guard came with the 429 message), shown red by mutation (the `retryAfterSeconds !== undefined` check removed): `Received: Too many searches from your connection. Try again in undefined seconds.`; restored.
- `'keeps the freshness true on a page left open'` (only `Date`, `setInterval` and `clearInterval` faked): red `Unable to find an element with the text: Updated 6 min ago` (the page's clock was taken once). Green after Step 3, with `ResultsList` reading `useNow(60_000)`.
- `'does not reload the results by itself when the window regains focus'`: red `AssertionError: expected 2 to be 1` (TanStack refetches a stale query on focus; for an infinite query that reads every loaded page again). Green with `staleTime: Infinity` on the search. My reading of Q4-A, for the reviewer: the user chose that the page *offers* a reload and rejected an automatic one (C), and every refetch also spends the per-address limit; a sort or filter change still searches at once (a new key), and "Reload" still reads the first page again.

`useNow.test.ts`
- `'ticks every minute'` (fake timers: unchanged at 59 s, a new time at 60 s and 120 s): red `Failed to resolve import "./useNow"`. Green with `useState` and a `setInterval` cleared on unmount.

`App.test.tsx`: given a handler for the search the page now sends as it opens.

### Choices to review
- **`server.listen()` at the top of `setup.ts`** instead of in `beforeAll` (reason above). The alternative was `createClient({ fetch: (request) => fetch(request) })` in the app, which reads the global on each call; I kept the app as the plan writes it.
- **The 2xx-without-body guard** in `useItinerarySearch`: `openapi-fetch` returns `data: undefined` for a 2xx with `Content-Length: 0`; the query then fails with an `Error` (the generic failure sentence) rather than rendering nothing. Not reachable from the contract, so it has no test.
- **"Load more" is disabled while the next page loads** (TanStack's documented pattern), so a second click cannot cancel and restart the request.
- **The status line holds "Loading flights…", the empty sentence and "Showing N flights"** in one `role="status"` region that is always in the page; failures are `role="alert"`; the inventory notice has its own polite region. Spacing uses margins on non-empty children so the empty regions take no room.
- **Look (Q6):** the failure is hold-red text and border on white, the notice an ink border with "Reload" as a plain bordered button, "Load more" centred under the list. Checked by eye at 960 and 390 px with Playwright screenshots against a mocked API on Vite's dev server (not committed).

### Checks before the commit
- `npm run verify` in `frontend/`: exit 0 (typecheck, ESLint, Prettier, 43 tests in 11 files).
- `./gradlew build`: not run, by the user's decision of 2026-11-16 (a change only to `frontend/` and its progress report runs `npm run verify` alone; the controller builds before integrating).
- Self-review (thermo-nuclear): the error mapping lives once in the client's middleware (no per-call error handling), the retry rule once in `createQueryClient`, and the cache key once in `itinerarySearchKey`, which the hook and "Reload" share; the status line became one pure `statusText(isLoading, loaded)` instead of a chain of checks on the query; `failureMessage` checks `ApiError` once. No existing mechanism was duplicated: TanStack's `resetQueries` is the reload, its `retry` option the rule. Nothing fails silently: every failure shows a sentence, and a 429 without a wait falls back to the generic one.

### Concerns
- `toApiError` turns an HTTP-date `Retry-After` into `NaN` (Task 2's Minor C-1, waiting for the end-of-plan review); the 429 message would then read "Try again in NaN seconds". The fix belongs in `toApiError`.
- After the last page loads, "Load more" disappears and the keyboard focus falls back to the page; moving it to the new cards is not in the plan.

### E7: axe in the component tests (approved extra, assigned to Task 5)

- **`eslint-plugin-jsx-a11y`** was already installed and enabled by Task 1 (`jsxA11y.flatConfigs.recommended` in `eslint.config.js`, shown failing there with `jsx-a11y/alt-text`); nothing to add.
- **`vitest-axe` 0.1.0** (dev), the version Task 1's table recorded: still the newest stable (`npm view vitest-axe time --json`: 0.1.0 on 2022-10-21; the only later releases are `1.0.0-pre.0` to `pre.5`, prereleases). Peer `vitest >=0.16.0` met by 5.0.5. It brings no new `axe-core`: its `^4.4.2` dedupes to the 4.14.0 already in the lockfile (2026-10-05, the newest stable; only canaries are newer). New transitive packages: `chalk` 5.6.2 (2025-09-08) and `lodash-es` 4.18.1 (2026-04-01); `npm ls` clean; `npm audit` unchanged (the 7 moderate `minimatch` findings of Task 1).
- **`src/test/axe.ts`**: `axe` configured with Q7's tags (`wcag2a`, `wcag2aa`, `wcag21a`, `wcag21aa`), the matcher registered with `expect.extend`, and its type declared for Vitest 5's `Matchers` (vitest-axe 0.1.0 still declares it on Vitest 0.x's `Vi` namespace, which Vitest 5 does not read). `color-contrast` is off here: jsdom neither lays out nor paints, so the rule cannot decide and printed `Not implemented: HTMLCanvasElement's getContext()` four times; the end-to-end axe run checks contrast in a browser.

Red → green, one assertion per component test file:
- `SearchPage.test.tsx` `'has no accessibility violations with results on the page'` (two cards and "Load more"): red `Failed to resolve import "vitest-axe" from "src/test/axe.ts"`; green after `npm install --save-dev --save-exact vitest-axe@0.1.0`. Shown red by mutation as well (each card's `<li>` made a `<div>`): `<ul> and <ol> must only directly contain <li>, <script> or <template> elements (list)`; restored.
- `FilterBar.test.tsx` `'has no accessibility violations while a field is invalid'` (Task 3's component; an origin error with `aria-invalid` and `aria-describedby`): passed on its first run, so red by mutation (the label text removed): `Form elements must have labels (label)` at `input[aria-describedby="origin-error"]`; restored.
- `SortTabs.test.tsx` `'has no accessibility violations'` (Task 3's component): passed on its first run, so red by mutation (the tab text removed): `Form elements must have labels (label)` at `input[value="price"]`; restored.
- `ItineraryCard.test.tsx` `'has no accessibility violations'` (Task 4's component, the connecting itinerary): passed on its first run, so red by mutation (the route line's `aria-hidden="true"` made `"yes"`): `ARIA attributes must conform to valid values (aria-valid-attr-value)`; restored.

Checks: `npm run verify` exit 0 (47 tests in 11 files, no stray output). Self-review (thermo-nuclear): one helper holds the configuration, the matcher and its type, so a test imports only `axe`; no existing accessibility helper to reuse (Task 1 had only the lint plugin).

### E9: "Try again" on the search failure (approved extra, assigned to Task 5)

Red → green in `SearchPage.test.tsx`, one test at a time:
- `'tries a failed search again'` (502 until the app is "up", then one flight): red `Unable to find an accessible element with the role "button" and name "Try again"`. Green with the button beside the failure sentence (outside the `role="alert"` paragraph, so the alert reads only the sentence), calling `refetch()`.
- No "Try again" where it cannot help, asserted in the existing 400 and 429 tests: red `expect(element).not.toBeInTheDocument()` in both (the button was on every failure). Green: `failureMessage` became `describeFailure(error): { message, canTryAgain }`; a rejected filter would be rejected again (its detail says what to change) and a rate limit says when to search, so only the generic failure ("Flights could not be loaded. Search again in a moment.", which a 429 without `Retry-After` also shows) offers it.
- `'tries a failed next page again'` (the first page shown, "Load more" answered 502): red `Unable to find an element with the text: Showing 2 flights` (`refetch()` read the first page again and never the one that failed). Green: "Try again" repeats the request that failed, `fetchNextPage()` when `isFetchNextPageError`, else `refetch()`; the requests after the click are exactly `['page-2']`.

Look: the failure box lays the sentence and "Try again" on one line, wrapping on a phone (checked by eye with a screenshot at 960 px against a mocked 502, not committed).

Checks: `npm run verify` exit 0 (49 tests in 11 files). Self-review (thermo-nuclear): the decision "may trying again help" sits with the message in one function, so the component has one flag to read; "Try again" uses TanStack's own `refetch` and `fetchNextPage`, no request code of its own.

### Extras for the user to decide
- None built. Considered and left out: moving the keyboard focus to the first new card after "Load more" (value: a keyboard user continues where the new results start; cost: a ref per card and one test).

## Task 5 — fix round 1

Review (`task-5-review.md`): functional CHANGES REQUESTED on one Important finding, code APPROVED. F-1 fixed as the controller chose; the Minors (2 functional, 5 code) wait for the end-of-plan review. Worked on top of `93f20ec` (the branch rebased by the controller onto `main` at `07bed8b`).

- **F-1, a 429 with `Retry-After` was a dead end:** "Try again" is hidden there (E9 unchanged), and "Search" with the same filters kept the same query key, so nothing was asked again after the wait. Fix: submitting "Search" with unchanged criteria while the search is in error asks again from the first page (`queryClient.resetQueries` on the search's key, limited to a query in error).
  - `'searches again with the same filters after a rate-limited search'` (429 with `Retry-After: 30`, then a 200; a click on "Search"): red `Unable to find an element with the text: Showing 1 flight` (one request only; the URL and the key did not change). Green with the reset in `SearchPage.search` when the normalised criteria equal the current ones; MSW counts 2 requests.
  - `'keeps the shown results when the same filters are searched again'` (a guard for the "in error" condition, added beyond the controller's one test because the condition is production code): passed on its first run, shown red by mutation (the predicate made `() => true`): `AssertionError: expected 2 to be 1`; restored. A successful search is not asked again by the same "Search": fresh results stay "Reload"'s job (Q4-A).
- Checks: `npm run verify` exit 0, 51 tests in 11 files, run on a clean export of `HEAD` plus this change (`git archive`), because the worktree also held another agent's uncommitted Task 6 files (`RepricePanel*`, `LegList*`, `useReprice.ts`, `renderWithClient.tsx`, changes to `ItineraryCard*` and `types.ts`) whose own `RepricePanel.test.tsx` was red there; none of them is part of this commit. No Gradle build (frontend only).
- Self-review (thermo-nuclear): the retry of a failed search reuses the cache key helper and TanStack's `resetQueries`, the same mechanism as "Reload"; one comparison of normalised parameters decides "same search"; no new state.

## Task 5 — review

The double review (`.superpowers/sdd/2026-11-15-dashboard/task-5-review.md`) answered functional CHANGES REQUESTED on one Important finding and code APPROVED. F-1: after a 429 with `Retry-After`, "Try again" was hidden and a Search with the same filters kept the same query key, so the customer was stuck. Fix round 1 (above; the controller chose, of the reviewer's two options, that a Search with unchanged criteria resets an errored query) was re-reviewed: both verdicts APPROVED, no request sent during the wait unless the user presses Search. The reviewer confirmed Review Focus 1 and 3, the `apiErrors` middleware, every Q7 text, `useNow` wired in, `staleTime: Infinity` as the right reading of Q4-A, and `vitest-axe` 0.1.0 as its newest stable release. Minors for the end-of-plan review:
- F-2, F-3 (first review) and F-4: switching tabs during the `Retry-After` wait sends one request (TanStack's refetch on focus; `refetchOnWindowFocus: false` on the search, and the comment in `useItinerarySearch` corrected).
- C-1 to C-5 (first review; C-4 the untested empty-2xx guard) and C-6: `SearchPage` and `ResultsList` both reach into the query cache with the search key; the hook should own both resets.

## Task 6 — evidence

Branch `plan6-task6` in the worktree `.claude/worktrees/agent-acab2caad1e1455b0`, on top of `593ce5a` (`main` at `07bed8b` plus Task 5 and its fix round 1; the agent's own worktree was removed by the harness, so it worked in Task 5's worktree, as the controller instructed). No new dependency. Files: created `src/itinerary/useReprice.ts`, `RepricePanel.tsx`, `RepricePanel.module.css`, `LegList.tsx`, `LegList.module.css`, `RepricePanel.test.tsx` and the test helper `src/test/renderWithClient.tsx`; modified `src/itinerary/ItineraryCard.tsx`, `ItineraryCard.module.css`, `ItineraryCard.test.tsx`, `format.ts` (`flight(leg)`) and `src/api/types.ts` (`RepriceResponse`). `schema.d.ts` already held the re-price operation: no regeneration.

### Red → green, one test at a time

`ItineraryCard.test.tsx` (now rendered under `createQueryClient()` with `renderWithClient`, since the card re-prices; a default handler answers `CONFIRMED`)
- `'re-prices when a card is opened, once per opening'` (MSW records each id: 1 after opening, still 1 after closing, 2 after reopening): red `Unable to find an accessible element with the role "button"`. Green: the pass and the stub (already phrasing content, Task 4) became `<span>`s inside one `<button type="button" aria-expanded>`, and opening calls `useReprice().mutate(id)`; `useReprice` is `useMutation<RepriceResponse, Error, string>` posting `/api/itineraries/{id}/reprice` through `api`, so failures arrive as the `apiErrors` middleware's `ApiError`.
- `'shows each leg when opened'` (added beyond the plan's list: the interface says opening shows each leg; "IB 3166 MAD 08:15 to LHR 09:40", "BA 117 LHR 10:50 to JFK 13:40", gone again after closing, `aria-controls` naming the region): red `Unable to find an accessible element with the role "list" and name "Legs"`. Then red twice on the way: `aria-controls` missing (my edit missed the one-line JSX), and `Received: IB 3166MAD 08:15toLHR 09:40` (adjacent flex `<span>`s, which a screen reader also reads run together). Green with `LegList` writing each leg as inline text with real spaces, inside a region that is always in the page (`hidden` while closed, so `aria-controls` always names an element).
- `'opens and closes a card with the keyboard'` (Tab focuses the summary, Enter opens, Space closes): passed on its first run (a native button), so shown red by mutation: the summary as `<div role="button" tabIndex={0}>` with only `onClick` gives `expect(element).toHaveAttribute("aria-expanded", "true")`, received `"false"`; restored.

`RepricePanel.test.tsx` (each test opens the connecting card with MSW answering the re-price; the panel is `role="status"`)
- `'shows a confirmed price with Select'`: red `Unable to find an accessible element with the role "status"`. Green with `RepricePanel` ("Price confirmed: €412.60" and "Select").
- `'shows a changed price and updates the card'` ("Price changed: was €412.60, now €431.00"; the summary shows €431.00 and no longer €412.60): red `Unable to find an element with the text: Price changed: was €412.60, now €431.00`. Green with `confirmation(response)` and the card's price taken from the answer when the outcome is `PRICE_CHANGED`.
- `'shows a changed price without the one shown before'` (added: the contract marks `previousPrice` optional): I had written its branch together with the previous green, so I took it out (`before!.amount`) before writing the test: red `TypeError: Cannot read properties of undefined (reading 'amount')`; green with the branch back ("Price changed: now €431.00").
- `'shows the alternative inside the card with its notice'` (§6.5's sentence exact; the alternative's legs in a list "Alternative legs"; "Price confirmed: €398.20" and "Select"; the chosen card keeps €412.60, Q5-A): red `Unable to find an element with the text: We couldn't confirm this flight. This is the best alternative we found.`. Green: the response's `notice` and a `LegList` of the alternative's legs (`LegList` took a `label`).
- `'shows the notice of spec 6.5 when an alternative comes without one'` (added: `notice` is optional in the contract): red, same message. Green: `data.notice ?? ALTERNATIVE_NOTICE`.
- `'shows the 503 message'` (a problem whose `detail` differs from §6.5's sentence, so the test proves the panel shows its own copy; no "Select"): red `Unable to find an element with the text: We can't confirm the price right now. Please try again in a few minutes.`. Green with `failure(error)` returning that sentence.
- `'says when a flight is no longer available'` (404): red `Unable to find an element with the text: This flight is no longer available. Search again for current flights.`. Green with the 404 case.
- `'shows the re-price limit with its seconds'` (429, `Retry-After: 30`): red `Unable to find an element with the text: Too many price checks from your connection. Try again in 30 seconds.`. Green with the 429 case.
- `'shows the failure for a re-price limit without a wait'` (added, as Task 5 did for the search): red `Unable to find an element with the text: We can't confirm the price right now. …` (it read "Try again in undefined seconds"). Green with `retryAfterSeconds !== undefined` on the 429 case.
- `'says the price is being checked'` (added: the interface's pending state; the answer held with `delay('infinite')`; no "Select"): red `Unable to find an element with the text: Checking the price…`. Green with `isPending`.
- `'has no accessibility violations with an alternative open'` (the E7 rule: one axe assertion in the file, on the fullest state): passed on its first run, so shown red by mutation ("Select" left without text): `Buttons must have discernible text (button-name)`; restored.

Refactor under green: the card's flights line and `LegList` both built "IB 3166", so the label moved to `flight(leg)` in `format.ts` (Task 4's Minor C-2); the panel's prop type is `Reprice = ReturnType<typeof useReprice>`, not a second copy of the hook's generics.

### Choices to review
- **The mutation's error type is `Error`, not the plan's `ApiError`:** a network failure rejects `fetch` with a `TypeError` before any middleware runs, so `ApiError` would be a type the value does not always have. The panel checks `instanceof ApiError`, as `ResultsList` does.
- **"Any other failure" shows §6.5's 503 sentence** ("We can't confirm the price right now. Please try again in a few minutes."): Q7 has no generic re-price text, and a 502, a network error, a 400 or a 429 without a wait all mean to the customer that the price could not be confirmed now. The 503 shows the dashboard's own copy, not the API's `detail` (the plan's decisions table).
- **`CONFIRMED` reads "Price confirmed: €412.60"**, and the alternative's price uses the same sentence: Q5-A says only "the price". New copy, for the user to see.
- **Failures and outcomes sit in the one `role="status"` panel** the plan names, not a separate `role="alert"`.
- **The card keeps the last answer while closed** (no reset on closing), so a changed price stays on the card. On reopening, the price falls back to the search's while "Checking the price…" shows, then takes the new answer. TanStack clears a mutation's data when it runs again.
- **The panel's region stays in the page while closed** (`hidden`), so `aria-controls` always names an element; its content mounts only while open.
- **No "Select" while pending or after a failure**; "Select" does nothing yet (E8 adds its note).
- **`useReprice` throws on a 2xx without a body**, as `useItinerarySearch` does; not reachable from the contract, so it has no test.

### Checks before the commit
- `npm run verify` in `frontend/`: exit 0 (typecheck, ESLint, Prettier, 65 tests in 12 files; no warnings in the output).
- `./gradlew build`: not run, by the user's decision of 2026-11-16 (frontend only).
- Self-review (thermo-nuclear): the error mapping stays in the client's middleware; mutations keep TanStack's default of no retry (`createQueryClient`), so a 429 spends one request; the outcome-to-sentence rules are two pure functions (`failure`, `confirmation`) instead of conditions in the JSX; one `LegList` serves the chosen and the alternative legs; `flight(leg)` removed the duplicated label. Left as it is: `failure` and Task 5's `describeFailure` share a shape (a 429 with its seconds) but not their sentences, and a shared helper would be a thin wrapper; the 2xx-without-body guard now exists in both hooks (a candidate for a helper in `client.ts`, not done here so as not to touch Task 5's file under review).

### E1: "+1" on an arrival the next local day (approved extra, assigned to Task 6)

Red → green, one test at a time:
- `format.test.ts` `'counts the local days from departure to arrival'` (22:40 Madrid → 06:15 London is 1; MAD → JFK the same day is 0; 18:00 New York → 21:30 Los Angeles is 0 although it lands at 05:30 UTC the next day; across the end of a month is 1): red `TypeError: daysLater is not a function`. Green with `daysLater(departure, arrival)`, the difference of the two dates written in the strings (the same `wallClock` reading as `localDate`), each taken as a UTC midnight. Shown red by mutation as well (the dates taken from the instants in UTC): `AssertionError: expected 1 to be +0` on the Los Angeles case; restored.
- `ItineraryCard.test.tsx` `'marks an arrival on the next local day'` (no "+1" on `direct()`; "+1" on the overnight flight): red `Unable to find an element with the text: +1`. Green: the card shows `+N` beside the arrival time when `daysLater` is above 0.

Choices to review: the marker is on the card's arrival only, not on each leg in the open card (E1 names the arrival); it counts any number of days (`+2` would show too), though the rule given is "+1"; it is visible text inside the summary button, so a screen reader reads "plus 1" after the time.

Checks: `npm run verify` exit 0 (67 tests in 12 files). Self-review (thermo-nuclear): `daysLater` reuses `wallClock`, so the date has one reading in the module; the card adds one value and one condition, no new state.

### E2: airline names beside the codes (approved extra, assigned to Task 6)

The names come from a static `src/itinerary/carriers.ts` (the controller's choice of 2026-11-16: in the bundle, no API change): the twelve carriers of `mock-gds`'s `StandardCatalogue`, checked against `mock-gds/src/main/java/com/flightaggregator/mockgds/reference/StandardCatalogue.java` (IB Iberia, UX Air Europa, BA British Airways, AF Air France, LH Lufthansa, KL KLM, TP TAP Air Portugal, AZ ITA Airways, LX Swiss, FR Ryanair, U2 easyJet, VY Vueling).

Red → green, one test at a time (`format.test.ts`):
- `"labels a flight with its airline's name"` (`direct()`'s leg → "Iberia IB 3166", `connecting()`'s second → "British Airways BA 117"): red `AssertionError: expected 'IB 3166' to be 'Iberia IB 3166'`. Green with `CARRIER_NAMES` (a `ReadonlyMap`) read by `flight(leg)`, the one label helper that Task 6 extracted, so the card's flights line and every leg list changed together. The card's expectations that pinned the old label ("IB 3166", "IB 3166, BA 117" in `ItineraryCard.test.tsx`) went red with it and were updated to the new text; the leg-list expectations still passed, because `toHaveTextContent` matches a substring, and were updated to the full new text as well.
- `'labels a flight of an unknown carrier with its code alone'` (`ZZ 120`): red `AssertionError: expected 'undefined ZZ 120' to be 'ZZ 120'`. Green: the name is added only when the map has the code.

Choices to review: the name stands before the code ("Iberia IB 3166", as E2 wrote it); the "Airline" filter still takes a code (unchanged, Task 3); the list must follow the dataset by hand, which the module's comment says.

Checks: `npm run verify` exit 0 (69 tests in 12 files). Self-review (thermo-nuclear): no carrier list existed in the frontend; one map, read in one place, with no wrapper function around the map.

### E8: a note when "Select" is pressed (approved extra, assigned to Task 6)

Red → green in `RepricePanel.test.tsx`:
- `'says booking is not part of the demo when Select is pressed'` (no note before the click, "Booking is not part of this demo." after it): red `Unable to find an element with the text: Booking is not part of this demo.`. Green: "Select" sets a flag in the panel, and the note shows under the price inside the panel's `role="status"` region, so it is announced. The flag lives in the panel, whose content mounts only while the card is open, so closing and reopening the card clears it.

Look, Task 6's panel and the three extras together (checked by eye at 960 and 390 px with Playwright screenshots of a throwaway page on Vite's dev server, `fetch` stubbed with a changed price and an alternative; not committed): the open card sits under a dashed perforation like the stub's; the notice, the alternative's legs and then the price with "Select" on one row, the note under them; "+1" raised beside the arrival time; the summary's focus ring drawn inside the card, which clips anything beyond its border. The first screenshot showed the panel laid out in a wrapping row (the notice beside the alternative's legs), so this commit also makes the panel a column, with the price and "Select" on their own row; layout only, the tests unchanged.

Checks: `npm run verify` exit 0 (70 tests in 12 files). Self-review (thermo-nuclear): one boolean in the component that owns "Select", no new module; the note's text sits next to the button it explains.

### Extras for the user to decide
- **"+1" on each leg in the open card:** a leg that lands the next local day (an overnight long-haul leg) shows only its time there; E1 named the card's arrival. Value: the leg list reads right too. Cost: one call of `daysLater` per leg in `LegList`, one test. Left out unless you approve.
- **Telling the chosen legs from the alternative's by more than the list's accessible name:** both lists look alike in the open card; only the notice between them separates them. Value: a clearer alternative. Cost: a heading or caption, one test. Left out unless you approve.

### Concerns
- The 2xx-without-body guard is now in both hooks (`useItinerarySearch`, `useReprice`); a helper in `client.ts` would hold it once (not done here, to leave Task 5's file alone while it is under review).
- On reopening a card whose price changed, the card shows the search's price while "Checking the price…" shows, then the new answer (TanStack clears a mutation's data when it runs again).
- `toApiError`'s HTTP-date `Retry-After` (Task 2's Minor C-1) reaches the re-price too: a 429 with a date would read "Try again in NaN seconds".

## Task 6 — integration

Integrated without a per-task review (the user's speed rule of 2026-11-16); the end-of-plan triple review covers Task 6, E1, E2 and E8. Its implementer's own worktree was removed by the harness when it first stopped, so it worked on a branch of its own (`plan6-task6`) in Task 5's worktree; the controller rebased its four commits onto `main` without Task 5's copies (only append-only conflicts in this file, both sides kept) and ran `npm run verify` and `./gradlew build` before the fast-forward. Points for the end-of-plan review, from the implementer: the re-price mutation's error is typed `Error`, not `ApiError` (a network failure is a `TypeError`); new copy "Price confirmed: €X"; a failure other than 404 or a 429 with a wait shows §6.5's 503 sentence, not the API's `detail`; the "2xx without a body" guard is in both hooks; a reopened card shows the search's price again while the new check runs; E1 counts any number of days (`+N`), on the card's arrival only. Extras pending for the user: "+1" on each leg in the open card; a caption telling the chosen legs from the alternative's.

## Task 7 — evidence

Worktree branch `worktree-agent-a854968855879f1d2`, from `main` at `2b6247a` (plan 6 Tasks 1 to 5 and 9 integrated; `main` later moved to `c71f80d`, a continuation-point commit only). No per-task review (the user's speed rule of 2026-11-16); the end-of-plan triple review covers this task.

Files: `src/config.ts`, `public/config.json` (the compose values, for `npm run dev`), `src/admin/authClient.ts`, `AdminSession.tsx`, `adminToken.ts` (the context and `useAdminToken`, in its own file because `react-refresh/only-export-components` refuses a hook beside components), `AdminPage.tsx`, their tests and `src/test/fakeAuth.ts` (my own test helper: a fake `AuthClient` and `adminUser(token)`); modified `src/App.tsx` (route `/admin`), `src/api/client.ts` (`adminClient`), `deploy/keycloak/flight-aggregator-realm.json`, `package.json` and `package-lock.json`.

### Dependency (spec 6.1)

| Package | Pinned | Published | Note |
|---|---|---|---|
| oidc-client-ts | 3.5.0 | 2026-03-13 | Task 1's table; re-checked on 2026-11-16 with `npm view oidc-client-ts time --json`: still the newest release (3.4.1 2025-11-17, 2.5.0 2026-01-05 on the old line). No peers; engines `node >=18`. One transitive package, `jwt-decode` 4.0.0 (2023-10-27, its newest stable). `npm ls` clean |

### Red → green, one test at a time

`config.test.ts` (MSW serves `/config.json`)
- `'loads the sign-in settings'`: red `Failed to resolve import "./config" from "src/config.test.ts"`. Green: `loadConfig()` fetches `new URL('/config.json', window.location.origin)` (Node's `fetch` refuses a relative URL, as in `client.ts`).
- `'names a missing setting'`: red `AssertionError: promise resolved "{ oidcAuthority: 'http://localhost:8…' }" instead of rejecting`. Green: each field goes through `required(settings, name)`, which throws `/config.json has no <name>` unless the value is a string.
- `'names an empty setting'` (added beyond the plan's list, a guard for the `=== ''` check written with the previous green): passed on its first run, so red by mutation (the empty check removed): `AssertionError: promise resolved "{ oidcAuthority: '', …(1) }" instead of rejecting`; restored.

`AdminSession.test.tsx` (a fake `AuthClient`; rendered inside `<StrictMode>`, as `main.tsx` renders the app, so every effect runs twice)
- `'starts the sign-in when there is no session'`: red `Failed to resolve import "./AdminSession"`; then a harness error of my own (`Found multiple elements with the role "status"`: the location probe was an `<output>`, now a `<p data-testid>`). Green: an effect calls `getUser()` and, with no user, `signinRedirect()`; "Signing in…" in a `role="status"` paragraph, no children. The once-only guard (a ref; a sign-in answer can be completed only once, and StrictMode runs the effect twice) was shown red by mutation (the guard line commented out): `AssertionError: expected "vi.fn()" to be called once, but got 2 times`; restored.
- `'completes the sign-in on the way back and cleans the URL'` (`/admin?state=…&session_state=…&code=…`): red `Unable to find an element with the text: The admin screen`. Green: with `state` and `code` in the URL, `signinRedirectCallback()`, then `navigate(pathname, { replace: true })` and the children; the location is `/admin`; no `signinRedirect`.
- `'shows a sign-in failure'` (`/admin?error=access_denied&state=…`, the callback rejecting): red `Unable to find role="alert"`. Green: an answer is `state` plus `code` **or `error`**, and one async `signIn()` whose rejection shows `role="alert"` "Sign-in failed. Reload the page to try again." (Q7). Without the `error` case, Keycloak's refusal would start a new sign-in, which Keycloak refuses again: a redirect loop (Step 4 shows Keycloak answering exactly that way). The URL is cleaned in `finally`, so the reload the message asks for starts afresh instead of repeating a spent answer.
- `'shows a sign-in failure when Keycloak cannot be reached'` (added, guard: `signinRedirect()` rejects, as when `oidc-client-ts` cannot read the discovery document): passed on its first run (the same catch); red by mutation (`signinRedirect().catch(() => undefined)`): `Unable to find role="alert"`; restored.
- `'shows the admin screen to an admin already signed in'` (added, found while writing: `getUser()` returns a user, as when the admin comes back to `/admin` from the search page): red `Unable to find an element with the text: The admin screen` (the page said "Signing in…" for ever). Green: a user in memory means signed in.
- `'gives the admin screen the access token'` (the plan's `useAdminToken`): red `TypeError: useAdminToken is not a function`. Green: `AdminSession` provides `() => auth.getUser().then((user) => user?.access_token ?? null)` (a `useCallback` on `auth`) through a context around its children.
- `'refuses the token outside the admin session'` (added, guard): passed on its first run; red by mutation (the hook returns `() => Promise.resolve(null)` instead of throwing): `AssertionError: expected [Function] to throw an error`; restored.
- `'has no accessibility violations while the sign-in fails'` (E7): passed on its first run; red by mutation (`aria-live="nope"` on the alert): `ARIA attributes must conform to valid values (aria-valid-attr-value)`; restored.

`src/api/client.test.ts` (MSW records each request's `Authorization`). The committed contract has no admin path until plan 5 Task 12 reaches `main`, and the middleware does not look at the path, so these tests call `/api/itineraries` through both clients (see Concerns).
- `'sends the token only to admin calls'`: red `TypeError: adminClient is not a function`. Green: `adminClient(auth)` is a second `createClient<paths>` on the same `baseUrl` with `apiErrors` and a `bearerToken(auth)` middleware whose `onRequest` sets `Authorization: Bearer <access token>`; `api` keeps only `apiErrors`. Seen: `[null, 'Bearer the-access-token']`.
- `'renews the session once when an admin call answers 401'` (Review Focus 5): red `ApiError: HTTP 401` (thrown before any renewal). Green: `onResponse` on a 401 calls `signinSilent()` once and repeats the request once with the new token through `options.fetch` (the `fetch` `openapi-fetch` itself uses). Seen `['Bearer expired-token', 'Bearer renewed-token']`, the data returned, one renewal, no `signinRedirect`. The amendment's registration order (`apiErrors` first, so that its `onResponse` runs last) was shown red by mutation: `client.use(bearerToken(auth), apiErrors)` gives `ApiError: HTTP 401`; restored.
- `'signs in again when the renewal fails'` (the refresh token refused): red `AssertionError: expected Error: invalid_grant to match object { status: 401 }` (the renewal's error escaped). Green: a rejected renewal counts as none, and `signinRedirect()` follows; the call rejects with the `ApiError` 401; one request only.
- `'signs in again when the renewed token is refused too, without a loop'` (added, the interface's "a second 401"): red `AssertionError: expected "vi.fn()" to be called once, but got 0 times` (the repeated 401 was returned as is). Green: a repeated answer that is still 401 also ends in `signinRedirect()`; exactly one renewal and two requests.

`AdminPage.test.tsx` (renders `<App />` at `/admin`, so it covers the route; `createAuthClient` is replaced with `vi.mock`, because the real client leaves the page for Keycloak and jsdom cannot follow; Task 10 signs in for real, and the manual run below did)
- `'shows a sign-in failure when the settings cannot be read'` (added, the admin screen's own wiring): red `Unable to find role="alert"` (no `/admin` route). Green: the route, and `AdminPage` reading `loadConfig()` through `useQuery({ queryKey: ['config'], retry: false })`; an error shows the same `SignInFailed` element as `AdminSession`. `retry: false` shown needed by mutation (removed: the app's retry rule retries a non-`ApiError` three times with back-off, so the alert came too late): `Unable to find role="alert"`; restored.
- `'signs in with the served settings'` (added): red `Unable to find role="heading" and name "Provider status"`. Green: once the settings are read, `AdminScreen` creates one `AuthClient` (`useState` initialiser, so the tokens it holds live as long as the screen) and wraps the h1 "Provider status" (Q7) in `AdminSession`; `createAuthClient` called with the served settings.
- `'has no accessibility violations once signed in'` (E7): passed on its first run; red by mutation (`aria-hidden="yes"` on the h1): `ARIA attributes must conform to valid values (aria-valid-attr-value)`; restored.

`createAuthClient(config)` has no unit test: it is the library's configuration (`authority`, `client_id`, `redirect_uri` `${origin}/admin`, `response_type: 'code'`, `scope: 'openid'`, `userStore` an `InMemoryWebStorage`), which jsdom cannot exercise without following a cross-origin redirect. The plan leaves it to Task 10's admin test; until then, the manual run below proves it against the real Keycloak.

### Step 4: the realm client and Keycloak's answers

Client `dashboard` in `deploy/keycloak/flight-aggregator-realm.json`, per Q7 and the decisions table: `publicClient: true`, `standardFlowEnabled: true`, `implicitFlowEnabled: false`, `directAccessGrantsEnabled: false`, `serviceAccountsEnabled: false`, `redirectUris` `["http://localhost:3000/admin*"]`, `webOrigins` `["http://localhost:3000"]`, attribute `pkce.code.challenge.method: S256`.

`docker compose up keycloak --wait` (exit 0, healthy), then the authorization endpoint `http://localhost:8180/realms/flight-aggregator/protocol/openid-connect/auth` with `client_id=dashboard`, `response_type=code`, `scope=openid` and `redirect_uri=http://localhost:3000/admin`:
1. With `code_challenge` and `code_challenge_method=S256`: `200`, the login page (`<title>Sign in to flight-aggregator</title>`, `id="kc-form-login"`, a `username` field).
2. Without the challenge: `302` to `http://localhost:3000/admin?error=invalid_request&error_description=Missing+parameter%3A+code_challenge_method&state=s2&iss=…`: refused, and sent back to `/admin` with `error` and `state`, which is why `AdminSession` treats `error` as an answer.
3. With `code_challenge_method=plain`: `302` with `error=invalid_request&error_description=Invalid+parameter%3A+code+challenge+method+is+not+matching+the+configured+one`.
4. With `redirect_uri=http://localhost:5173/admin` (Vite's default dev port): `400` "Invalid parameter: redirect_uri" (see Concerns).
5. The password grant with `client_id=dashboard`: `{"error":"unauthorized_client","error_description":"Client not allowed for direct access grants"}`.

**A real sign-in, run by hand** (a throwaway Playwright script against `npx vite --port 3000 --strictPort` and this Keycloak; not committed, Task 10 makes it a test):
- The first run stopped at Keycloak's "Update Account Information" form (required action `VERIFY_PROFILE`): Keycloak 26's user profile requires an email, a first and a last name, and the realm's demo `admin` had none. It would have stopped Task 10's admin test the same way. Fixed in the realm file: `admin` gets `email` `admin@example.com` (a reserved example domain), `firstName` "Demo", `lastName` "Admin". `docker compose up keycloak --wait --force-recreate` re-imported it.
- Second run: the authorization request carried `client_id=dashboard`, `redirect_uri=http://localhost:3000/admin`, `response_type=code`, `scope=openid`, `code_challenge_method=S256` and a `code_challenge`; after the demo credentials the browser was back at exactly `http://localhost:3000/admin` (the code and state removed) with the h1 "Provider status", in React's StrictMode (the dev build), so the callback ran once. `sessionStorage` and `localStorage` were both empty after the sign-in (the tokens stay in memory; the PKCE state is removed once used). A reload came back to the signed-in screen with no password asked (Keycloak's own session).

### Choices to review
- **`useAdminToken` is built as the plan's interface names it, and nothing uses it yet.** `adminClient(auth)` reads the token from `auth` itself, so Task 8's `useInventoryStatus()` cannot reach the admin API through `useAdminToken`; it needs `adminClient(auth)` (or the `AuthClient`) from somewhere. I did not change the interface (a design point for the controller): providing the admin client from `AdminSession`'s context, instead of or beside the token, would make Task 8 direct.
- **The 401 renewal returns the original 401 after `signinRedirect()`**, so `apiErrors` throws an `ApiError` 401. In the browser `signinRedirect()` does not settle (the page leaves), so the query stays pending until it does. If `signinRedirect()` itself rejects (Keycloak unreachable), that error propagates; it is not an `ApiError`, so a query would retry it by the app's rule (at most three times, each with one renewal attempt: bounded, never a loop).
- **Two admin calls answering 401 at the same time renew twice** (one `signinSilent()` each). Keycloak accepts both refresh grants (refresh-token rotation is off by default). Task 8's polled status and "Run ingestion now" are the only concurrent callers.
- **`automaticSilentRenew`** stays at `oidc-client-ts`'s default (on): with the refresh token, the library also renews a minute before expiry, so the 401 path is the fallback, not the routine.
- **`AdminPage` renders the h1 "Provider status"** once signed in, so the signed-in screen is not empty; Task 8's `StatusPage` owns that heading and can move it.

### Concerns
- **The admin paths are not in the committed contract yet** (plan 5 Task 12 is under review on its own branch). `client.test.ts` calls `/api/itineraries` through `adminClient`; once Task 12 is on `main`, these tests can name `/api/admin/status` (one-line change each).
- **`npm run dev` cannot complete a sign-in as is**: Vite serves on 5173 and the realm (as decided) allows only `http://localhost:3000/admin*` (Step 4, case 4). Running `npx vite --port 3000` works (the manual run did). See Extras.
- `RealmClientCredentialsTest` imports the realm file, so this task runs `./gradlew build` too.

### Checks before the commit
- `npm run verify` in `frontend/`: exit 0 (typecheck, ESLint, Prettier, 69 tests in 15 files, no stray output).
- `./gradlew build`: exit 0 (`BUILD SUCCESSFUL in 1m 21s`; `:bootstrap:test` executed, not from cache, with `RealmClientCredentialsTest` passing on the new realm file).
- Self-review (thermo-nuclear): the error mapping stays once in `apiErrors`, shared by both clients; the token, its renewal and the single repeat live in one middleware, so no screen handles a 401; the sign-in is one `async` function with one catch, read by a three-state switch, instead of flags; the two state elements ("Signing in…", the failure) are shared by `AdminPage` and `AdminSession`; the settings are validated once at the boundary (`required`), not cast. Existing mechanisms reused: `openapi-fetch` middleware and its own `fetch`, TanStack Query for reading the settings, `oidc-client-ts`'s `InMemoryWebStorage` and its built-in PKCE, react-router's `navigate(..., { replace: true })`. Nothing fails silently: every failure of the sign-in shows the alert, a refused renewal leads to a new sign-in, and a missing setting is named.

### Extras for the user to decide
- **Let `npm run dev` sign in.** Value: the admin screen works on the dev server too. Cost: either `server: { port: 3000, strictPort: true }` in `vite.config.ts` (clashes with the compose `frontend` while both run) or a second redirect `http://localhost:5173/admin*` and web origin in the realm. Left out unless you approve.
- **One renewal for concurrent 401s** (a shared in-flight `signinSilent()`). Value: one refresh grant instead of two when the status poll and "Run ingestion now" expire together. Cost: a few lines in `bearerToken` and one test. Left out unless you approve.

### E4: "Sign out" (approved extra, assigned to Task 7)

Its own commit after Task 7's. `AuthClient` gains `signoutRedirect()` (the `UserManager` method), and `createAuthClient` sets `post_logout_redirect_uri` `${origin}/admin`.

Red → green in `AdminPage.test.tsx`:
- `'signs out through Keycloak'` (signed in, a click on "Sign out"): red `Unable to find role="button" and name "Sign out"`. Green: a "Sign out" button under the h1, inside `AdminSession` (so only a signed-in admin sees it), calling `auth.signoutRedirect()`; called once.

The realm's post-logout redirect, shown with the real Keycloak and the same throwaway Playwright run (sign in, reload, "Sign out"):
- Without the attribute, Keycloak already accepted `/admin` (an empty "valid post logout redirect URIs" falls back to the redirect URIs), so the realm states it explicitly and narrower: `post.logout.redirect.uris` `http://localhost:3000/admin` (not the `/admin*` pattern).
- Red by mutation, to show the attribute governs it: with `http://localhost:3000/` the sign-out stopped at Keycloak's logout endpoint with "We are sorry... Invalid redirect uri".
- Green with `http://localhost:3000/admin` (`docker compose up keycloak --wait --force-recreate` each time): "Sign out" → Keycloak's logout endpoint (with the `id_token_hint` `oidc-client-ts` sends) → `http://localhost:3000/admin` → a new sign-in → Keycloak's login form (`#username` present): its session ended, so the password is asked again, which is what E4 is for ("the demo can show the login again").

Choice to review: the post-logout target is `/admin`, not the search page, so the demo lands on the login form at once; `/` would need its own allowed URI. If `signoutRedirect()` rejects (it builds the URL from the discovery document already read at sign-in, so only an unreachable Keycloak before any sign-in could), the rejection reaches the console and the button does nothing visible.

Checks: `npm run verify` exit 0 (70 tests in 15 files); `./gradlew build` exit 0 (`BUILD SUCCESSFUL in 1m 15s`; the realm file changed again, and `:bootstrap:test` ran, with `RealmClientCredentialsTest`). Self-review (thermo-nuclear): one button and one setting, no new state; the end-session call is the library's own (`signoutRedirect`, with `id_token_hint`), not a hand-built logout URL.

## Task 7 — integration

Integrated without a per-task review (the user's speed rule of 2026-11-16); the end-of-plan triple review covers Task 7 and E4. The controller rebased it on `main` after Task 6 (only an append-only conflict in this file, both sides kept) and ran `npm run verify` and `./gradlew build` before the fast-forward. Points for the end-of-plan review, from the implementer: the realm's demo `admin` gained an email and names, because Keycloak 26 otherwise stops a first sign-in at "Update Account Information" (it would also block Task 10's admin end-to-end test); `npm run dev` on port 5173 cannot complete a sign-in, since the realm allows only `localhost:3000` (`npx vite --port 3000` works); `createAuthClient` has no unit test (checked by hand against the real Keycloak, and by Task 10); a failed sign-in inside the 401 path may be retried by the query rule up to three times; two concurrent 401s renew twice; a failed `signoutRedirect()` reaches only the console. Concern 1 (Task 8 cannot use `useAdminToken`) is settled in plan 6 Task 8's amendment. Extras pending for the user: let `npm run dev` sign in (Vite on 3000 with `strictPort`, or the realm also allowing 5173); one shared renewal for concurrent 401s.

## Task 8 — evidence

Worktree branch `worktree-agent-a88e6bc96b058c82f`, from `main` at `dcf88a2` (plan 5 Task 12 and plan 6 Tasks 1 to 7 and 9 integrated, so `schema.d.ts` has the admin paths). No per-task review (the user's speed rule of 2026-11-16); the end-of-plan triple review covers this task. Commit: `Show each provider's status and run an ingestion on demand`.

Files: created `src/admin/labels.ts`, `useInventoryStatus.ts`, `StatusPage.tsx`, `ProviderTable.tsx`, `RunIngestionButton.tsx`, `StatusPage.module.css`, `adminClientContext.ts` (the amendment's `useAdminClient`, in its own file like `adminToken.ts`, since `react-refresh/only-export-components` refuses a hook beside components), `adminFailure.ts` (Q7's 403 sentence, shared by the status and the button), their tests and `src/test/renderAsAdmin.tsx` (a test helper: renders a screen inside `AdminSession` for a signed-in fake admin, under the app's query client with `retryDelay: 0`); modified `src/admin/AdminSession.tsx` (provides the admin client), `AdminPage.tsx` (renders `StatusPage`), `src/api/types.ts` (`StatusReport` = `StatusResponse`, `ProviderStatus` = `ProviderStatusResponse`, the contract's schema names), `src/itinerary/format.ts` (`timeAgo` extracted from `freshness`, see below), `src/test/fixtures.ts` (`statusReport()`, `providerStatus()`). No dependency added. `schema.d.ts`, `openapi.json`, `ci.yml` and the backend are untouched.

### Red → green, one test at a time

`labels.test.ts` (Step 1)
- `'turns an enum value into sentence case'` (`UNKNOWN_AIRPORT`, `UP`, `ALPHA`): red `Failed to resolve import "./labels"`. Green: `label(value)` lowercases, turns `_` into a space and capitalises the first letter.
- `'writes the half-open breaker with a hyphen'` (added: Q7 writes "Half-open"): red `AssertionError: expected 'Half open' to be 'Half-open'`. Green: a `SPELLED` map read first, holding only `HALF_OPEN`.

`AdminSession.test.tsx` (the amendment after Task 7: `AdminSession` provides `useAdminClient()`, built once from its `AuthClient` with `adminClient(auth)`)
- `'gives the admin screen the admin client'` (a probe calls `GET /api/admin/status` through `useAdminClient()`; MSW records `Authorization`): red `Failed to resolve import "./adminClientContext"`; with the context file written, red `Unable to find role="button" and name "Read the status"` (the hook threw `useAdminClient is used outside AdminSession`: no provider). Green: `AdminSession` builds `useMemo(() => adminClient(auth), [auth])` and wraps its children in `AdminClientContext` inside the token's context. Seen: generation 7 shown, `['Bearer the-access-token']`.
- `'refuses the admin client outside the admin session'` (added, guard for the hook's throw, written with the context file): passed on its first run; red by mutation (the hook returns `{} as Client<paths>` instead of throwing): `AssertionError: expected [Function] to throw an error`; restored.

`StatusPage.test.tsx` (Step 2; rendered with `renderAsAdmin`, MSW serving `/api/admin/status`)
- `'shows the generation, its publication and the next ingestion'` (now 09:12, published 09:00, next 09:30): red `Failed to resolve import "./StatusPage"`. Green: `useInventoryStatus()` (`useQuery` keyed `['admin', 'status']`, `GET /api/admin/status` through `useAdminClient()`); h1 "Provider status"; "Generation 7", "Published 12 min ago", "Next ingestion in 18 min". "Published …" reuses the cards' relative time: `freshness()` in `format.ts` was split into `timeAgo()` ("just now", "N min ago", "N h ago") and `freshness() = "Updated " + timeAgo()`, its own tests green before and after. The relative times read `useNow` (Task 5's hook).
- `'says when the next ingestion is due'` (added: Q7's "Next ingestion due"; the branch was written with the previous green): passed on its first run; red by mutation (the `minutes > 0` branch removed): `Unable to find an element with the text: Next ingestion due`; restored. The minutes are rounded up, so 20 s before the run reads "in 1 min", never "in 0 min".
- `'shows one row per provider with its health and breaker'` (Alpha up/closed, Beta degraded/half-open with discards, Gamma down/open with no quote): red `Unable to find role="table" and name "Providers"`. Green: `ProviderTable`, a `<table>` with the caption "Providers", the column headers Provider, Health, Circuit breaker, Last successful ingestion, Legs, Discarded records, a row header per provider; enum values through `label()`; the last successful ingestion is the provider's `newestQuotedAt` as a relative time, "Never" when null; the discarded records are the total, then a list of each reason with its count ("Unknown airport: 3"). Then a harness error of my own (`getAllByRole` takes no regular expression; the row's header and cells are now read separately); green.
- `'marks a provider kept from the last good ingestion'`: red `AssertionError: expected '380' to be '380Kept from the last good ingestion'`. Green: a stale row adds "Kept from the last good ingestion" (§6.8, Q7) under its legs.
- `'shows that no ingestion has run yet'` (`generation 0`, both times null, no provider: what the API answers before the first run ends): red `Unable to find an element with the text: No ingestion has run yet.`. Green: with no `nextIngestionAt` (the API schedules the next run from the end of the last one, so it is null exactly while no run has ended), the report is "No ingestion has run yet." alone, with no generation and no table.
- `'refreshes the status every 10 seconds'` (fake `setInterval`; the second answer is generation 8): red `Unable to find an element with the text: Generation 8` (one request after 10 s). Green: `refetchInterval: STATUS_REFRESH_MS` (10 000, Q7). TanStack Query does not refetch on an interval in a hidden tab unless `refetchIntervalInBackground` is set, so "while the tab is visible" is the library's default; no test fakes the page's visibility.
- `'keeps its times relative to the current time'` (added, guard: the status answered again is the same, so only the clock can move the times on): passed on its first run; red by mutation (`useNow(3_600_000)`): `Unable to find an element with the text: Published 13 min ago`; restored. The clock ticks with the refresh, every 10 s.
- `'shows the missing admin role'` (a 403 without a problem body): red `Unable to find role="alert"`. Green: the error as a `role="alert"` paragraph, "This account does not have the admin role." (Q7) for an `ApiError` 403.
- `'shows a failure to read the status'` (added: a failed status must not leave the screen silently empty; nginx's HTML 502): red `Unable to find role="alert"` (only the 403 was shown). Green: every other failure says "Provider status could not be loaded. Retrying every 10 seconds." The 403 sentence moved to `adminFailure(error, otherwise)`, shared with the button.
- `'keeps refreshing after a failure, as its message says'` (added, guard for the message's promise: a 502, then a good answer at the next 10-second tick): passed on its first run; red by mutation (`refetchInterval: (query) => query.state.status === 'error' ? false : STATUS_REFRESH_MS`): `Unable to find an element with the text: Generation 7`; restored. The alert disappears once a refresh succeeds.
- `'has no accessibility violations with the providers shown'` (E7, a stale provider with discards): passed on its first run; red by mutation (the reasons' `<li>` turned into `<span>`): `<ul> and <ol> must only directly contain <li>, <script> or <template> elements (list)`; restored.

`RunIngestionButton.test.tsx` (Step 3; it renders `StatusPage`, where the button lives, so the status read again after a run is observable)
- `'starts an ingestion'` (202): red `Unable to find role="button" and name "Run ingestion now"`. Green: `RunIngestionButton` (a `useMutation` posting `/api/admin/ingestions` through `useAdminClient()`), placed under the h1; "Ingestion started." in a `role="status"` paragraph; on success the `['admin', 'status']` query is invalidated. One POST, two status requests. The refresh shown needed by mutation (no invalidation): `AssertionError: expected 1 to be 2`; restored (and again after the self-review below).
- `'says when an ingestion is already running'` (409 with the API's problem detail): red `Unable to find role="alert"`. Green: the 409's `detail` in a `role="alert"` paragraph, "An ingestion is already running.". Plan 5 Task 12 answers 409 only while a run is really going on, so the API's own sentence is shown as is.
- `'cannot be pressed again while the ingestion is starting'` (added, the interface's "disabled while pending"; the POST held until the test answers it): red `expect(element).toBeDisabled()`. Green: `disabled={run.isPending}`; enabled again once "Ingestion started." shows.
- `'shows the missing admin role'` (added, the interface's "403 → Q7's text"): red `Unable to find role="alert"`. Green: the button's failure goes through `adminFailure`, after the 409's detail.
- `'shows a failure to start an ingestion'` (added: nginx's HTML 502; the fallback was written with the previous green): passed on its first run; red by mutation (an empty fallback): `expect(element).toHaveTextContent()` failed; restored. Text: "The ingestion could not be started. Try again in a moment."
- `'has no accessibility violations once a run is refused'` (E7, the 409 alert on the page): passed on its first run; red by mutation (`role="statuss"` on the started message): `ARIA roles used must conform to valid values (aria-roles)`; restored.

`AdminPage.test.tsx` (Task 7's wiring)
- `'shows the provider status once signed in'` (added): red `Unable to find role="table" and name "Providers"`. Green: `AdminScreen` renders `<StatusPage />` instead of Task 7's bare h1 (which `StatusPage` now owns), with "Sign out" after it. The file's other tests serve the status in a `beforeEach`, since every signed-in screen now reads it.

### Choices to review
- **"Last successful ingestion" is the provider's `newestQuotedAt`**, the only time the contract gives per provider (the newest quote among its stored offers, which survives a restart: plan 4, progress report of 2026-11-15). "Never" when it is null (nothing stored); Q7 has no text for that.
- **"No ingestion has run yet." keys on `nextIngestionAt` being null**, which the API derives from the last run's end. A finished run that published nothing (`publishedAt` null) shows the generation and the next ingestion without a "Published" line; Q7 has no text for it, so none was invented.
- **Texts not in Q7**, each the minimum for a failure that would otherwise be silent: "Provider status could not be loaded. Retrying every 10 seconds." (the 10 comes from `STATUS_REFRESH_MS`; a test pins that the refresh does go on), "The ingestion could not be started. Try again in a moment.", and "Never".
- **"Ingestion started." does not wait for the status read again** (`void invalidateQueries`): found by the self-review. Returning the invalidation's promise from `onSuccess` kept the mutation pending, and so the button disabled and the message hidden, until the status answered, which after a 5xx is the retries' 7 s.
- **The status keeps refreshing on a 403** (TanStack's interval does not stop on an error): one 403 every 10 s while a non-admin keeps the page open. Harmless; stopping it is an extra.
- **"Sign out" now sits after the table**: Task 7 put it under its own h1, which `StatusPage` owns now. No style was added to it.
- **Health has colour as well as its word** (Q6: cleared for UP, hold for DOWN; Degraded keeps the text colour); the word is always there, so colour is never the only signal.
- **`RunIngestionButton` uses `StatusPage.module.css`**, the one stylesheet the plan names for the screen.
- **The reasons are listed in the order the API sends them** (a Java `Map.copyOf`, stable within one app run).

### Concerns
- `useAdminToken` (Task 7) still has no production caller: `useAdminClient` replaces what it was for. The amendment says the context "also" provides the client, so it stays; deleting it (the hook, its context and two tests) is for the end-of-plan review to decide.
- `src/api/client.test.ts` still calls `/api/itineraries` through `adminClient`, with a comment saying the contract has no admin path; it has had them since plan 5 Task 12 (Task 7's concern). Not touched here: outside Task 8.
- Another agent may regenerate `schema.d.ts` for plan 5 Task 13 (the admin bearer scheme, 401 and 403). This task reads only `StatusResponse`, `ProviderStatusResponse` and the two paths; the 403 is handled from `ApiError.status`, so a documented 403 changes no code here.

### Checks before the commit
- `npm run verify` in `frontend/`: exit 0 (typecheck, ESLint with jsx-a11y, Prettier, 111 tests in 19 files, no stray output). Frontend-only change: no `./gradlew build` here (the user's rule of 2026-11-16); the controller runs it before integrating.
- Self-review (thermo-nuclear): reuse over new helpers (`timeAgo` split out of `freshness` instead of a second relative-time formatter; `useNow`; `ApiError` and the `apiErrors` middleware; `adminClient` for the token and its renewal, so no admin screen handles a 401; the `retry` rule of `createQueryClient`); one place each for the 403 sentence (`adminFailure`), the refresh interval (`STATUS_REFRESH_MS`, shared by the poll, the clock and the failure text) and the query key (`inventoryStatusKey`, shared by the poll and the button's invalidation); the no-run state is one early return instead of nullable checks across the page; enum spelling is one function with one exception table. Nothing fails silently: every failure of either call shows a `role="alert"` sentence. Finding fixed: the awaited invalidation above. Largest new file: `StatusPage.test.tsx`, 246 lines; no existing file grows past 200.

### Extras for the user to decide
- **A loading line on the status screen** ("Loading…" while the first read is pending). Value: the half second before the table is not blank. Cost: one line, one test. Left out unless you approve.
- **Stop polling after a 403.** Value: no request every 10 s from a non-admin's open tab. Cost: a `refetchInterval` function, one test. Left out unless you approve.
- **Hours in "Next ingestion in N min"** for an interval over an hour. Value: "in 2 h" instead of "in 120 min". Cost: one branch, one test; the interval is 30 min today. Left out unless you approve.

## Task 8 — integration

Integrated without a per-task review (the user's speed rule of 2026-11-16); the end-of-plan triple review covers it. The controller rebased it on `main` and ran `npm run verify` and `./gradlew build` before the fast-forward. Points for the end-of-plan review, from the implementer: texts not in Q7, added so a failure is never silent ("Provider status could not be loaded. Retrying every 10 seconds.", "The ingestion could not be started. Try again in a moment.", "Never" for a provider with no quote, no "Published" line for a run that published nothing); polling continues after a 403; Task 7's `useAdminToken` has no production caller any more; `src/api/client.test.ts` still calls `/api/itineraries` through `adminClient` with a stale comment (the admin paths now exist); `timeAgo()` extracted from `freshness()` in `format.ts`. Extras pending for the user: a "Loading…" line on the first status read; stop polling after a 403; hours in "Next ingestion in N min".

## Task 10 — evidence

Worktree branch `worktree-agent-a3697d5354fd34b30`, from `main` at `396bc6c` plus plan 5 Task 13 (fast-forwarded from its review branch, then rebased onto `main` once Task 13 was integrated, so only these commits are new). Commits: `Prove the dashboard end to end with Playwright and axe in CI` (Task 10), `Run the end-to-end flows in Firefox and WebKit as well as Chromium` (E6), `Let the end-to-end flows pass on a stack just started` (two fixes found on a fresh stack, below). Per-task review pending (other tasks build on it).

Files: created `frontend/e2e/axe.ts` (`expectNoAxeViolations(page)`), `cards.ts` (shared card helpers), `search.spec.ts`, `re-price.spec.ts`, `accessibility.spec.ts`, `admin.spec.ts`, `rate-limit.spec.ts`, and `frontend/src/test/axeTags.ts` (Q7's four tags, now read by both the component tests' `src/test/axe.ts` and the end-to-end `axe.ts`, so the two cannot drift); modified `frontend/src/test/axe.ts` (reads `AXE_TAGS`), `frontend/package.json` and `package-lock.json` (`@axe-core/playwright`), `frontend/playwright.config.ts` (E6) and `.github/workflows/ci.yml` (job `e2e` only). No production code changed.

### Dependency (spec 6.1)
- `@axe-core/playwright` **4.14.0** (dev), the version Task 1's table recorded: still the newest stable (`npm view @axe-core/playwright time --json`: 4.14.0 on 2026-10-15, 32 days; everything newer is a `4.14.1-<sha>` prerelease). Its `axe-core ~4.14.0` dedupes to the 4.14.0 already in the lockfile; peer `playwright-core >= 1.0.0` met by 1.64.1.

### How each test was shown red
The tests run over features that exist, so each was shown red by a labelled mutation of the dashboard, every one restored (`git status` clean of `src/` after each). For the source mutations, the compose `frontend` was stopped and the mutated source served by `npx vite --port 3000 --strictPort` (its proxy sends `/api` to the app on 8080; the realm's only redirect is `localhost:3000`); the nginx mutation and the Firefox/WebKit runs used the rebuilt image. Green runs are against the compose stack through nginx (`docker compose -p p6t10 up --build -d --wait`).

`search.spec.ts`
- `'shows the cheapest flights first and loads 20 more'` (40 cards, "Showing 40 flights", prices read from the cards non-decreasing): M1, the search always sends `sort: "departure"`: red `Error: prices in the order shown: 443.74, 573.95, 463.02, 572.33, 152.42, …  Expected: true Received: false`. M2, `getNextPageParam: () => undefined`: red `locator.click: Test timeout of 30000ms exceeded. waiting for getByRole('button', { name: 'Load more' })`.
- `'filters by origin and keeps the search in the URL'` (From "mad" → `?origin=MAD`, every card leaves MAD; reload keeps it; back returns to the unfiltered search with From empty; forward to MAD again): first run red for a harness reason, the previous search's cards read a moment after the URL changed (`- Expected - 1 + Received + 10`), so the check polls (`expectEveryCardFrom`). M3, the origin not sent: red `Error: every card should leave MAD … + "IBZ", + "FCO", …`. M4, `setParams(next, { replace: true })`: red `expect(page).toHaveURL(expected) failed Expected pattern: /\/$/ Received string: "about:blank"`.
- `'sorts by departure and by duration'` (the departure and arrival instants read from each card's `<time dateTime>`; the duration is their difference): first run red for a harness reason, `locator.check: Clicking the checkbox did not change its state` (the controlled radio re-renders after the URL changes), so it clicks and polls. M5, the sort not sent: red `Expected: true Received: false` at the departure poll.
- `'stacks the filters on a phone'` (390 × 844: each control below the one before, same left edge): M6, `flex-direction: column` removed from the 640 px query: red `getByRole('button', { name: 'Search' }) is below the control before Expected: >= 445.546875 Received: 384.359375`.

`re-price.spec.ts` (named after Task 9's project `re-price`; the plan's file list says `reprice.spec.ts`)
- `'opens a card and shows a confirmed price, a new price or an alternative'` (the card expands, its legs show, the panel says "Price confirmed: €…", "Price changed: …" or §6.5's alternative notice, and "Select"; waits up to 15 s, the re-price's 10 s budget plus a quote in flight): M7, opening a card does not call `reprice.mutate`: red `expect(locator).toContainText(expected) failed … Received string: ""`.

`accessibility.spec.ts`
- `'has no axe violations'` (the results, an opened card with its outcome, the empty state `?maxPrice=0.01`, the field error `?origin=MADR`; Q7's tags, colour contrast included, which the component tests cannot check): M8, `--color-tarmac` lightened to `#aab4be`: red `axe violations on http://localhost:3000/ … "color-contrast: Elements must meet minimum color contrast ratio thresholds (… and 123 more)"`. M9b, the field error's colour lightened to `#f4a6a0`: red only at the last scene, `axe violations on http://localhost:3000/?origin=MADR … (#origin-error)`, so every scene is really scanned. (A first M9, `aria-describedby` naming a missing id, stayed green: axe 4.14 does not flag it under these tags.)
- `'searches, loads more and opens a card with the keyboard only'` (Tab to From, type, Enter; Tab to the first card, Enter opens and re-prices it; Tab to "Load more", Enter): M10, `tabIndex={-1}` on the card's summary button: red `Error: Tab never reached getByRole('article').first().getByRole('button').first()`.

`admin.spec.ts` (the demo `admin` of the README's "Admin access")
- `'signs in through Keycloak and shows each provider'` (`/admin` → Keycloak's own page → back on `/admin` with a clean URL; the "Providers" table with the row headers Alpha, Beta, Gamma; axe on the page): M11, `public/config.json` naming client `dashboard-x`: red `locator.fill: Test timeout of 30000ms exceeded. waiting for getByLabel('Username or email')` (Keycloak refuses the unknown client and shows no login form).
- `'runs an ingestion on demand'` ("Ingestion started.", then the generation shown grows within 90 s): M12, the button's mutation posts nothing: red `Error: a generation after 3 Expected: > 3 Received: 3 - Timeout 90000ms exceeded while waiting on the predicate`.

`rate-limit.spec.ts`
- `'counts a client that forges X-Forwarded-For under its own address'` (a new RFC 5737 address in `X-Forwarded-For` per search; a 429 with a positive `Retry-After` within 121 searches, since 60 a minute in fixed windows can straddle two): M13, nginx's `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;` (appending instead of overwriting), image rebuilt: red `Error: a 429 within 121 searches Received: undefined`. Restored and rebuilt; green.

### E6: Firefox and WebKit (approved extra, assigned to Task 10)
- `playwright.config.ts`: each browser (Desktop Chrome, Firefox, Safari) runs smoke → search → re-price → accessibility → admin before the next browser starts, chained by `dependencies` on one worker; `rate-limit` runs once, last, in Chromium, since it sends its requests from Node, not from a browser. Browser after browser rather than spec after spec: run back to back, the three browsers' searches (about 17 each) and re-prices (3 each) would crowd one minute's limits (60 and 10); each browser's admin run spaces them out. The admin's run has published before the next browser starts, so no spec sees the generation change under it.
- `ci.yml`: `npx playwright install --with-deps chromium firefox webkit`.
- Red: the keyboard test failed in WebKit on the Mac, `Error: Tab never reached getByRole('article').first().getByRole('button').first()`: Safari on macOS gives Tab to text fields only, and Option+Tab to every control. Green with `nextControlKey(browserName)`: Alt+Tab for WebKit on macOS, Tab elsewhere (Linux WebKit, as in CI, takes Tab). M8 shown red in both new browsers (the image rebuilt with the light token, run in the Linux Playwright image): `[accessibility-firefox] … color-contrast` and `[accessibility-webkit] … color-contrast`; restored and rebuilt.
- Time: Chromium alone took 55 s (`npm run e2e`, 11 tests, on the Mac). All three in `mcr.microsoft.com/playwright:v1.64.1-noble` (the version pinned, Linux like CI, `--network host` against the same stack), on a stack just started: 31 passed in 3.0 min, by browser 62 s Chromium (including up to a minute waiting for the first run's lock, below), 41 s Firefox, 49 s WebKit, 0.3 s rate limit. On a warm stack the same run took 2.3 min. About 3 times Chromium's time, as E6 said, and far inside `timeout-minutes: 30`; CI adds the stack's build and the browser installs.

### Found on a stack just started (fixed in the tests, third commit)
- `'runs an ingestion on demand'` red on a fresh stack: `expect(locator).toBeVisible() failed Locator: getByText('Ingestion started.')`, the page saying "An ingestion is already running." with generation 1 published and the app's log `Ingestion run ended` at 04:46:25 then `Skipped an ingestion run: another run holds its lock` at 04:46:40. The scheduled run keeps the lock at least a minute from its start (`IngestionJob.SCHEDULED_LOCK_AT_LEAST_FOR`, plan 5), so a press within the app's first minute is refused although no run is running. The test now presses again every 10 s, for up to 70 s, until "Ingestion started." (`toPass`); green on a fresh stack (62 s for Chromium's block). See Concerns.
- `'filters by origin and keeps the search in the URL'` red once in the Linux container: `Error: No origin on the card: 22:20IBZ to PMI23:00Mon 23 Nov…`, a card being replaced gave its text without its layout's line breaks. `shownOrigins` no longer requires whitespace between the time and the code. Green in every browser since.

### Choices to review
- **Card values are read from the page**, never named: prices from the card's text ("€1,234.50"), instants from `<time dateTime>`, origins from the code after the departure time. Assertions that follow a new search poll, because the URL changes a moment before the list re-renders.
- **The re-price test accepts any of the three confirmed outcomes** (the dataset and the providers decide which); a 503 or any failure fails it. 15 s allowed (`REPRICE_ANSWERED_WITHIN_MS`, from search-api.md's 10 s budget plus a quote in flight).
- **The demo admin's credentials are in `admin.spec.ts`**, as the README and the realm file publish them.
- **The sort radio is clicked, not `check()`ed:** `check()` verifies the state right after its click, before the controlled radio re-renders from the URL.
- **`src/test/axeTags.ts`** holds Q7's tags for both kinds of tests (one list instead of two).
- **The CI job** starts the stack with `docker compose up --build -d --wait`, then `npm ci`, the browsers and `npm run e2e`; on failure or cancel it saves `docker compose logs` and uploads them with `playwright-report/` and `test-results/`; `docker compose down -v` always. `globalSetup` (Task 9) waits for the first ingestion.

### Concerns
- **"An ingestion is already running." for up to a minute after a scheduled run has ended** (the failing test above): the scheduled run's one-minute lock minimum (plan 5) answers 409 to a run on demand although nothing runs, and the dashboard shows the API's sentence. Production code not changed; for the user or plan 5's review to decide (the message, or the minimum).
- **Firefox does not start on this Mac** (macOS 27.2): Playwright 1.64.1's Firefox 157 exits with `Could not find profile folder.` whatever the profile, sandboxed or not, so `npm run e2e` exits 1 here at `smoke-firefox`. The three browsers ran green in the Linux Playwright image (the CI platform), and Chromium and WebKit natively (21 passed, 1.4 min, `--no-deps` over the Chromium, WebKit and rate-limit projects). Whether CI's Ubuntu runner starts it is seen on the first push.
- **A stale-route refresh during a run** (spec 6.4): a search for an origin whose last publication is over 10 minutes old starts a background refresh that publishes a new generation. On a stack up for a while, "Load more" during that refresh would get `inventoryChanged` and possibly fewer than 40 cards. CI and a fresh stack are inside the 10 minutes; not seen in any run here.

### Checks before the commits
- `npm run verify` in `frontend/`: exit 0 (typecheck, ESLint with jsx-a11y, Prettier, 111 tests in 19 files) before each commit and after the rebase. Frontend-only change: no `./gradlew build` (the user's rule of 2026-11-16).
- `npm run e2e` against `docker compose -p p6t10 up --build -d --wait`: Chromium only (Task 10's commit) exit 0, 11 passed in 55 s; all three browsers in the Linux Playwright image exit 0, 31 passed in 3.0 min on a fresh stack after the rebase; natively on the Mac, Chromium and WebKit exit 0 (21 passed), Firefox as in Concerns.
- Self-review (thermo-nuclear): one helper module for what two specs share (`cards.ts`: the origin poll and the re-price wait) and one for axe; the axe tags shared with the component tests instead of a second list; a search-only wait (`searchAnswered`) deleted once the polls made it redundant; the phone test sets its viewport itself instead of a `describe` block; the page size named once (`PAGE_SIZE`). No production code, no new mechanism: Playwright's own `expect.poll`, `toPass`, `dependencies` and `AxeBuilder`. The browser-and-platform branch (`nextControlKey`) is the one conditional added, for Safari's own keyboard rule. Nothing fails silently: every helper throws or fails with the values it saw (prices, origins, the axe rule and its elements).

### Extras for the user to decide
- **Cache Playwright's browsers in the e2e job** (`actions/cache` on `~/.cache/ms-playwright`, keyed by the Playwright version). Value: a minute or two less per run with three browsers. Cost: one step. Left out unless you approve.
- **Choose the browsers by an environment variable** (for example `E2E_BROWSERS=chromium,webkit`). Value: `npm run e2e` on a machine where a browser does not start (Firefox on this Mac). Cost: a few lines in the config. Left out unless you approve.
- **Screenshots and traces on failure** (`use: { screenshot: 'only-on-failure', trace: 'retain-on-failure' }`). Value: a failed CI run shows the page, not only the error. Cost: one line; bigger artefacts. Left out unless you approve.

## Task 10 — review

The double review (`.superpowers/sdd/2026-11-15-dashboard/task-10-review.md`) answered functional APPROVED and code APPROVED, with no Critical or Important finding. The reviewer ran `npm run verify` (111 tests) and the whole suite on a fresh stack in `mcr.microsoft.com/playwright:v1.64.1-noble` (31 of 31 in 2.3 min, three browsers and the rate-limit test), reproduced Firefox's start failure on macOS 27, and saw a background refresh of MAD in flight during "Load more" on a warm stack (it passed that time). The departures (`re-price.spec.ts`, browser-by-browser order with the rate-limit test once at the end, Alt+Tab for macOS WebKit) are justified; `b5f577c` tests the decided behaviour. Minors for the end-of-plan review:
- F-M1: on the Mac `npm run e2e` exits 1 at Firefox and skips WebKit and the rate-limit test; Task 11's run uses the Linux Playwright image.
- F-M2 (for the user): plan 5's decision says the 409 comes "only while a run is really running", but the scheduled run keeps its one-minute lock minimum, so a press within about 30 s after a short scheduled run still gets the 409.
- F-M3: the plan's "Chromium only, project order" decision line predates E6.
- F-M4: the duration half of the sort test was never shown red on its own; the report's "13 tests" are 10 tests and 13 mutations.
- C-M1: the admin test's `toPass` retries on any failure (a broken button costs 70 s; a slow 202 could start a second run).
- C-M2: the 16-project dependency chain skips every later project after one failure.
- C-M3: the keyboard test's "Load more" can race a refresh of MAD on a stack older than 10 min.
- C-M4: the card-reading helpers are split between `cards.ts` and `search.spec.ts`.

## The first CI end-to-end run (controller, 2026-11-16)

CI's first `e2e` job (run 43809886722, on `8882d86`) failed before any test: `docker compose up --build` builds `app` and `mock-gds` at once, and both Dockerfiles mounted the same BuildKit cache at `/root/.gradle/caches` without a lock, so one Gradle run timed out on the other's journal lock (`Timeout waiting to lock journal cache`). Fix, test first: `ComposeFileTest.locksEveryGradleCacheTheImageBuildsShare` (red: `Expecting actual ".././bootstrap/Dockerfile: --mount=type=cache,target=/root/.gradle/caches" to contain "sharing=locked"`), then `sharing=locked` on both Dockerfiles' Gradle cache mounts (green). `docker compose build --no-cache app mock-gds` then built both images in parallel with no lock timeout; `./gradlew build` green.

## Task 11 — evidence

Worktree branch `worktree-agent-a83fa39838af333e3`, from `main` at `2e4839c`. No production code changed. Files: created `docs/architecture/dashboard.md`; modified `README.md` (a "Use the dashboard" section with the frontend commands, the sign-in paragraph in present tense, the index entry), `docs/architecture/README.md` (the dashboard in the overview), `docs/planning/roadmap.md` (plan 6 row: linked, "tasks done, end-of-plan review pending", as plan 5's row reads until its review closes) and this report. The screenshots stay out of git, in the worktree's `.superpowers/sdd/2026-11-15-dashboard/task-11-screens/`, with `INDEX.md` (state → file), for the user's review against Q6.

### The full-stack run (2026-11-16, compose project `p6t11`)
- Host ports 3000, 3001, 8080, 8081, 8180 and 9090 were free, so no port override was needed.
- `docker compose -p p6t11 down -v`, then `docker compose -p p6t11 up --build -d --wait`: exit 0 in 45 s (06:33:04 → 06:33:49 UTC; the images' layers were cached by earlier builds), every service healthy (the Collector has no health check, by decision).
- **End to end**, in `mcr.microsoft.com/playwright:v1.64.1-noble` (`--network host --ipc=host`, a copy of `frontend/` without `node_modules`, `npm ci && npx playwright test`), as review finding F-M1 asked, since Firefox does not start on this Mac: exit 0, **31 passed in 3.1 min** on the stack just started (3 min 25 s with `npm ci`). Chromium 10 of 10, Firefox 10 of 10, WebKit 10 of 10, the rate-limit test 1 of 1. The slowest test, `runs an ingestion on demand`, took 31.7 s in Chromium and 52.5 s and 52.0 s in Firefox and WebKit, each waiting out the previous run's one-minute lock.
- **Every designed state walked through** (§6.8 and Q7's texts) with Chromium in the same image, a throwaway Playwright script outside the repository taking each state at 1280 × 900 and 390 × 844: 37 states, 74 screenshots. 29 states are the stack's own answers: loading (held), results, "Load more" and its "Loading…" (held), both sorts with filters, empty (`?maxPrice=0.01`), field errors from a hand-edited URL, "Try again" recovering, "Results updated." with "Reload" (a run on demand published between page 1 and page 2), the search's 429 and the re-price's 429 (each window spent from the same address), stops, "Multiple airlines", "+1" and airline names (MAD → JFK, IBZ → JFK), "Checking the price…" (held), `CONFIRMED`, `PRICE_CHANGED` ("was €519.00, now €578.51", the stub showing the new price), "Select" with E8's note, the 404 (the request's id rewritten to one never on sale), the 503 (`mock-gds` stopped), "Signing in…" (held), Keycloak's login, the status page, "Ingestion started.", the 409 "An ingestion is already running." (pressed again at once), the new generation, every provider Down with an open breaker and "Kept from the last good ingestion" (`mock-gds` stopped, generation 5 → 6), and the login again after "Sign out". 7 states cannot be produced on demand on a healthy stack and were injected with `page.route`, the page's handling being real: a 400 with its detail, nginx's HTML 502 with "Try again", `ALTERNATIVE` (its body built from a real itinerary of the page; the API's own alternative needs one provider to fail one leg), "No ingestion has run yet.", the 403 (no demo account lacks `ADMIN`), a failed status read and a failed sign-in (`/config.json` answering 500). One `PRICE_CHANGED` was injected too, before the real one was seen.
- Two of the script's own steps went red and were fixed in the script: its first search met a real 429 ("Try again in 13 seconds": the end-to-end rate-limit test had just spent the window), and a held route was released after it had been removed (`route.continue: Route is already handled!`).
- `/config.json` answered `{"oidcAuthority":"http://localhost:8180/realms/flight-aggregator","oidcClientId":"dashboard"}`; a plain `GET /admin` answered `index.html`.
- 06:45:32 `docker compose -p p6t11 down -v`: containers, network and volume removed.

### Defects found (reported, not fixed: Task 11 changes no production code)
- **D1. The admin screen is wider than a phone.** Steps: open `http://localhost:3000/admin` in a 390 × 844 viewport and sign in. The providers table needs 419 px (442 px with "Kept from the last good ingestion"), so the whole page scrolls sideways and the top bar ends before the table (`a03`–`a06`, `a12`). §6.8 asks only that the search's filters stack on a phone; the search screen fits at 390 px.
- **D2. "Sign out" is the browser's default button**, unlike every other button of Q6, and sits flush under the table. Steps: sign in to `/admin` (`a03`).
- **D3. After a re-price the stub's freshness still describes the search's quote.** Steps: open a card whose price changed: the stub shows the new price ("€578.51") beside "Updated 4 min ago", although that price was quoted seconds ago; a `CONFIRMED` card says the same. `ItineraryCard` swaps the price for the re-price's but always reads `itinerary.quotedAt`. §6.3 asks each result to show when its price was last updated.
- **Observation (minor).** A `/config.json` missing a field fails with "Sign-in failed. Reload the page to try again." only: the error that names the field is neither shown nor logged. In the image, `10-validate-config.sh` refuses such a value before nginx starts, so only `npm run dev` with an edited `public/config.json` can meet it.

### Checks before the commit
- `npm run verify` in `frontend/` (after `npm ci`): exit 0, 111 tests in 19 files, 26 s.
- `./gradlew build`: exit 0 (`BUILD SUCCESSFUL`, 68 tasks, most from the build cache since no Java changed).
- Every link and anchor of the four changed documents resolves (a script over the Markdown links); each fact in `dashboard.md` checked against the source, the realm file, `compose.yaml`, the nginx template and the CI jobs, and the served `/config.json` and fallback against the running stack.
- Self-review (thermo-nuclear) of the diff: documentation only, no new mechanism. The README keeps one copy of the dashboard's details (`dashboard.md`) and links to it; the macOS Firefox note and the Linux-image command live in `dashboard.md` only, the README pointing there.

### Extras for the user to decide
- **Order ties of a sort by departure** (the duration sort lists every 7h 40m MAD → JFK flight in id order, so dates jump: Thu 26 Nov, Sun 6 Dec, Sat 12 Dec…). Value: a calmer list. Cost: a second key in the API's keyset order and indexes, the cursor and plan 5's tests. Left out unless you approve.

## End-of-plan fix round

The three end-of-plan reviews (functional, code, risk; each CHANGES REQUESTED, `.superpowers/sdd/2026-11-15-dashboard/end-review-*.md`) found three Important findings, and their Minors, new and deferred, were all due in this round (the user's rule of 2026-11-15). Worked in its own worktree from `main` at `d92d03c`, rebased onto `main` twice (after plan 5's closing round 2, `fe8f7d9`, and after the `AdminSession` test fix that followed `0f71173`). Frontend, the dashboard's nginx files, `dashboard.md` and this report only.

**User decision (2026-11-16, about 08:10), risk I-1, the sign-in loop: option A, break the loop.** When the API refuses a freshly renewed token too, the admin client no longer sends the browser to Keycloak again; the 401 reaches the screen, which says "The API refused your session. Sign out and try again." with "Sign out" beside it. Only a renewal that itself fails leads to a new sign-in. (The copy is for the user's review, with M-2's texts; the controller updates the plan's Task 7 line.)

### Every finding and where it went

Commits on the branch `worktree-agent-a874083cf67ad6104`:

| Commit | Area |
|---|---|
| `5916937` | card and re-price |
| `a95e8e3` | auth client and session |
| `985fcb8` | time helpers |
| `1a31a04` | API client and errors |
| `b985438` | results list and search page |
| `03bbc7a` | nginx template, entrypoint, resolver and image |
| `8bfddf2` | end-to-end specs and the run on demand |
| `ebbbcaf` | styles and tokens |
| `11784be` | tooling |
| this commit | docs (`dashboard.md`, this report) |

| Finding | Outcome |
|---|---|
| Functional I-1 = D3 (code and risk graded it Minor): the stub's freshness after a re-price | Fixed, `5916937` |
| Code I-1 = risk M-1: a failed `/config.json` re-read on focus signed the admin out | Fixed, `a95e8e3` (`staleTime: Infinity`; the optional `stopSilentRenew` is moot, since the screen no longer unmounts) |
| Risk I-1: the sign-in loop on a token the API refuses | Fixed by the user's decision above, `a95e8e3` |
| Functional M-1: no test of `createAuthClient`'s settings | Fixed, `a95e8e3` |
| Functional M-2: copy never put to the user | **Left for the user**, the texts unchanged: "Price confirmed: €…", "Price changed: now €…" (no `previousPrice`), "Never", "Provider status could not be loaded. Retrying every 10 seconds.", "The ingestion could not be started. Try again in a moment.", the list names "Legs" and "Alternative legs"; and, new in this round by the user's decision, "The API refused your session. Sign out and try again." |
| Functional M-3: Task 11's extra missing from the backlog | Done before this round (`d92d03c`) |
| Functional M-4 = code M1: `useAdminToken` has no caller | Fixed, `a95e8e3` (deleted with its context, provider and two tests; a plan amendment for the controller under Tasks 7 and 8) |
| Functional M-5: stale comment in `client.test.ts` | Fixed, `a95e8e3` (the tests call `/api/admin/status`) |
| Functional M-6: a reopened changed-price card showed the old price | Fixed, `5916937` |
| Functional M-7 = D1: the admin screen wider than 390 px | Fixed, `ebbbcaf` (with an end-to-end test) |
| Functional M-8 = D2: "Sign out" in the browser's default style | Fixed, `ebbbcaf` |
| Functional M-9 (known): a missing setting not named | Fixed, `a95e8e3` (logged to the console; the screen's copy unchanged) |
| Code M2: three untested "2xx without a body" guards | Fixed, `1a31a04` (`bodyOf`) |
| Code M3: time helpers in the card's module | Fixed, `985fcb8` (`src/time/`) |
| Code M4: a failed sign-out fails silently | **Left for the user**: showing it needs a new sentence (M-2's rule), or the user accepts it as recorded in Task 7's E4 |
| Code M5: production lines written before their tests in Tasks 6 to 8 | **Left for the user** to accept as a recorded lesson; the lines never shown red by a unit test now are: `createAuthClient` (`a95e8e3`) and the body guards (`1a31a04`) |
| Risk M-2: a Keycloak outage sends the tab to a browser error page | **Left for the user**: telling a network failure of the renewal from a refused grant, and keeping the page with a message, is a design change beyond the I-1 decision and needs new copy |
| Risk M-3: `API_UPSTREAM` with a path passed the check | Fixed, `03bbc7a` |
| Risk M-4: resolver hard-coded to Docker's DNS | Fixed, `03bbc7a` |
| Risk M-5: a hung API kept "Loading flights…" for minutes; no `signal` | Fixed: `proxy_read_timeout 15s`, `03bbc7a`; the search passes TanStack's `signal`, `b985438` (the status query's key never changes, so it was left as is) |
| Risk M-6: client `X-Forwarded-Proto/Host/Port` reached the app | Fixed, `03bbc7a` |
| Risk M-7: the X-Forwarded-For caveat behind a load balancer | Fixed, this commit (`dashboard.md`, "nginx") |
| Risk M-8: the realm's `/admin*` wildcard redirect | **Left for the user**: the plan's decisions-table value, and the realm file is outside this round's files |
| T1 F4: Node 26.13.1 is a security release | Fixed, this commit (Task 1's version row) |
| T1 F6: why plan 6 has its own progress file | Fixed, this commit (the line at the top) |
| T1 F7: MSW evidence quoted the message, not the cause | Fixed, this commit (the cause, re-established by a probe) |
| T1 C2: deprecated `tseslint.config()` | Fixed, `11784be` |
| T1 C3: `eslint.config.js` in `tsconfig.node.json`; Node globals for `src/` | Fixed, `11784be` |
| T2 F-1: the "Contract drift" CI step runs after `verify` | **Left, with why**: `ci.yml` is outside this round's files (other plans' agents edit it); moving the step before `npm run verify` in job `frontend` is one line for the controller |
| T2 C-1 = T5 F-3: an HTTP-date `Retry-After` gave NaN | Fixed, `1a31a04` |
| T2 C-2: a bad problem body rejected with `SyntaxError`; case-sensitive media type | Fixed, `1a31a04` |
| T2 C-3: the drift test's Prettier resolution and `.pathname` | Fixed, `11784be` |
| T3 F-3: the filters stacked at exactly 640 px | Fixed, `ebbbcaf` |
| T3 F-4: input border below 3:1; tokens not in Q6 | Fixed, `ebbbcaf` (tarmac border; the two tokens commented as implied) |
| T3 F-5: the h1 in Barlow Condensed | Fixed, `ebbbcaf` |
| T3 C-2: `search()` built the URL twice | Fixed, `b985438` |
| T3 C-3: the same search pushed a history entry; the draft kept "mad" | Fixed, `b985438` |
| T3 C-4: the field list stated twice | Fixed, `b985438` |
| T3 C-5: the hidden radio had no positioned ancestor | Fixed, `ebbbcaf` |
| T4 F-1: the zone stub not asserted | Fixed, `5916937` |
| T4 F-2: "no float" not pinned | Fixed, `5916937` |
| T4 F-3: the freshness wrapped at 960 px; the notch comment | Fixed, `ebbbcaf` |
| T4 C-1: a malformed date throws, a malformed amount shows `€NaN` | **Left, with why**: choosing one policy for contract violations is a design change (throwing everywhere needs an error boundary with new copy; a placeholder everywhere is new display copy). Unreachable through the contract |
| T4 C-2: formatting rules in the component | Fixed, `5916937` (`stopsSummary` in `format.ts`; `flight()` was moved in Task 6) |
| T4 C-3: the visually hidden class in the card's module | Fixed, `ebbbcaf` |
| T5 F-2: a failed "Load more" left it enabled | Fixed, `b985438` |
| T5 F-4: an errored search refetched on focus | Fixed, `b985438` (focus and reconnect) |
| T5 C-1: "Reload" matched keys partially | Fixed, `b985438` |
| T5 C-2: "Try again" enabled while its retry ran | Fixed, `b985438` |
| T5 C-3: a next-page failure shown at the top | Fixed, `b985438` |
| T5 C-4: the untested empty-2xx guard | Fixed, `1a31a04` |
| T5 C-5: fixed 50 ms negative waits | Fixed, `b985438` |
| T5 C-6: the cache key and resets in two components | Fixed, `b985438` |
| T6 integration points | Judged acceptable by the code review; the reopened card's price is M-6 |
| T7 integration points | Acceptable (code review); `createAuthClient` tested (`a95e8e3`); the failed sign-out is code M4; one shared renewal stays in the backlog |
| T8 integration: polling after a 403 | Not built: an extra in `extras-backlog.md` |
| T9 F-1: `Host $host` not recorded as implied | Recorded here: implied by a same-origin proxy (the app sees the host the browser asked for, as with no proxy; nginx's default would be `app:8080`). No change |
| T9 C-2: no `Cache-Control` on `index.html`; a missing asset answered 200 HTML | The 404 fixed, `03bbc7a`. **`Cache-Control` left, with why**: a caching header, part of E5, which the user rejected |
| T9 C-3: `globalSetup` fetch without a timeout | Fixed, `8bfddf2` |
| T9 C-4: nginx's `user` directive warning | Fixed, `03bbc7a` |
| T9 C-5: the Dockerfile's explicit `COPY` list | Kept on purpose and said so in the Dockerfile, `03bbc7a`: it keeps `public/config.json` out of the bundle |
| T10 F-M1: `npm run e2e` stops at Firefox on macOS | No longer applies (documented) |
| T10 F-M2 / C-M1: the 409 in the scheduled lock's minute; the admin test's 70 s retry | Fixed, `8bfddf2`, now that plan 5 releases the lock when a run ends (`8401bc9`): the test presses once; `RunIngestionButton`'s comment and `dashboard.md` say so |
| T10 F-M3: the plan's "Chromium only" decision line | **Left, with why**: the plan file is the controller's; its line 113 predates E6 |
| T10 F-M4: the duration half of the sort test never red alone | Fixed, this commit (evidence below) |
| T10 C-M2: the 16-project chain skips everything after one failure | Kept by design (the order spaces the rate limits; Playwright has no order-only dependency); said in `playwright.config.ts`, `8bfddf2` |
| T10 C-M3: the keyboard test's exact 40 after a stale-route refresh | Fixed, `8bfddf2` (asks for more than 20 cards) |
| T10 C-M4: card readers in two files | Fixed, `8bfddf2` (`cards.ts`) |
| Plan 5 closing round 2, code review m7: the admin password hard-coded in `admin.spec.ts` | Fixed, `8bfddf2` (`ADMIN_USER_PASSWORD` first) |

Counts: fixed 54 (functional M-3, done before this round, and the 404 half of T9 C-2 among them); left for the user 5 (functional M-2, code M4, code M5, risk M-2, risk M-8); left with why 4 (T2 F-1, T4 C-1, T9 C-2's `Cache-Control`, T10 F-M3); kept by design and documented 2 (T9 C-5, T10 C-M2); not built as a backlog extra 1 (polling after a 403).

### Red → green evidence

#### Card and re-price (functional I-1 = D3, M-6; T4 F-1, F-2, C-2)
- `'shows how fresh a changed price is'` (PRICE_CHANGED quoted at `now`): red `Unable to find an element with the text: Updated just now` (stub read `itinerary.quotedAt`). Green: the stub reads one `quote` (price and quotedAt together).
- `'shows how fresh a confirmed price is'` (CONFIRMED quoted at `now`): red, same message (only PRICE_CHANGED replaced the quote). Green: any answer but ALTERNATIVE replaces the quote. The ALTERNATIVE exclusion is pinned by the existing `'shows the alternative inside the card with its notice'`: mutation (exclusion removed) red `Unable to find an element with the text: €412.60`; restored.
- `'keeps a changed price on the card while it is checked again'` (M-6; the second check never answers): red `Unable to find an element with the text: €431.00` (TanStack clears the mutation's data when it runs again, so the stub fell back to €412.60). Green: the card keeps the last confirming answer in its own state (`requote`, set in `mutate`'s `onSuccess`).
- `'shows a newer search quote than the last re-price'` (a Reload brings the same id quoted after the re-price; `renderWithClient` now passes the provider as `wrapper` so `rerender` keeps it): red `Unable to find an element with the text: €440.00`. Green: the requote wins unless the search's `quotedAt` is newer (`>=`, so an answer quoted at the same instant still wins; two existing tests whose fixture re-price has the search's `quotedAt` went red with `>` and green with `>=`).
- T4 F-1: the zone stub is asserted in `beforeEach`. Under `--pool=threads` with `TZ=America/New_York` every test of the file now fails instead of passing vacuously.
- T4 F-2: `price('12345678901234567.89')` → `€12,345,678,901,234,567.89`. Mutation `EUROS.format(Number(amount))`: red `expected '€12,345,678,901,234,568.00' to be '€12,345,678,901,234,567.89'`; restored.
- T4 C-2: `stopsSummary` moved to `format.ts` (refactor; the card tests cover it).

#### Auth client and session (code I-1 = risk M-1; risk I-1 by the user's decision; functional M-1, M-4 = code M1, M-5, M-9)
- `'keeps the admin screen when the window regains focus'` (the second `/config.json` read is a network error; `focusManager` off and on): red `AssertionError: expected 2 to be 1` (the settings were read again on focus). Green: `staleTime: Infinity` on the config query.
- Risk I-1, the user's decision of 2026-11-16 (option A, break the loop):
  - `client.test.ts` `'answers 401 without signing in again when the renewed token is refused too'` (replaces `'signs in again when the renewed token is refused too, without a loop'`): red `AssertionError: expected "vi.fn()" to not be called at all, but actually been called 1 times` (`signinRedirect`). Green: `bearerToken` redirects only when the renewal itself fails, and otherwise returns the repeat's answer, so `apiErrors` throws its 401.
  - `AdminPage.test.tsx` `'says when the API refuses the session, without signing in again'` (whole screen: status always 401, renewal succeeds): red `Expected element to have text content: The API refused your session. Sign out and try again. Received: Provider status could not be loaded. Retrying every 10 seconds.` Green: `adminFailure` maps a 401 to that sentence (shared by the status and "Run ingestion now"); "Sign out" stays on the screen.
  - The other path, `'signs in again when the renewal fails'`, was green already: mutation (the `signinRedirect()` of the failed-renewal branch removed) red `AssertionError: expected "vi.fn()" to be called once, but got 0 times`; restored.
- Functional M-1, `authClient.test.ts` (tests over existing code, both green at once): `'signs in with Authorization Code back to /admin, and signs out back there'`; mutation (`post_logout_redirect_uri` removed) red `- "post_logout_redirect_uri": "http://localhost:3000/admin", + "post_logout_redirect_uri": undefined`. `'keeps the tokens in memory only'` (`storeUser`, then both web storages empty); mutation (`userStore` removed, the library's sessionStorage default) red `AssertionError: expected 1 to be +0`. Both restored.
- Functional M-4 = code M1: `adminToken.ts` (`useAdminToken`, `AdminTokenContext`), its provider in `AdminSession` and its two tests deleted; `useAdminClient` is the one context (a plan amendment for the controller to record under Task 7/8).
- Functional M-5: `client.test.ts` calls `/api/admin/status` (the stale comment and the search path are gone).
- Functional M-9 (known): `'logs which setting is missing'`: red `expected "error" to be called with arguments: [ ObjectContaining{…} ] Number of calls: 0`. Green: `AdminPage` logs the config error with `console.error` in an effect.

#### Time helpers (code M3)
- `minutesBetween`, `timeAgo`, a new `minutesUntil` (the status page's own arithmetic) and `useNow` with its test moved to `src/time/`; refactor, the existing format, status and clock tests stay green (118).

#### API client and errors (T2 C-1 = T5 F-3, T2 C-2, T5 C-4 = code M2)
- `'reads a Retry-After given as a date'` (fake clock 10:00:00, header 10:00:30 GMT): red `AssertionError: expected NaN to be 30`. Green: `retryAfterSeconds` reads whole seconds or an HTTP date (ceil, never below 0).
- `'ignores a Retry-After it cannot read'` (`soon`): red `AssertionError: expected NaN to be undefined`. Green: an unparsable date gives `undefined`, so the screens show their sentence without a wait.
- `'leaves the detail empty for a problem body it cannot read'` (a cut-off JSON problem, 429): red `SyntaxError: Unterminated string in JSON at position 19`. Green: `problemOf` catches the parse error; the `ApiError` keeps its status and `Retry-After`, so a query does not retry it as a network failure.
- `'reads a problem whatever the case of its media type'` (`Application/Problem+JSON; charset=utf-8`): red `AssertionError: expected undefined to be 'origin is not valid'`. Green: the media type is lower-cased before the check.
- `client.test.ts` `'rejects a 2xx answer without a body'`: red `TypeError: bodyOf is not a function`. Green: `bodyOf(call)` in `client.ts`; the three hooks (`useItinerarySearch`, `useReprice`, `useInventoryStatus`) use it instead of three untested copies. Mutation (its `throw` removed) red `AssertionError: promise resolved "undefined" instead of rejecting`; restored.

#### Results list and search page (T5 F-2, F-4, C-1, C-2, C-3, C-5, C-6; T3 C-2, C-3, C-4; risk M-5's signal)
- T5 F-4, `'does not search again when the window regains focus after a failure'` (a 429; focus off and on inside an async `act`, since TanStack's focus listener refetches a few microtasks later): red `AssertionError: expected 2 to be 1`. Green: `refetchOnWindowFocus: false` on the search; the hook's comment now says why (a query without data is stale whatever its staleTime).
- Same, `'does not search again when the connection comes back after a failure'` (`onlineManager` off and on), written with `refetchOnReconnect: false` taken out first: red `AssertionError: expected 2 to be 1`. Green with it back.
- T5 C-5: the three fixed 50 ms waits are gone. `renderAt` returns its `queryClient`; the negative tests assert `queryClient.isFetching()` is 0 and the request count, after an async `act` where focus is involved. Each shown red by a labelled mutation, restored: `enabled: true` → `'does not search while a filter is invalid'` red `expected 1 to be +0`; the error-only predicate made `() => true` → `'keeps the shown results when the same filters are searched again'` red `expected 2 to be 1`; `refetchOnWindowFocus: 'always'` → `'does not reload the results by itself when the window regains focus'` red `expected 2 to be 1`.
- T5 F-2, `'does not offer Load more again after a rate-limited next page'`: red `expected document not to contain element, found <button` ("Load more"). Green: "Load more" is not rendered while the next page is in error.
- T5 C-3, `'shows a next-page failure where Load more was'` (a 502 on page 2; the alert must follow the list in document order, with one "Try again"): red `AssertionError: expected +0 to be truthy`. Green: one `failureNotice` element, rendered above the list for a first-page failure and in place of "Load more" for a next-page one.
- T5 C-2, `'cannot try again while a failed next page is being tried again'` (the repeat never answers): red `Received element is not disabled`. Green: "Try again" is `disabled` while the search fetches. (A first version on a first-page failure could not go red for this reason: without data TanStack goes back to `pending`, so the button disappears; recorded and rewritten on the next page, where the error stays during the repeat.)
- T5 C-1, `'reloads only the search shown'` (MAD → JFK cached, then MAD, a flagged page, "Reload", Back): red `AssertionError: expected [ …(2) ] to have a length of 1 but got 2` (the partial key match had reset MAD → JFK too). Green: `exact: true`.
- T5 C-6 (refactor in the same step): the key is private to `useItinerarySearch.ts`, which exports `useSearchResets()` with `reload(criteria)` and `retryIfFailed(criteria)`; neither component holds a `QueryClient` or the key any more.
- Risk M-5 (client side), `'stops loading a search the customer has left'` (the first request hangs; the customer picks "Duration"): red `AssertionError: expected false to be true` (the abandoned request's signal). Green: the query function passes TanStack's `signal` to `api.GET`.
- T3 C-3, `'adds no history entry for the search already shown'` (MAD, then "mad" again, then Back): red `expect(element).toBeEmptyDOMElement()` (Back stayed on `?origin=MAD`). Green: `search()` returns after `retryIfFailed` without `setParams`.
- T3 C-3, `'shows the normalised value when the search is already shown'` (" mad " over `?origin=MAD`): red `Expected the element to have value: MAD Received: " mad "`. Green: `FilterBar` resets its draft to the URL's values after a submit (when the URL changes, the render-time sync replaces them in the same render).
- T3 C-2 (refactor): `toSearchParams` normalises each value as `parseSearch` reads it, so `search()` builds the URL once. Mutation (the normalisation removed) red in three tests (`'restores the previous search on back'`, `'adds no history entry…'`, `'keeps the focus in the form…'`); restored.
- T3 C-4 (refactor): `FilterBar` maps the exported `FILTER_FIELDS` through `CONTROLS: Record<FilterField, …>`, so a new field cannot compile without its control; the airport rule is one `AIRPORT` object used twice.

#### nginx template, entrypoint, resolver and image (risk M-3, M-4, M-5 server side, M-6; T9 C-2, C-4, C-5)
Each case was added to `frontend/nginx/test-config-validation.sh` and run against the image built from the code before its fix (`docker build -t p6close-frontend:wip frontend`), then rebuilt with the fix.
- Risk M-3, `API_UPSTREAM='http://app:8080/'`: red `FAIL: expected a refusal naming API_UPSTREAM (exit 0)`. Green: the upstream pattern is a scheme, a host and an optional port only (`^https?://[A-Za-z0-9.-]+(:[0-9]+)?$`), which also drops `?`, `&`, `@` and `%`.
- Risk M-4 (the container on Docker's default bridge, where 127.0.0.11 does not exist): red `FAIL: resolver '127.0.0.11', nameserver '192.168.65.7'`. Green: `resolver ${NGINX_LOCAL_RESOLVERS} valid=10s;` with `ENV NGINX_ENTRYPOINT_LOCAL_RESOLVERS=1`, the image's own `15-local-resolvers.envsh` (in compose it still renders 127.0.0.11).
- Risk M-6 (a client sends `X-Forwarded-Proto: https`, `X-Forwarded-Host: evil.example`, `X-Forwarded-Port: 444`; the upstream is the same image running an nginx that echoes what reaches it): red `FAIL: the app received: uri=/api/itineraries?origin=MAD proto=https host=evil.example port=444`. Green: the three headers are set empty, so nginx does not send them.
- T9 C-2 (`/assets/missing.js`): red `FAIL: a missing asset answered 'HTTP/1.1 200'`. Green: `location /assets/ { try_files $uri =404; }`. `Cache-Control` on `index.html` was not added (E5, caching headers, rejected by the user).
- T9 C-4: red `FAIL: nginx warned at start: … the "user" directive makes sense only if the master process runs with super-user privileges`. Green: the Dockerfile's `sed` also deletes the `user` line.
- Risk M-5, server side (an upstream that accepts the connection and never answers: the image's busybox `nc -l` with its standard input kept open; a first try without `-i` closed at once and gave a 502, a harness fault): red `FAIL: a hung app answered '' after 41 s` (wget gave up at 40 s; nginx's default is 60 s). Green: `proxy_read_timeout 15s` → `ok: a hung app answers 504 after 16 s`.
- T9 C-5: no change of mechanism; the Dockerfile says why its `COPY` list is explicit (it keeps `public/config.json` out of the bundle).
- All twelve cases `ok` on the final image.

#### Styles and tokens (functional M-7 = D1, M-8 = D2; T3 F-3, F-4, F-5, C-5; T4 F-3, C-3)
- D1, a new end-to-end test `admin.spec.ts` `'fits the provider status on a phone'` (390 × 844, signed in; the providers table's right edge), run against the stack `p6close` built before the fix: red `Error: the table ends within the phone … Expected: <= 390 Received: 416.1875`. Green after `docker compose -p p6close up --build -d --wait frontend` with the fix: under 640 px the table's cells take `--space-1` padding, 0.875 rem text (0.75 rem headers) and `overflow-wrap: anywhere`.
- D2 (style only): "Sign out" takes the screen's bordered button style and sits `--space-4` below the table.
- T3 F-3: both media queries are `max-width: 639.98px`, so a 640 px window keeps one row (checked with a screenshot at 640 px: the filters wrap, they do not stack).
- T3 F-4: the inputs' border is tarmac (about 5.4:1 on white, WCAG 1.4.11's 3:1); `--color-surface` and `--color-border` are commented as implied by Q6's flat cards, decorative only.
- T3 F-5: the h1 is in Barlow (Q6 keeps Barlow Condensed for codes and times).
- T3 C-5: `.option` is `position: relative`, the hidden radio's positioned ancestor.
- T4 F-3: the freshness is `white-space: nowrap` (one line at 960 px, screenshot); the notch comment now says what the CSS draws (half circles over the card's edge, the border line running under them).
- T4 C-3: the visually hidden utility is the global `.visually-hidden` in `global.css`.

#### Tooling (T1 C2, C3; T2 C-3)
- T1 C2: `eslint.config.js` uses ESLint's own `defineConfig` (from `eslint/config`) instead of the deprecated `tseslint.config()`.
- T1 C3: `eslint.config.js` left `tsconfig.node.json`'s `include` (a `.js` file there type-checks nothing without `allowJs`); Node globals apply only to the tool configs, `e2e/` and the drift test, `src/` gets the browser's alone. `npx eslint .` and `tsc -b` clean.
- T2 C-3: the drift test resolves Prettier with `editorconfig: true`, as the CLI does, and builds the path with `fileURLToPath`. Green (1 test); no test of its own (the repository has no `.editorconfig` today, so the output is the same).

#### T10 F-M4, the duration half of the sort test
- Mutation (labelled `MUTATION F-M4`): the search sends `departure` when the criteria say `duration`; the compose `frontend` stopped and the mutated source served by `npx vite --port 3000 --strictPort`; `search-chromium --no-deps -g 'sorts by departure and by duration'` red at the duration poll, after the departure half passed: `Error: a first page in duration order … Expected: true Received: false`. Restored (`git status` clean of `src/`), the compose `frontend` started again: 1 passed.

#### Gates
- `npm run verify` in `frontend/` before every commit, the last after the second rebase: typecheck, ESLint, Prettier, 132 tests in 20 files, exit 0. Frontend-only round: no `./gradlew build` (the controller runs it before integrating).
- Image test `frontend/nginx/test-config-validation.sh` on the final image: 12 of 12 `ok`.
- End to end, on a stack just started (`docker compose -p p6close down -v`, then `up --build -d --wait`: 07:44:24 → 07:45:01 UTC), in `mcr.microsoft.com/playwright:v1.64.1-noble` (`--network host --ipc=host`, a copy of `frontend/` without `node_modules`): exit 0, **34 passed in 3.1 min** (Chromium 11, Firefox 11, WebKit 11, the rate-limit test 1). `runs an ingestion on demand` pressed once in each browser and took 50.4 s, 51.9 s and 50.9 s (waiting for the run to publish). The stack was taken down with `-v`.
- Thermo-nuclear self-review of each area's diff, before its commit: the existing mechanisms are used (TanStack's `signal`, `exact`, `refetchOn*`, `staleTime`; the nginx image's `15-local-resolvers.envsh`; `openapi-fetch` middleware; ESLint's `defineConfig`); one place each for the body guard (`bodyOf`), the search's key and its resets (`useSearchResets`), the failure notice (`failureNotice`), the time helpers (`src/time/`) and the visually hidden class. Nothing new fails silently: the config error is logged, a refused session says so, a hung app answers 504. Known limit, recorded: on a refused session the status keeps its 10 s refresh, each one renewing once more against Keycloak (stopping the poll is the backlog's "stop polling after a 403" kind of extra).

## End-of-plan fix round — re-review verdicts

Triple re-review on `5229c2f..b6fd160`, 2026-11-16 (`.superpowers/sdd/2026-11-15-dashboard/close-rereview-{functional,code,risk}.md`): **functional APPROVED, code APPROVED, risk APPROVED**; plan 6 closes. I-1 (D3), code I-1 (config read once) and risk I-1 (the sign-in loop, the user's option A; probe: 60 s of refused sessions, 14 requests, 7 renewals, 0 redirects) are fixed. New Minors, for a short last round:

| Id | Where | Finding |
|---|---|---|
| F m-1 | `ResultsList.tsx:78,104-117` | After a next-page 429, only "Search" continues, and it reloads from page 1; show "Load more" again once `Retry-After` has passed |
| C N1 | `FilterBar.tsx:47-53` | After a new search the inputs flash their old values for one render; set the draft to the normalised submitted values |
| C N2 | `default.conf.template:28-30` | `proxy_read_timeout 15s` is below the slowest re-price (10 s plus a quote in flight, 2.5 s × 3); about 20 s, or correct the comment |
| C N3 | `apiError.ts:30-36` | An empty or negative `Retry-After` gives "0 seconds" or "-5 seconds"; accept only `^\d+$` |
| R m-1 | `client.ts:65-68` | Redirect only when the refused token has expired (`user.expired`), closing the last double-fault loop (fresh tokens refused and renewal impossible) |
| R m-2 | `dashboard.md:120` | nginx's resolver ignores `resolv.conf`'s search domains: outside Docker `API_UPSTREAM` must be a fully qualified name; say so |
