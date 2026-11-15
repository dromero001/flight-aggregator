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
