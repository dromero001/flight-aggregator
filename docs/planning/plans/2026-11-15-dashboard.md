# Dashboard Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. Every production change follows `superpowers:test-driven-development` and `CLAUDE.md`. Reviews follow the review protocol in `CLAUDE.md` as changed from plan 5 on: **one reviewer per task**, acting as `functional-reviewer` and then as `code-reviewer`, gives two verdicts; at the end of the plan, `functional-reviewer`, `code-reviewer` and `risk-reviewer` review the whole diff separately. Tasks marked parallel in the task map may run in their own git worktrees (`superpowers:using-git-worktrees`); each is reviewed before it reaches `main`.

**Goal:** the two approved screens of §6.8, a public flight search and an admin provider-status page behind a Keycloak login, served by nginx in docker compose, typed from the committed OpenAPI contract, and proven end to end with Playwright and axe in CI.

**Architecture:**
- `frontend/` is a React + TypeScript (strict) + Vite single-page app. The URL's query string holds the search; TanStack Query holds the server state (an infinite query for "Load more", a mutation for re-price, a polled query for the admin status). No global state manager.
- `src/api/schema.d.ts` is generated from `docs/architecture/openapi.json` (plan 5) and committed; every request and response type comes from it, so a contract change breaks the compile.
- A multi-stage image builds the bundle and serves it from nginx, which proxies `/api/` to `app`. The browser signs in to Keycloak on its own origin with Authorization Code + PKCE and sends the token only to `/api/admin/**`.

**Tech Stack:** React, TypeScript (strict), Vite, TanStack Query, Vitest + React Testing Library + MSW (jsdom), Playwright + `@axe-core/playwright`, ESLint (typescript-eslint) + Prettier, nginx; the libraries of Q2. **No version is chosen yet:** Task 1 picks every one by §6.1 from the npm registry and records it.

**Spec:** [design spec](../design-spec.md), sections:
- §2 (the four filters, all optional; non-goals), §4 (Carrier = every leg; freshness = the oldest leg's quote), §4.2 (the airport's local wall-clock time).
- §5.4: "Load more", sort tabs, no total count, "Showing N flights", no "Best" sort.
- §6.3: "updated X min ago", "results updated, reload". §6.5: the alternative notice and the 503 message, verbatim.
- §6.6: public search and re-price; admin through Keycloak and the `ADMIN` role, here with the browser login (Authorization Code + PKCE, [session 09](../../ai/sessions/09-lost-sessions-and-recovery.md)).
- §6.8: the stack, nginx with the `/api` proxy, generated types, TanStack Query, the URL as state, every designed state, axe, Playwright, the two screens. §6.9: English. §6.11: the frontend, contract-drift, image and end-to-end CI steps.
- [Session 05](../../ai/sessions/05-scope-guard-and-agent-skills.md): exactly the brief's four filters, nothing more.

