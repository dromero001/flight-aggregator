# Extras backlog

Extras the implementers and reviewers proposed while plans 5 and 6 were built, and that are **out of the delivery** by the user's decision of 2026-11-16: "keep the extras that are not built, in case there is time later". None is approved. Each would need the user's approval before it is built; any left at the end is listed in `SOLUTION.md` as a next step.

Extras that a review turned into a finding are not here: they are fixed in the closing rounds of their plan.

## Plan 5, search API

| Extra | Proposed by | Value | Cost |
|---|---|---|---|
| Refuse unknown query parameters (a misspelt `orign=MAD` gives an unfiltered search today) | Task 9 | a client's typo is caught | one check, tests |
| Problem details for every framework error (`spring.mvc.problemdetails.enabled`) | Task 9 | one error format everywhere | a property, tests |
| An exact `ProblemDetail` schema (the contract lists a `properties` member Spring never writes) | Task 9 | a truthful contract | a springdoc customizer |
| Contract metadata: title and server (today springdoc's default and `http://localhost`) | Task 9 | a readable contract | a few lines |
| `currency` as the enum `["EUR"]` | Task 9 | a stricter contract | one annotation |
| A `oneOf` re-price contract with a discriminator on `outcome` | Task 10 | a true union type in the dashboard | a springdoc customizer, regenerated types |
| The rate limits as configuration properties instead of constants in `WebConfig` | Task 10 | tunable without a build | properties, validation |
| A startup check that every rate-limited action has a limit | Task 10 | fails at start, not on the first request | one check |
| A README sentence naming the rate limits | Task 10 | documented limits | one line |
| A `NOTHING_FRESH` refresh logged at WARN, as an empty run is | Task 7 | visible degraded refreshes | one line, one test |
| Each kept provider's failure reason in a refresh's report | Task 7 | easier diagnosis | report field, log |
| Refresh metrics (count by outcome, duration) | Task 7 | observability of refreshes | meters, tests |
| Reschedule the next run from the last run, manual or not | Task 12 | `nextIngestionAt` matches the scheduler | scheduler change |
| A health other than `UP` before the first run ends | Task 12 | honest health at start | indicator change |
| Valkey with `--save ""`, so the cache writes no snapshots (the image declares no volume) | Task 13 | no disk writes for a cache | one compose flag |
| A README line on Swagger UI's "Authorize" button | E2 | admin calls tried from the UI | one line |

## Plan 6, dashboard

| Extra | Proposed by | Value | Cost |
|---|---|---|---|
| `openapi-typescript --check` instead of `schema.test.ts` | Task 2 | one file and a tsconfig exception fewer | the generated file left unformatted |
| Focus the first new card after "Load more" | Task 5 | keyboard users carry on where new results start | a ref, one test |
| "+1" on each leg in the open card | Task 6 | next-day legs read right | helper reuse, one test |
| A caption telling the chosen legs from the alternative's | Task 6 | clearer alternative | copy, one test |
| Let `npm run dev` sign in (Vite on 3000 with `strictPort`, or the realm allowing 5173) | Task 7 | local sign-in without flags | config line |
| One shared renewal for concurrent 401s | Task 7 | one renewal instead of two | a shared promise, test |
| A "Loading…" line on the first status read | Task 8 | no blank screen | one state, one test |
| Stop polling after a 403 | Task 8 | no request every 10 s for a non-admin | query option, test |
| Hours in "Next ingestion in N min" for intervals over an hour | Task 8 | readable times | formatter, test |
| Cache Playwright's browsers in the CI `e2e` job | Task 10 | faster CI | a cache step |
| Choose the end-to-end browsers with an environment variable (`E2E_BROWSERS`), so `npm run e2e` works where a browser does not start (Firefox on macOS 27) | Task 10 | local runs on any Mac | config, docs |
| Keep screenshots and traces of failed end-to-end tests | Task 10 | easier diagnosis of CI failures | config, artefacts |
| E3, airport and airline suggestions in the filters (approved, but needs a catalogue endpoint no task builds) | plan 6 | no IATA codes to know | an endpoint, a combobox, accessibility tests |