Roadmap: [plan 6](../roadmap.md). **Plan 5 must be closed before every task that needs its final contract** (task map; Tasks 1 and 3 can start earlier, and Task 9 needs plan 5's stack on `main`). This plan consumes its committed `docs/architecture/openapi.json`, `GET /api/itineraries` (cursor, `inventoryChanged`, default page size 20), `POST /api/itineraries/{id}/reprice` (200 with `outcome` `CONFIRMED | PRICE_CHANGED | ALTERNATIVE`, 503 + `Retry-After`, 404, 429), `GET /api/admin/status`, `POST /api/admin/ingestions` (202, or 409 *"An ingestion is already running."*), the realm file `deploy/keycloak/flight-aggregator-realm.json` with its demo `admin` user, and the compose services `app`, `valkey` and `keycloak` (issuer `http://localhost:8180/realms/flight-aggregator`).

**Status:** approved by the user on 2026-11-15 (session 09). Earlier status: draft for the user's review on 2026-11-15. Not approved; the open questions below are unanswered.

## Global Constraints

- **Location:** `frontend/` at the repository root (§6's module table); compose service `frontend`.
- **Version policy (§6.1):** every npm package, the Node version, the `node` and `nginx` base images and `actions/setup-node` take the newest stable GA at least 14 days old, never a prerelease. npm versions come from `npm view <pkg> time --json` (the registry is the primary source), Node from `https://nodejs.org/dist/index.json` (the active LTS line), images by tag and multi-arch digest, the action by commit SHA with its version as a comment. Exact pins (`save-exact`), `package-lock.json` committed, `npm ci` everywhere. Recorded in the progress report.
- **Stack fixed by §6.8:** the list above; light styling, no component library, no global state manager.
- **Scope (§6.8, session 05):** filters exactly From, To, Airline, Max price, all optional; sort exactly price (default), departure, duration. No date or stops filter, side panel, provider, leg price, total count, "Best" sort, badge or logo.
- **Copy:** English. Fixed by the spec: "Showing N flights", "Load more" (§5.4); "updated X min ago", "results updated, reload", "price changed: was X, now Y" (§6.3); "Multiple airlines", "Select", "Run ingestion now" (§6.8); *"We couldn't confirm this flight. This is the best alternative we found."* and *"We can't confirm the price right now. Please try again in a few minutes."* (§6.5). Every other text is Q7's.
- **Times and prices:** a leg's time is shown as the wall clock written in its own ISO-8601 string (§4.2), never converted to the browser's zone. Prices are the API's decimal strings, formatted for display only; no price arithmetic in the browser.
- **Accessibility (§6.8):** semantic HTML, a label on every control, everything reachable by keyboard, notices in live regions, colour never the only signal; axe reports zero violations (Q7's rules) in the end-to-end tests.
- **Gates:** TDD one test at a time, red → green evidence in `docs/planning/progress/<date>.md`, section "Dashboard run (plan 6)"; an end-to-end test over code that already exists is shown red by mutation. Before every commit: `npm run verify` in `frontend/` and `./gradlew build` (Q1-A), and the thermo-nuclear self-review. Scope by `brief-scope`; extras found mid-task go to the progress report. No AI attribution; push only after `direnv exec . gh api user --jq .login` prints `dromero001`, then watch the CI run.

## Open questions for the user

**Answered by the user on 2026-11-15 (session 09):** Q1 A (a standalone npm project in `frontend/`, same repository), Q2 A, Q3 A, Q4 A, Q5 A, Q6 A (boarding pass), Q7 A. Extras approved: E1 ("+1" next-day arrival), E2 (airline names), E3 (airport and airline suggestions — needs a catalogue endpoint from plan 5), E4 (sign out), E6 (Firefox and WebKit in end to end), E7 (axe in component tests and the jsx-a11y lint plugin), E8 (a note when "Select" is pressed), E9 ("Try again"). Rejected: E5 (nginx security and caching headers). If the delivery date is at risk, E3 and E6 are the first to cut, with the user's approval.


**Q1. Where the frontend builds.** `settings.gradle.kts` says the frontend "is added when its first task starts", and §6's table lists it among the Gradle subprojects.
- A. A standalone npm project, outside Gradle: its own gate (`npm run verify`) next to `./gradlew build`, and its own CI job. Task 1 rewrites the settings comment, the spec's module row and one line of `CLAUDE.md`'s "Before every commit".
- B. A Gradle subproject through the `com.github.node-gradle.node` plugin, which downloads a pinned Node: `./gradlew build` runs the frontend checks too.
- C. A Gradle subproject whose tasks call the system `npm`: one gate, but `./gradlew build` breaks on a machine without Node.
- **Recommendation: A.** Each side keeps its canonical toolchain; `./gradlew build` keeps working without Node on both machines; §6.11 already gives the backend and the frontend separate jobs, which B would run twice; the image, Playwright and Dependabot use npm directly anyway. Tasks: 1, 2, 10.

**Q2. Libraries the spec does not name.**

| Need | Recommended | Alternatives | Why |
|---|---|---|---|
| Types from OpenAPI | `openapi-typescript` (types only) + `openapi-fetch` (typed `fetch`, same maintainers) | `orval` or `@hey-api/openapi-ts` (generated clients and hooks) | The drift check diffs one declaration file; the hooks stay hand-written TanStack Query (§6.8) |
| Router | `react-router` (library mode) | TanStack Router; a hand-written `popstate` hook | The most widely known; `useSearchParams` holds the filters and the back button works (§6.8) |
| Browser login | `oidc-client-ts`, tokens kept in memory | `keycloak-js` (ties the app to Keycloak); hand-written PKCE | Standard OIDC keeps Keycloak swappable by configuration (§6.6); no script can read a stored admin token, and a reload passes back through Keycloak's session without the password |
| Styling | CSS Modules (built into Vite), one tokens file | Tailwind; global CSS | No dependency, scoped names ("light styling", §6.8) |
| Fonts | `@fontsource` packages, self-hosted | the system font stack | No call to a font CDN at runtime (with Q6) |

- A. These. B. Change any row. **Recommendation: A.** Tasks: 1, 2, 3, 7.

**Q3. Where the dashboard's settings come from** (the OIDC authority and client id; the `/api` upstream is nginx's own setting either way).
- A. At container start: nginx renders `/config.json` from environment variables (the official image's templates), and the admin screen reads it before signing in. One image for every environment.
- B. At build time: `VITE_OIDC_*` build arguments baked into the bundle. Simpler; the image only works where Keycloak is `localhost:8180`.
- **Recommendation: A.** "As close as possible to a real production frontend" (§6.8): an image is promoted between environments, not rebuilt; the cost is one template location and one fetch on the admin screen only. Tasks: 7, 9.

**Q4. "Load more" on a page flagged `inventoryChanged`** (plan 5 Q1-A seeks the old cursor's position on the new generation and returns rows).
- A. Append the page's itineraries that are not already shown, and show "Results updated." with a "Reload" button that searches again from the first page.
- B. Drop the flagged page's rows; show only the notice and "Reload".
- C. Reload from the first page automatically.
- **Recommendation: A.** Plan 5 returns rows so the user keeps their place; §6.3 says the dashboard *offers* a reload, which rules out C; skipping repeated ids keeps one card per itinerary. Tasks: 5.

**Q5. What each re-price outcome shows.** §6.8 names "Select" only for an unchanged price.
- A. "Select" after every outcome the API confirmed: `CONFIRMED` (the price), `PRICE_CHANGED` ("Price changed: was €X, now €Y", the card's price becomes Y), `ALTERNATIVE` (the §6.5 notice and, inside the same card, the alternative's legs and price).
- B. "Select" only after `CONFIRMED`; the other two show their notice and the customer opens the card again to re-confirm.
- C. As A, but the alternative replaces the chosen card in the list.
- **Recommendation: A.** All three are prices the API has just confirmed and stored (§6.5: we never sell an *unconfirmed* price), so B makes the customer confirm twice; C reorders the list under the customer's eyes. Tasks: 6.

**Q6. Visual direction** (from the `frontend-design` skill; §6.8 fixes the layout, not the look).
- A. **Boarding pass.** Each card reads like a boarding pass: times and IATA codes large on a route line with a dot per stop, and a perforated stub on the right holding the price and the freshness. Palette: ink `#1B2A41` (text, top bar), cabin `#F5F7FA` (background), tarmac `#5C6B7A` (secondary text), beacon `#E8A33D` (the Search and Select buttons, ink text on it), cleared `#1E7F4F` (confirmed, UP), hold `#B3261E` (errors, DOWN). Type: Barlow for text and Barlow Condensed for codes and times (one family, two widths). Flat cards with a 1 px border, no shadows; one column up to 880 px, left-aligned; the filters in one row, stacked under 640 px (§6.8). The route line is the one bold element.
  ```
  08:15  MAD ──────●────── JFK  13:40   ┆  €412.60
  Fri 20 Nov   7h 25m   1 stop: LHR, 1h 10m      ┆  Updated 4 min ago
  IB 3166, BA 117   Multiple airlines             ┆
  ```
- B. **Plain OTA.** White, one blue accent, the system font, cards with a soft shadow: fastest, but it reads like every template.
- **Recommendation: A.** A traveller scans times, codes and price; the boarding-pass layout puts exactly those first, and its contrast pairs pass AA (axe checks them). Tasks: 3, 4, 6, 8.

**Q7. Values and texts the spec leaves open.**

| Setting | Recommended | Why |
|---|---|---|
| Dashboard address | `http://localhost:3000`, published on `127.0.0.1` | Loopback like every service; 8080, 8081 and 8180 are taken |
| Keycloak client | `dashboard`: public, standard flow, PKCE `S256`, no direct grants | A browser app cannot keep a secret |
| Locale | `en-GB`: "Fri 20 Nov", "08:15", "€1,234.50" | English (§6.9), EUR, the 24-hour clock of airports |
| Freshness | "Updated just now" under 1 min, "Updated N min ago" under 60, then "Updated N h ago"; re-rendered every 60 s | §6.3's text, still true on a page left open |
| Status screen | refetched every 10 s while the tab is visible; times relative ("Published 12 min ago", "Next ingestion in 18 min", "Next ingestion due") | Shows a started run ending without an extra control; instants with no airport need no time zone |
| axe rules | tags `wcag2a`, `wcag2aa`, `wcag21a`, `wcag21aa`; zero violations | The level the European Accessibility Act asks of e-commerce |

Texts: headings "Find flights" and "Provider status"; "Showing 1 flight"; field errors "Enter a 3-letter airport code, like MAD", "Enter a 2-character airline code, like IB", "Enter an amount in euros above 0, like 150 or 149.99"; "Loading flights…", "Loading…" (on Load more), "Checking the price…", "Signing in…"; empty "No flights match these filters. Remove a filter or raise the maximum price."; failure "Flights could not be loaded. Search again in a moment." (a 400 shows the API's `detail`); 429 "Too many searches from your connection. Try again in N seconds." and "Too many price checks from your connection. Try again in N seconds."; 404 "This flight is no longer available. Search again for current flights."; admin "This account does not have the admin role.", "Sign-in failed. Reload the page to try again.", "No ingestion has run yet.", "Ingestion started.", a stale provider "Kept from the last good ingestion"; enum values in sentence case ("Up", "Half-open", "Unknown airport").
- A. These. B. Change any row or text. **Recommendation: A.** Tasks: 3–10.

## Decisions this plan takes

| Decision | Why | Source |
|---|---|---|
| The search runs on the URL as it stands, the empty one included; no `limit` is sent (the API's 20) | Every filter is optional; one page per "Load more" | (brief; plan 5 Q9) |
| A query never retries a 4xx; mutations never retry | TanStack retries 3 times by default, spending the rate limit (Review Focus 1) | (§6.6 per-IP limit) |
| The 503 shows §6.5's sentence, not §6.8's earlier label "Provider unavailable" | §6.5 was decided later, with exact copy, and plan 5 serves it as `detail` | (§6.5; plan 5) |
| nginx overwrites `X-Forwarded-For` with `$remote_addr`, resolves `app` through Docker's DNS at request time, and falls back to `index.html` | Appending would let a client pick its rate-limit counter through `RemoteIpValve`; a recreated `app` gets a new address; `/admin` and shared URLs must survive a reload | (§6.6; plan 5's client-IP decision; §6.8) |
| The image runs nginx as a non-root user on port 8080 | As `bootstrap/Dockerfile` does | (repository convention) |
| Keycloak stays on its own origin; the realm gains client `dashboard` (Q7) with redirect `http://localhost:3000/admin*` and web origin `http://localhost:3000` | Plan 5's issuer is `localhost:8180`; "the dashboard's client comes with plan 6" | (§6.6; plan 5 Task 11) |
| `/admin` starts the Keycloak login when there is no session | An admin page has nothing to show before it | (§6.8 "Keycloak login") |
| Sort tabs are a radio group; a card's summary is a disclosure button; opening it re-prices it | Native keyboard behaviour; "clicking a card expands its legs and re-prices it" | (§5.4; §6.8) |
| End to end: Chromium, Firefox and WebKit (E6, approved later; run in the Linux Playwright image), one worker, the real rate limits; projects ordered smoke → search → re-price → accessibility → admin → rate-limit | The admin run changes the generation and the rate-limit test spends the window, so both go last | (minimum for §6.11 step 5) |

## Decided by the user at the close of plan 6 (2026-11-16, about 08:58)

- The new copy written during Tasks 5–8 and the closing round is **approved as it is**: "Price confirmed: €…", "Price changed: now €…", "Never", "Provider status could not be loaded. Retrying every 10 seconds.", "The ingestion could not be started. Try again in a moment.", "Legs", "Alternative legs", "The API refused your session. Sign out and try again."
- **Accepted and documented as limitations** in `dashboard.md`: a failed sign-out is silent (code M4); with Keycloak down when a token must be renewed, the tab goes to the browser's error page (risk M-2).
- **Accepted as a recorded process lesson:** about five branches in Tasks 6–8, integrated without a per-task review by the speed rule, were written before their test and later shown covered by mutation (code M5).

## ⚠ Extras — your call

- **E1. A "+1" marker on an arrival the next local day.** Value: a 22:40 → 06:15 trip reads right at a glance. Cost: one helper, one test. Left out unless you approve.
- **E2. Airline names beside the codes** ("Iberia IB 3166"). Value: friendlier cards. Cost: a carrier catalogue in the API or the bundle, kept in step with the dataset. Left out unless you approve.
- **E3. Airport and airline suggestions in the filters.** Value: no IATA codes to know. Cost: an endpoint listing them, a combobox and its accessibility tests. Left out unless you approve.
- **E4. "Sign out" on the admin screen.** Value: ends the Keycloak session; the demo can show the login again. Cost: a button, the end-session call, a post-logout redirect in the realm, one test. Left out unless you approve.
- **E5. nginx hardening and caching:** CSP (allowing Keycloak's origin), `X-Content-Type-Options`, `frame-ancestors`, `Referrer-Policy`, gzip, immutable caching of hashed assets. Value: production headers. Cost: ~15 lines and one e2e check. Left out unless you approve.
- **E6. Firefox and WebKit in Playwright.** Value: cross-browser evidence. Cost: two browser installs, about three times the e2e time. Left out unless you approve.
- **E7. Accessibility checks in component tests** (`vitest-axe`, `eslint-plugin-jsx-a11y`). Value: a problem fails its own component's test before e2e. Cost: two dev dependencies, one assertion per component test. Left out unless you approve.
- **E8. A note when "Select" is pressed** ("Booking is not part of this demo."). Value: the button does not look broken. Cost: one line, one test. Left out unless you approve.
- **E9. A "Try again" button on the search failure.** Value: one-click recovery. Cost: a button, one test. Left out unless you approve.

**Where the approved extras are built (assigned 2026-11-16 by the controller under the night rules, rule 3: build what the user already approved; for the user to see):** no task listed them, as Task 4's review found.
- E7 (`vitest-axe`, `eslint-plugin-jsx-a11y`) and E9 ("Try again") in **Task 5**: the dev dependencies and the lint plugin there, one axe assertion in each component test from then on (and one added to Tasks 3 and 4's component tests); "Try again" on the search failure state.
- E1 ("+1" next-day arrival), E2 (below) and E8 (the note on "Select") in **Task 6**, which changes the card and owns the "Select" button.
- E4 ("Sign out") in **Task 7**, with the realm's post-logout redirect.
- E6 (Firefox and WebKit) in **Task 10**.
- E2 (airline names) in **Task 6**. **Decided by the user on 2026-11-16:** the controller finds the airline catalogue itself. The dataset's twelve carriers and their names are already in `mock-gds`'s `StandardCatalogue` (IB Iberia, UX Air Europa, BA British Airways, AF Air France, LH Lufthansa, KL KLM, TP TAP Air Portugal, AZ ITA Airways, LX Swiss, FR Ryanair, U2 easyJet, VY Vueling), and they match the airlines' IATA designators. The controller's choice of the two options E2 named: a static `src/itinerary/carriers.ts` in the bundle, no API change; an unknown code shows the code alone.
- E3 (suggestions) needs a catalogue endpoint from plan 5 that no task builds: **waiting for the user** (and the first to cut with E6 if the date is at risk).

**Reviews and gates for the remaining tasks (the user's decision of 2026-11-16, for speed):** Tasks 6, 7 and 8 are integrated without a per-task review, after `npm run verify` and the controller's `./gradlew build`; the end-of-plan triple review covers them. Tasks that touch only `frontend/` run `npm run verify` alone before each commit.

## Out of scope

Booking (§6.8: "Select" goes no further); `SOLUTION.md`, Grafana and the final reviews (plan 7); visual-regression tests; a frontend coverage gate (§6.11, not approved).

## Review Focus

1. **A 4xx retried by TanStack's default** would spend three more requests of a 429's limit and show its message seconds late. Expected: one request, the message at once. Pinned by Task 5 `'does not retry a rate-limited search'`.
2. **A browser in another time zone** (a reviewer in Madrid, a JFK departure at `08:15-05:00`) must see 08:15, not 14:15. Pinned by Task 4 `'shows the airport's local time whatever the browser's zone'`.
3. **A flagged "Load more" page repeating an itinerary already shown** must not render it twice (duplicate keys corrupt the list). Pinned by Task 5 `'appends only itineraries not already shown'`.
4. **A shared or hand-edited URL with bad values** (`?origin=MADR&maxPrice=-1&sort=best`) shows field errors and sends no request, never a blank page. Pinned by Task 3 `'shows invalid URL values as field errors'` and Task 5 `'does not search while a filter is invalid'`.
5. **An admin page open longer than the access token lives** (Keycloak's default is 5 min) while it polls must renew once or sign in again, never loop on 401s. Pinned by Task 7 `'renews the session once when an admin call answers 401'`.

## Task map

**Changed on 2026-11-15 (the user's decision):** plan 5 now commits `openapi.json` from its Task 9 (the search endpoint) instead of its last task, so the tasks marked "yes" below start once plan 5 Task 9 is on `main`. When plan 5 Tasks 10 (re-price) and 12 (admin) add their paths, the task that needs them runs `npm run api:generate` again, and `api:check` fails on a stale `schema.d.ts`.

| Task | Depends on | May run in parallel with | Needs plan 5's final contract |
|---|---|---|---|
| 1. Scaffold, gates, CI job | — | — | no |
| 2. Generated types, client, errors | 1 | 3, 9 | **yes** |
| 3. URL state, filter bar, sort tabs, tokens | 1 | 2, 9 | no (plan 5 Task 9's parameter names) |
| 4. Itinerary card | 2 | 3, 7, 9 | **yes** |
| 5. Results, "Load more", search states | 2, 3, 4 | 6, 8, 9 | **yes** |
| 6. Re-price | 2, 4 | 5, 8, 9 | **yes** |
| 7. Admin sign-in, realm client, settings | 2, 3 | 4, 9 | **yes** (admin paths) |
| 8. Provider status, "Run ingestion now" | 7 | 5, 6, 9 | **yes** |
| 9. nginx image, compose, Playwright harness | 1 | 2–8 | no (needs plan 5's stack running) |
| 10. End-to-end flows, axe, CI e2e job | 3–9 | — | yes |
| 11. Docs and the full-stack run | all | — | — |

`ci.yml` is edited by Tasks 1, 2, 9 and 10 in different jobs: a parallel task rebases on `main` before its review.

---

### Task 1: Scaffold `frontend/`, its gates and its CI job

**Files:**
- Create in `frontend/`: `package.json`, `package-lock.json`, `.npmrc` (`save-exact=true`, `engine-strict=true`), `.nvmrc`, `tsconfig.json`, `tsconfig.app.json`, `tsconfig.node.json`, `vite.config.ts`, `eslint.config.js`, `.prettierrc.json`, `.prettierignore`, `index.html`, `src/main.tsx`, `src/App.tsx`, `src/queryClient.ts`, `src/test/setup.ts`, `src/test/server.ts`
- Modify: `.gitignore` (`node_modules/`, `frontend/dist/`, `frontend/test-results/`, `frontend/playwright-report/`), `.github/workflows/ci.yml` (job `frontend`), `.github/dependabot.yml` (npm at `/frontend`, paused like the rest); per Q1-A: `settings.gradle.kts` (comment), `CLAUDE.md` ("Before every commit": `npm run verify` in `frontend/`), `docs/planning/design-spec.md` (§6 module row: npm project, outside Gradle)
- Test: `src/App.test.tsx`

**Interfaces:**
- Scripts: `dev`, `build` (`tsc -b && vite build`), `typecheck`, `lint`, `format`, `format:check`, `test` (`vitest run`, JUnit report to `test-results/`), `verify` (typecheck, lint, format:check, test). The Vite dev server proxies `/api` to `http://localhost:8080` (§6.8).
- `src/test/server.ts` exports MSW's `server`, started in `setup.ts` with `onUnhandledRequest: 'error'`. `createQueryClient(): QueryClient` in `queryClient.ts` (Task 5 sets its retry rule). `App` renders `<main>` with the h1 "Find flights" inside a `QueryClientProvider`.

- [ ] **Step 1: versions.** Read Node from `nodejs.org/dist/index.json` and every package of this plan (the stack above and Q2's) with `npm view <pkg> time --json`; take the newest version that meets the Global Constraints and accepts its peers (if the newest breaks a peer range, the newest that fits, with the reason). Record one table (package, version, publication date) in the progress report; later tasks install from it.
- [ ] **Step 2:** scaffold with the recorded `create-vite` (`react-ts` template), pin, set `strict: true`.
- [ ] **Step 3: `App.test.tsx` `'renders the search page heading'`:** red (the template's page), then green.
- [ ] **Step 4:** show each gate failing once (an unformatted file, an implicit `any`, a lint error), restore, record.
- [ ] **Step 5: CI job `frontend`:** `actions/setup-node` by §6.1 with `node-version-file: frontend/.nvmrc` and the npm cache, `npm ci`, `npm run verify`; `frontend/test-results/` uploaded on failure or cancel (§6.11 step 6).
- [ ] **Step 6: gates green, review, commit:** `git commit -m "Scaffold the dashboard with strict TypeScript, its checks and its CI job"`.

### Task 2: Generated API types, the client and API errors

**Started on plan 5 Task 9's contract (the controller's reading of the user's decision to move the contract to Task 9 so that plan 6 starts earlier; put to the user on 2026-11-15):** `types.ts` aliases only the schemas the committed contract has (the search's); `RepriceResponse`, `StatusReport` and `ProviderStatus` are added by Tasks 6 and 8, after regenerating `schema.d.ts` from the contract plan 5 Tasks 10 and 12 commit.

**Files:**
- Create: `frontend/src/api/schema.d.ts` (generated), `client.ts`, `apiError.ts`, `types.ts`, `frontend/src/test/fixtures.ts`
- Modify: `frontend/package.json` (`api:generate`, `api:check`), `ci.yml` (job `frontend`: step "Contract drift", §6.11 step 3)
- Test: `src/api/schema.test.ts`, `src/api/apiError.test.ts`

**Interfaces:**
- `api:generate` writes `schema.d.ts` from `../docs/architecture/openapi.json` with `openapi-typescript`, then Prettier; `api:check` runs `schema.test.ts` alone. The image never regenerates (Docker's context is `frontend/`).
- `types.ts`: `Itinerary`, `Leg`, `SearchResponse`, `RepriceResponse`, `StatusReport`, `ProviderStatus` as aliases of `components['schemas'][…]`, named as plan 5's contract names them.
- `client.ts`: `export const api = createClient<paths>({ baseUrl: '' })` (Task 7 adds `adminClient`).
- `class ApiError extends Error { readonly status: number; readonly detail?: string; readonly retryAfterSeconds?: number }`; `toApiError(response: Response): Promise<ApiError>` reads an RFC 9457 body and `Retry-After`; any other body (nginx's HTML 502) leaves `detail` empty.
- `fixtures.ts`: `direct(overrides?)` MAD→LHR IB 3166 and `connecting(overrides?)` MAD→LHR→JFK (IB + BA; offsets `+01:00`, `Z`, `-05:00`), typed from the schema.

- [ ] **Step 1: `schema.test.ts` `'committed types match the contract'`** (generates in memory with `openapi-typescript`'s Node API; the failure names `npm run api:generate`): red (no file), green after `npm run api:generate`; then red by mutation (one character changed in `schema.d.ts`), restored.
- [ ] **Step 2: `apiError.test.ts`, one test at a time:** `'reads the problem detail and Retry-After'` (429, `Retry-After: 17` → 17); `'leaves the detail empty for a body that is not a problem'` (502, `text/html`).
- [ ] **Step 3:** the "Contract drift" step runs `npm run api:check`; gates green, review, commit: `git commit -m "Generate the dashboard's API types from the committed contract and fail on drift"`.

### Task 3: The search in the URL, the filter bar and the sort tabs

**Files:**
- Create in `frontend/src/`: `search/searchParams.ts`, `search/FilterBar.tsx`, `search/SortTabs.tsx`, `search/SearchPage.tsx`, their `.module.css`; `styles/tokens.css`, `styles/global.css`
- Modify: `src/App.tsx` (router, route `/`), `src/main.tsx` (fonts, Q6)
- Test: `searchParams.test.ts`, `FilterBar.test.tsx`, `SortTabs.test.tsx`, `SearchPage.test.tsx`

**Interfaces:**
- `type SortOrder = 'price' | 'departure' | 'duration'`; `interface SearchCriteria { origin?: string; destination?: string; carrier?: string; maxPrice?: string; sort: SortOrder }`; `type FilterField = 'origin' | 'destination' | 'carrier' | 'maxPrice'`; `type FieldErrors = Partial<Record<FilterField, string>>`.
- `parseSearch(params: URLSearchParams): { criteria: SearchCriteria; errors: FieldErrors }`: trims, upper-cases codes and validates as plan 5 Task 9 does (airport `^[A-Z]{3}$`, carrier `^[A-Z0-9]{2}$`, `maxPrice` `^[0-9]{1,9}(\.[0-9]{1,2})?$` and above 0); an unknown `sort` is `price`; other parameters are ignored. `toSearchParams(criteria): URLSearchParams` omits empty values and `sort=price`.
- `FilterBar({ criteria, errors, onSubmit })`: a `<form>` with "From", "To", "Airline", "Max price" (§6.8; text inputs, `inputMode="decimal"` for the price) and "Search"; errors through `aria-invalid` and `aria-describedby`. `SortTabs({ value, onChange })`: a `<fieldset>` "Sort by" with radios "Price", "Departure", "Duration". `SearchPage` reads and writes the URL with `useSearchParams`; a search and a sort change each push a history entry.

- [ ] **Step 1: `searchParams.test.ts`, one test at a time:** `'round-trips every filter and the sort'`; `'normalises codes and amounts'` (`mad` → `MAD`, ` 150 ` → `150`); `'reports each invalid value as a field error'` (`MADR`, `IBX`, `-1`, `0`, `1e3`, `abc`; Q7 texts); `'falls back to price for an unknown sort'`.
- [ ] **Step 2: one test at a time:** `FilterBar.test.tsx` `'submits the four filters'`, `'labels every control'`; `SortTabs.test.tsx` `'changes the sort with the arrow keys'`.
- [ ] **Step 3: `SearchPage.test.tsx` (memory router), one test at a time:** `'writes a submitted search to the URL'`; `'restores the previous search on back'`; `'shows invalid URL values as field errors'` (Review Focus 4).
- [ ] **Step 4:** Q6's tokens and the top bar, the filters stacking under 640 px (checked by Task 10's phone test); gates green, review, commit: `git commit -m "Keep the search in the URL with the four filters and the sort tabs"`.

### Task 4: The itinerary card

**Files:**
- Create: `frontend/src/itinerary/format.ts`, `ItineraryCard.tsx`, `ItineraryCard.module.css`
- Test: `format.test.ts`, `ItineraryCard.test.tsx`

**Interfaces:**
- `format.ts`: `localTime(iso: string): string` ("08:15", read from the string's own clock); `localDate(iso: string): string` ("Fri 20 Nov", the string's date formatted in UTC); `duration(minutes: number): string` ("7h 25m", "45m"); `price(amount: string): string` ("€1,234.50"); `freshness(quotedAt: string, now: Date): string` (Q7); `layovers(legs: Leg[]): { airport: string; minutes: number }[]` (from instants); `isMultiAirline(legs: Leg[]): boolean`.
- `ItineraryCard({ itinerary, now }: { itinerary: Itinerary; now: Date })`: an `<article>` with `<time dateTime>` for departure and arrival, the route, the duration, "Direct" / "1 stop" / "2 stops" with each connecting airport and layover, each leg's carrier and flight number ("IB 3166"), "Multiple airlines" when it applies, the price and the freshness (§6.8).

- [ ] **Step 1: `format.test.ts`, one test at a time:** `'shows the airport's local time whatever the browser's zone'` (Review Focus 2: `process.env.TZ = 'Pacific/Auckland'`; `2026-11-20T08:15:00-05:00` → `08:15` and `Fri 20 Nov`; `…T09:20:00Z` → `09:20`); `'formats durations'`; `'formats euro prices from decimal strings'`; `'describes freshness'` (30 s, 4 min, 61 min); `'computes layovers across time zones'` (LHR in at `09:20Z`, out at `10:30+00:00` → 70); `'detects a multi-airline itinerary'`.
- [ ] **Step 2: `ItineraryCard.test.tsx`, one test at a time:** `'shows a direct flight'`; `'shows each stop with its airport and layover'` ("1 stop", "LHR", "1h 10m"); `'marks a multi-airline itinerary'`.
- [ ] **Step 3: gates green, review, commit:** `git commit -m "Show an itinerary card in the airports' local times with its stops and freshness"`.

### Task 5: Results, "Load more" and the search states

**Amended after Task 2's review (2026-11-15, the controller under the night rules; for the user to see):** modify `src/api/client.ts`: `api` is created with `baseUrl: window.location.origin` (same origin in the browser; parseable under jsdom, where Node's `Request` rejects a relative URL), replacing Task 2's `baseUrl: ''`; an `onResponse` middleware `apiErrors` throws `await toApiError(response)` for every non-2xx answer, so every query and mutation rejects with `ApiError` and the hooks read only `data`. `toApiError` must run inside the middleware, before `openapi-fetch` reads the body. The middleware is proven by `'shows the API's detail for a rejected filter'` (400) and `'shows a failure without a problem body'` (502 HTML).

**Files:**
- Create: `frontend/src/search/useItinerarySearch.ts`, `mergePages.ts`, `ResultsList.tsx`, `ResultsList.module.css`; `src/itinerary/useNow.ts`
- Modify: `src/search/SearchPage.tsx`, `src/queryClient.ts`
- Test: `mergePages.test.ts`, `SearchPage.test.tsx`, `useNow.test.ts`

**Interfaces:**
- `useItinerarySearch(criteria: SearchCriteria, enabled: boolean)`: `useInfiniteQuery` keyed `['itineraries', criteria]`, `initialPageParam: undefined`, `getNextPageParam: (last) => last.nextCursor ?? undefined`, `GET /api/itineraries` with the criteria and the cursor; disabled while any field error exists.
- `mergePages(pages: SearchResponse[]): { itineraries: Itinerary[]; inventoryChanged: boolean }`: pages in order, the first occurrence of each id kept (Q4-A), flagged if any page is.
- `createQueryClient()`: a query retries (TanStack's count) only on a network error or a status ≥ 500.
- `ResultsList`: "Showing N flights" in a polite live region, the cards (Task 4), "Load more" while a next cursor exists, the notice "Results updated." with "Reload" (resets the query to its first page); loading, empty, failure, 400 and 429 states with Q7's texts. `useNow(intervalMs: number): Date` ticks every 60 s (Q7).

- [ ] **Step 1: `mergePages.test.ts`, one test at a time:** `'appends only itineraries not already shown'` (Review Focus 3); `'flags a changed inventory from any page'`.
- [ ] **Step 2: `SearchPage.test.tsx` with MSW, one test at a time:** `'shows the loading state'` (`aria-busy`); `'shows the first page and how many flights are shown'` ("Showing 20 flights"); `'loads more until the last page'` ("Showing 40 flights", then no "Load more" once `nextCursor` is null); `'searches again when the sort changes'` (`sort=duration` in the request); `'shows the empty state'`; `'shows the API's detail for a rejected filter'` (400); `'shows a failure without a problem body'` (502 HTML); `'does not retry a rate-limited search'` (Review Focus 1: one request, "Try again in 30 seconds"); `'does not search while a filter is invalid'` (Review Focus 4: no request); `'offers a reload when the inventory changed'` (the second page flagged; "Reload" requests a page without a cursor).
- [ ] **Step 3: `useNow.test.ts` `'ticks every minute'`** (fake timers).
- [ ] **Step 4: gates green, review, commit:** `git commit -m "List the results with Load more, the inventory notice and every search state"`.

### Task 6: Re-price on selection

**Amended after Task 2's review (2026-11-15):** `useReprice()` gets its `ApiError` from `api`'s `apiErrors` middleware (Task 5). Plan 5 Task 10's re-price body writes `previousPrice` and `notice` only when they apply, so the generated type has them optional.

**Files:**
- Create: `frontend/src/itinerary/useReprice.ts`, `RepricePanel.tsx`, `RepricePanel.module.css`
- Modify: `src/itinerary/ItineraryCard.tsx`
- Test: `ItineraryCard.test.tsx`, `RepricePanel.test.tsx`

**Interfaces:**
- `useReprice()`: `useMutation<RepriceResponse, ApiError, string>` posting `/api/itineraries/{id}/reprice`.
- `ItineraryCard`: its summary becomes a `<button aria-expanded aria-controls>`; opening it shows each leg (flight, airports, local times) and re-prices once per opening (§6.8).
- `RepricePanel({ result }: { result: UseMutationResult<RepriceResponse, ApiError, string> })`, in a `role="status"` region: pending "Checking the price…"; then per Q5: `CONFIRMED`, `PRICE_CHANGED` (the card shows the new price), `ALTERNATIVE` (the response's `notice` and the alternative's legs and price), each with "Select", a button that goes no further (E8); 503 → §6.5's sentence; 404, 429 and any other failure → Q7's texts.

- [ ] **Step 1: one test at a time:** `'re-prices when a card is opened, once per opening'` (MSW counts 1, then 2 after closing and reopening); `'opens and closes a card with the keyboard'`.
- [ ] **Step 2: `RepricePanel.test.tsx`, one test at a time:** `'shows a confirmed price with Select'`; `'shows a changed price and updates the card'` ("Price changed: was €412.60, now €431.00"); `'shows the alternative inside the card with its notice'` (§6.5 text exact); `'shows the 503 message'` (§6.5 text exact); `'says when a flight is no longer available'` (404); `'shows the re-price limit with its seconds'` (429).
- [ ] **Step 3: gates green, review, commit:** `git commit -m "Re-price a card when it is opened and show each outcome"`.

### Task 7: Admin sign-in, the realm's dashboard client and the settings (as Q2-A, Q3-A)

**Decided by the user at the end-of-plan review (2026-11-16, about 08:07), risk I-1:** a renewed token that the API refuses again no longer redirects to Keycloak (which looped through SSO forever on a misconfigured issuer, realm or clock); the screen shows an error with Sign out. Only a failed renewal redirects. Built in plan 6's closing round.

**Amended after Task 2's review (2026-11-15):** `adminClient(auth)` uses the same `baseUrl` and the same `apiErrors` middleware. Its 401 renewal must see the 401 before `apiErrors` throws: `openapi-fetch` runs `onResponse` in reverse order of registration, so `apiErrors` is registered first and the auth middleware after it.

**Files:**
- Create: `frontend/src/config.ts`, `frontend/public/config.json` (the compose values, for `npm run dev`), `src/admin/authClient.ts`, `src/admin/AdminSession.tsx`, `src/admin/AdminPage.tsx`
- Modify: `src/App.tsx` (route `/admin`), `src/api/client.ts`, `deploy/keycloak/flight-aggregator-realm.json` (client `dashboard`)
- Test: `config.test.ts`, `AdminSession.test.tsx`, `src/api/client.test.ts`

**Interfaces:**
- `interface AppConfig { oidcAuthority: string; oidcClientId: string }`; `loadConfig(): Promise<AppConfig>` reads `/config.json`, only on the admin screen; a missing field throws naming it.
- `interface AuthClient { getUser(): Promise<User | null>; signinRedirect(): Promise<void>; signinRedirectCallback(): Promise<User>; signinSilent(): Promise<User | null> }` (the part of `oidc-client-ts`'s `UserManager` the app uses); `createAuthClient(config: AppConfig): AuthClient` with `redirect_uri` `${location.origin}/admin`, `response_type: 'code'` (PKCE `S256`), `scope: 'openid'`, `userStore` an `InMemoryWebStorage`.
- `AdminSession({ auth, children })`: completes `/admin?code=…&state=…` and replaces the URL with `/admin`; with no user, `signinRedirect()`; provides `useAdminToken(): () => Promise<string | null>` (removed in the closing round: since Task 8, `useAdminClient()` is the only caller's need).
- `adminClient(auth: AuthClient): Client<paths>`: attaches `Authorization: Bearer`; on a 401, one `signinSilent()` and one repeat; a second 401 or a failed renewal → `signinRedirect()`. `api` never sends a token.
- Realm client `dashboard` per Q7 and the decisions table: `publicClient: true`, `standardFlowEnabled: true`, `directAccessGrantsEnabled: false`, `pkce.code.challenge.method: S256`.

- [ ] **Step 1: `config.test.ts`, one test at a time:** `'loads the sign-in settings'`; `'names a missing setting'`.
- [ ] **Step 2: `AdminSession.test.tsx` (a fake `AuthClient`), one test at a time:** `'starts the sign-in when there is no session'`; `'completes the sign-in on the way back and cleans the URL'`; `'shows a sign-in failure'`.
- [ ] **Step 3: `client.test.ts`, one test at a time:** `'sends the token only to admin calls'`; `'renews the session once when an admin call answers 401'` (Review Focus 5: one renewal, two requests, success); `'signs in again when the renewal fails'`.
- [ ] **Step 4:** the realm client; `docker compose up keycloak --wait`, then Keycloak's authorization endpoint with `client_id=dashboard`, `code_challenge_method=S256` and the redirect shows the login page, and without the challenge refuses it. Record both; Task 10's admin test proves the whole flow.
- [ ] **Step 5: gates green, review, commit:** `git commit -m "Sign the admin in through Keycloak with PKCE and renew the session once on a 401"`.

### Task 8: The provider-status screen and "Run ingestion now"

**Amended after Task 7 (2026-11-16, the controller under the night rules; for the user to see):** `AdminSession`'s context also provides the admin client, `useAdminClient()`, built once from its `AuthClient` with Task 7's `adminClient(auth)`; `useInventoryStatus()` and `RunIngestionButton` use it (Task 7's `useAdminToken` alone cannot build it). Starts after plan 5 Task 12 is on `main`, so `schema.d.ts` has the admin paths; `types.ts` then gains `StatusReport` and `ProviderStatus`. The 409 is answered only while a run is really running (plan 5 Task 12, the user's decision of 2026-11-16).

**Files:**
- Create: `frontend/src/admin/useInventoryStatus.ts`, `StatusPage.tsx`, `ProviderTable.tsx`, `RunIngestionButton.tsx`, `StatusPage.module.css`, `src/admin/labels.ts`
- Modify: `src/admin/AdminPage.tsx`
- Test: `StatusPage.test.tsx`, `RunIngestionButton.test.tsx`, `labels.test.ts`

**Interfaces:**
- `useInventoryStatus()`: `useQuery` keyed `['admin', 'status']`, `GET /api/admin/status` through `adminClient`, `refetchInterval` per Q7, not in the background.
- `StatusPage`: h1 "Provider status"; the generation, "Published …" and "Next ingestion …" (Q7); a `<table>` with a caption and the column headers Provider, Health, Circuit breaker, Last successful ingestion, Legs, Discarded records (the total, then each reason with its count); a stale row says "Kept from the last good ingestion" (§6.8). `label(value: string): string` turns `UNKNOWN_AIRPORT` into "Unknown airport".
- `RunIngestionButton`: posts `/api/admin/ingestions`; 202 → "Ingestion started." and the status refetched; 409 → the API's `detail`; disabled while pending; 403 → Q7's text.

- [ ] **Step 1: `labels.test.ts` `'turns an enum value into sentence case'`.**
- [ ] **Step 2: `StatusPage.test.tsx`, one test at a time:** `'shows the generation, its publication and the next ingestion'`; `'shows one row per provider with its health and breaker'`; `'marks a provider kept from the last good ingestion'`; `'shows that no ingestion has run yet'`; `'refreshes the status every 10 seconds'` (fake timers); `'shows the missing admin role'` (403).
- [ ] **Step 3: `RunIngestionButton.test.tsx`, one test at a time:** `'starts an ingestion'`; `'says when an ingestion is already running'` (409).
- [ ] **Step 4: gates green, review, commit:** `git commit -m "Show each provider's status and run an ingestion on demand"`.

### Task 9: The nginx image, the compose service and the Playwright harness

**Files:**
- Create: `frontend/Dockerfile`, `frontend/.dockerignore`, `frontend/nginx/default.conf.template`, `frontend/playwright.config.ts`, `frontend/e2e/global-setup.ts`
- Modify: `compose.yaml` (service `frontend`), `ci.yml` (job `images`: `docker build frontend`), `dependabot.yml` (docker at `/frontend`), `frontend/package.json` (`e2e`)
- Test: `frontend/e2e/smoke.spec.ts`

**Interfaces:**
- `Dockerfile`: a build stage from the recorded `node` image (`npm ci`, `npm run build`; tests run in CI, as `bootstrap/Dockerfile`), then the recorded `nginx` stable image: the bundle in `/usr/share/nginx/html`, the template in `/etc/nginx/templates/`, non-root on 8080. Build context `frontend/`.
- Template: `resolver 127.0.0.11`; `location /api/` proxies to `${API_UPSTREAM}` through a variable with `X-Forwarded-For $remote_addr`; `location = /config.json` returns `{"oidcAuthority":"${OIDC_AUTHORITY}","oidcClientId":"${OIDC_CLIENT_ID}"}` (Q3-A); `location /` `try_files $uri /index.html`.
- Compose `frontend`: `127.0.0.1:3000:8080`, `API_UPSTREAM: http://app:8080`, `OIDC_AUTHORITY: http://localhost:8180/realms/flight-aggregator`, `OIDC_CLIENT_ID: dashboard`, depends on `app` healthy, health check `wget -q --spider http://localhost:8080/`.
- `playwright.config.ts`: `baseURL` `http://localhost:3000`, Chromium, one worker, no retries, the project order of the decisions table; `globalSetup` polls `/api/itineraries` every 5 s (so it spends little of the search limit), up to 180 s, until it returns an itinerary (the first ingestion).

- [ ] **Step 1:** pick the `node` and `nginx` tags by §6.1, pin tag and digest, record.
- [ ] **Step 2: `smoke.spec.ts` `'serves the dashboard and proxies the API'`** (h1 "Find flights"; a plain `GET /admin` answers the app's `index.html`; `/api/itineraries` answers JSON; `/config.json` names `dashboard`): red against the stack without `frontend`, green with the image and the service.
- [ ] **Step 3:** the `images` job builds `frontend`; gates green, review, commit: `git commit -m "Serve the dashboard from nginx in compose with the API behind the same origin"`.

### Task 10: End-to-end flows, axe and the CI end-to-end job

**Files:**
- Create in `frontend/e2e/`: `axe.ts`, `search.spec.ts`, `reprice.spec.ts`, `accessibility.spec.ts`, `admin.spec.ts`, `rate-limit.spec.ts`
- Modify: `.github/workflows/ci.yml` (job `e2e`)

**Interfaces:** `expectNoAxeViolations(page: Page): Promise<void>` with `AxeBuilder` and Q7's tags. The admin signs in with plan 5's demo user from the README. Assertions never name a route or price of the rolling dataset: they read `<time dateTime>`, prices and codes from the page.

- [ ] **Step 1: one test at a time, each shown red once (by mutation where the feature exists):** `search.spec.ts` `'shows the cheapest flights first and loads 20 more'` (40 cards, prices non-decreasing, "Showing 40 flights"); `'filters by origin and keeps the search in the URL'` (every card leaves MAD; reload and back keep it); `'sorts by departure and by duration'`; `'stacks the filters on a phone'` (390 × 844). `reprice.spec.ts` `'opens a card and shows a confirmed price, a new price or an alternative'`.
- [ ] **Step 2:** `accessibility.spec.ts` `'has no axe violations'` (results, an opened card with its outcome, the empty state with `?maxPrice=0.01`, a field error with `?origin=MADR`); `'searches, loads more and opens a card with the keyboard only'`.
- [ ] **Step 3:** `admin.spec.ts` `'signs in through Keycloak and shows each provider'` (three rows; axe on the page); `'runs an ingestion on demand'` (the generation grows within 90 s). `rate-limit.spec.ts` `'counts a client that forges X-Forwarded-For under its own address'` (a new forged address per search; a 429 within 121 searches, two fixed windows).
- [ ] **Step 4: CI job `e2e`** (§6.11 step 5, `timeout-minutes: 30`): `docker compose up --build -d --wait`, `npm ci`, `npx playwright install --with-deps chromium`, `npm run e2e`; on failure or cancel, upload `playwright-report/`, `test-results/` and `docker compose logs`; always `docker compose down -v`.
- [ ] **Step 5: gates green, review, commit:** `git commit -m "Prove the dashboard end to end with Playwright and axe in CI"`.

### Task 11: Docs and the full-stack run

**Files:**
- Create: `docs/architecture/dashboard.md`
- Modify: `README.md` (the dashboard at `http://localhost:3000`, the admin sign-in, the frontend commands; the index: this plan and `dashboard.md`), `docs/architecture/README.md`, `docs/planning/roadmap.md` (plan 6 done)

- [ ] **Step 1: run** (no production code; recorded in the progress report): `docker compose down -v`, `docker compose up --build -d --wait`, `npm run e2e`; screenshots of both screens at 1280 and 390 px, every designed state (§6.8) walked through, kept out of git and shown to the user against Q6.
- [ ] **Step 2: docs:** `dashboard.md` (screens and states, URL parameters, the re-price flow, sign-in and token handling, `/config.json`, nginx, how each layer is tested).
- [ ] **Step 3: gates green, review, commit:** `git commit -m "Document the dashboard and close plan 6"`; then the end-of-plan review by the three reviewers on the whole diff.
