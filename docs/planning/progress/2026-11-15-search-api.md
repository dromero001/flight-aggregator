# Progress — plan 5 (search API), 2026-11-15

Plan 4's run still appends to [the day's main report](2026-11-15.md), so plan 5 keeps its own file.

## Task 1 — evidence

One test at a time; the `mock-gds` suite ran after each cycle. A test that passed on its first run is labelled and shown red by a mutation of the production code, which was then restored.

Design notes:

- `RecordFilter(origin, flight)` with `all()`, `fromOrigin`, `flight`, and `of(origin, carrier, flightNumber, destination, departure)`, which turns a request's parameters into a filter and is shared by the three controllers. `ProviderInventory.page(Provider, RecordFilter, offset, limit)` filters before paging; the existing callers pass `RecordFilter.all()`, so the listing is unchanged without an origin.
- A quote is the listing filtered to one flight, so it keeps each provider's own format and its paging ends by itself (Alpha `"nextCursor":null`, Beta `totalPages="1"`, Gamma a short page), and it is priced by the same drift model.
- An unknown flight is the listing's own empty answer in each format (HTTP 200: `{"legs":[],"nextCursor":null}`, `<Segments/>` with `totalPages="1"`, Gamma's header row only), not a 404. The brief says a quote is "the listing with the flight key present: at most one record", so no separate not-found was decided.
- A flight key with a missing part, a non-numeric `flightNumber`, or a `departure` that is not a UTC instant ending in `Z` throws `InvalidRequestException` (400 via `InvalidRequestAdvice`). `origin` alone is the origin filter.

Cycles:

- `ProviderInventoryTest.filtersByOriginBeforePaging`: red = `Expecting actual: [ValidLeg[... origin=BCN ...]] to contain exactly [MalformedLeg ...]` (the filter parameter existed but was ignored); green = `ProviderInventory` keeps only the records `RecordFilter.matches`.
- `findsOneFlightByItsKey`: red = the origin-less filter returned all ten records; green = `matches` also compares the leg key.
- `findsNothingForAFlightItDoesNotSell`: passed on first run; red under the mutation "match the flight by flight number only" (`Expecting empty but was: [PricedRecord[... flightNumber=3004 ...`); green with the key comparison.
- `AlphaControllerTest.servesOnlyTheOriginsLegs`: red = `Expected size: 483 but was: 600`; green = the `origin` parameter.
- `AlphaControllerTest.quotesOneFlightInItsFormat`: red = `Expected size: 1 but was: 212` (origin applied, flight ignored); green = `RecordFilter.of` with the five parameters.
- `AlphaControllerTest.quotesNothingInItsFormat`: passed on first run; red under the mutation "never match the flight key" (it and `quotesOneFlightInItsFormat` failed); green with the key match.
- `AlphaControllerTest.refusesAPartialFlightKey`: red = `expected 400 but was 200 OK`; `refusesADepartureThatIsNotAnInstant`: red = `[x] expected 400 but was 500`; green = the missing-part check and the strict `Z` instant parse (an offset such as `+01:00` is refused too, since `Instant.parse` accepts it).
- `BetaControllerTest.servesOnlyTheOriginsLegs`: red = `Expected size: 487 but was: 3616`; `quotesOneFlightInItsFormat`: red = `expected: 1 but was: 287`; green = the five parameters wired.
- `BetaControllerTest.quotesNothingInItsFormat`: passed on first run; red under the same flight-match mutation. `refusesAPartialFlightKey`: passed on first run; red under the mutation "a partial key returns the whole listing" (`expected 400 but was 200 OK`).
- `GammaControllerTest.servesOnlyTheOriginsLegs`: red = `Expected size: 484 but was: 600`; `quotesOneFlightInItsFormat`: red = `Expected size: 2 but was: 22`; green as for Beta.
- `GammaControllerTest.quotesNothingInItsFormat` and `refusesAPartialFlightKey`: passed on first run; red under the two mutations above (`Expecting actual: [header ...]`, `expected 400 but was 200 OK`).
- `EndpointAccessTest.guardsTheQuoteLikeTheListing`: passed on first run (the gate sits on the path); red under the mutation "the gate lets a request with a `carrier` parameter through" (all three endpoints `expected 401 but was 200 OK`).

Refactor: the quote query the three controller tests build moved to `Fixtures.quoteQuery`.

Gates: `./gradlew :mock-gds:build` and `./gradlew build` green (195 tests in `mock-gds`, 0 failed). The thermo-nuclear review of the diff found: the five `@RequestParam` lines repeat in the three controllers (the providers' signatures are separate endpoints, and the parsing is already shared in `RecordFilter.of`); the quote tests' query helper was duplicated and is now one fixture; nothing fails silently (every malformed parameter is a 400) and no state is shared between requests.

### Extras for the user to decide

None.

## Task 1 — fix round 1

Review: functional CHANGES REQUIRED, code APPROVED with follow-ups.

- Architecture doc: `docs/architecture/README.md`, "Mock providers" gained "Origin filter and quote" (the `origin` parameter, the five-parameter quote, the empty-page answer per format, the 400 rules, the `Z` of the request's `departure`).
- `AlphaControllerTest.refusesAnOriginThatIsNotAnIataCode`: red = `[origin=] expected: 400 BAD_REQUEST but was: 200 OK`; `refusesAFlightKeyWithMalformedParts` (lowercase destination and carrier, a three-letter carrier, a non-numeric `flightNumber`): red = `destination=lhr expected: 400 ...` (written together with the previous one, both red for the same missing validation; the `flightNumber=abc` case was already refused by `Paging.integer` and is only proven by that test being green as a whole). Green = `ListingParams.toFilter` validates the shapes: origin and destination `[A-Z]{3}`, carrier `[A-Z0-9]{2}`; a repeated `origin` reaches it joined with a comma and fails the shape.
- Beta and Gamma `refusesADepartureThatIsNotAnInstant` and `refusesAnOriginThatIsNotAnIataCode`: passed on first run (shared code); red under the mutations "the shape check disabled" (Alpha, Beta and Gamma origin tests fail) and "an unparsable departure answers `Instant.EPOCH`" (the three departure tests fail).
- Finding: `Instant.parse` already refuses an offset such as `+01:00`, so the explicit `endsWith("Z")` check added in the first round was redundant, and removing it kept every test green (it was dead code); it is gone.
- Request parsing moved out of `RecordFilter` (now the pure filter value: `all`, `fromOrigin`, `flight`, `matches`) into the record `serving/ListingParams`, bound by name with `@ModelAttribute` in the three controllers, with `toFilter()`; the five positional `@RequestParam` lines are gone. Behaviour unchanged, so this is a refactor under the green suite.
- Gates: `./gradlew :mock-gds:build` and `./gradlew build` green, 201 tests in `mock-gds`. Thermo-nuclear checklist on the diff: one canonical place for request parsing (next to `Paging`, reusing `Paging.integer` and `InvalidRequestException`), no repeated parameter lists, no silent failures (every malformed part is a 400), no shared mutable state.

## Task 3 — evidence

Persistence groundwork: one provider-time encoding, legs read back, ANALYZE before the switch, six sort indexes per slot.

- `LegsJsonTest.roundTripsLegsExactly` and `failsOnADocumentOfAnotherShape`: red with a stub `LegsJson` (both written before running, so both ran red together): `UnsupportedOperationException` for both (the second therefore failed for the wrong exception type, not yet for its shape). Green = `write` moved unchanged out of `ItineraryRow` (`ItineraryRowTest` untouched and green) and `read`, a strict streaming parse that expects the properties in the order `write` gives them and turns any other shape or a bad value into `IllegalStateException`. The second test was not shown red against a lenient reader; it pins the object-instead-of-array case only.
- `ProviderTimeTest.roundTripsAnInstantAndItsOffset` (four offsets, through a temporary table): red = `UnsupportedOperationException` from the stub. Green = `instantCell`, `offsetCell`, `read`. `JdbcOfferStore` and `ItineraryRow` then moved onto the helper with their tests unchanged and green (`JdbcOfferStoreTest`, `ItineraryRowTest`).
- `JdbcItineraryRepositoryTest.analyzesTheLoadedSlotBeforeTheSwitch`: red = `expected: 2.0f but was: -1.0f` (`reltuples` of `itinerary_b_20261125`). Green = `ANALYZE <slot>` after the default-partition count, before `switchTo`.
- `indexesEverySortOrderOnBothSlots`: red = `expected [six key lists] but was: []`. Green = `V5__itinerary_sort_indexes.sql`: per parent, `(price, id)`, `(departure, id)`, `(duration_seconds, id)` and the same three behind `(origin, destination, ...)`; the test also checks a day partition created by a run, which proves inheritance.
- Shared file touched: `FlightAggregatorApplicationTest.startsAgainstPostgresAndMigrates` pins the list of applied migrations; it gained version 5 (red in `./gradlew build`: `["5"] not expected`).
- Gates: `./gradlew build` green. Thermo-nuclear checklist on the diff: the time encoding and the legs JSON each have one canonical home now (no duplicate in the store, the row or the readers); `ANALYZE` runs in the load's transaction so a failure still rolls everything back; no silent failures (`LegsJson.read` throws, with the cause); table names still come only from `Slot`; no new store, so the single-transaction-manager constraint is unchanged.

### Extras for the user to decide

None.

## Task 3 — fix round 1

Review: functional CHANGES REQUESTED, code CHANGES REQUESTED (F1/C1 critical, F2, F3). F4, F5 and the code minors are deferred to the end-of-plan review.

- F1/C1: `LegsJsonTest.readsTheLegsAsPostgresReturnsThem` (write, `SELECT CAST(? AS jsonb)::text`, read): red = `IllegalStateException: ... expected property carrier but found price`. Green = `read` rewritten to collect each leg object into a name to value map (any order), with the nine names in one `PROPERTIES` set shared with `write`'s constants.
- F2, one test at a time, each red for its own reason against the order-independent reader, which started lenient:
  - `failsOnAMissingProperty`: red = `NullPointerException` (not `IllegalStateException`); green = the leg's names must contain the set.
  - `failsOnAnUnknownProperty`: red = `Expecting code to raise a throwable`; green = the check became equality with the set.
  - `failsOnADuplicateProperty`: red = no throwable; green = `put` returning a previous value fails.
  - `failsOnAValueOfTheWrongType` (flightNumber as a string): red = no throwable; green = the token must be a number for `flightNumber` and a string otherwise.
  - `failsOnContentAfterTheDocument` (`[...] []`): red = no throwable; green = the next token after the array must be the end.
  - `wrapsAValueThatDoesNotParseWithItsCause` (price `cheap`): red = raw `NumberFormatException`; green = `IllegalArgumentException | DateTimeException` wrapped with the cause.
  - The earlier `failsOnADocumentOfAnotherShape` (object instead of array) stays; the `START_ARRAY` check keeps it green.
- F3: `docs/architecture/ingestion.md`, "The slots": step 6 `ANALYZE`, `UPDATE` becomes 7, and a paragraph on V5's six indexes and their inheritance by every partition.

## Task 11 — evidence

OAuth2 for the admin side, and Keycloak in compose. Image: `quay.io/keycloak/keycloak:26.8.1`, the newest patch of the newest minor whose tag is at least 14 days old (pushed 2026-10-31, 15 days before today; `26.8.2` was first pushed on 2026-11-05 and moved again on 2026-11-12, so it is too young), pinned by the digest of its multi-arch index `sha256:3f3c5f8e03c614774b4bd9173b88391fefa4153bfc010d8012d59ad825377332` (`docker buildx imagetools inspect`; quay.io tag list). Spring Security, the resource server and `spring-security-test` come from the Boot 4.1.2 BOM.

- `KeycloakRolesTest.mapsRealmRolesToAuthorities`: red = `cannot find symbol class KeycloakRoles`; green = the converter. `givesNoAuthorityWithoutTheClaim`: passed on first run; red under the mutation "no claim gives ROLE_ADMIN" (1 of 2 failed); green with the empty list.
- `SecurityTest.searchAndRepriceNeedNoToken`: red = `Expecting actual: 401 not to be in: [401, 403]` (the starter locks every path); green = `SecurityConfig` (public matchers, `anyRequest().hasRole("ADMIN")`, JWT decoder, stateless, CSRF off). The paths do not exist yet (Tasks 9 and 10), so the test checks only that no token is asked for.
- `healthNeedsNoTokenAndShowsNoDetails`: passed on first run (Boot hides details by default); red under the mutation `show-details=always` (`Expected no value at JSON path "$.components" but found: {db=...`). `healthShowsItsDetailsToTheAdmin`: passed on first run once `when-authorized` + `roles: ADMIN` were set; red under `show-details: never`.
- `metricsAnswer401WithoutAToken`, `metricsAnswer403WithoutTheAdminRole`: passed on first run (metrics was not yet exposed, but the rule answers before the 404); red under the mutation `anyRequest().permitAll()` (`Status expected:<401> but was:<404>`, `<403> but was:<404>`).
- `metricsAnswer200WithTheAdminRealmRole`: red = `Status expected:<200> but was:<404>`; green = `include: health,metrics`. `FlightAggregatorApplicationTest` (the test of the exposure) now expects `health,metrics`.
- `rejectsATokenOfAnotherIssuer` and `acceptsASignedTokenOfTheIssuerWithTheAdminRealmRole` (real RS256 tokens, keys served by WireMock as Keycloak's JWKS): passed on first run; red under the mutation `JwtValidators.createDefault()` (no issuer check) for the first.
- `ComposeFileTest.runsTheKeycloakImageTheTestsRun`: red = NullPointerException (no `keycloak` service); green = the service, with the image of `KeycloakImage.NAME`.
- `RealmClientCredentialsTest.acceptsTheClientCredentialsTokenOfTheRealmsAdminClient` (real Keycloak in Testcontainers importing `deploy/keycloak/flight-aggregator-realm.json`, token by client credentials, `GET /actuator/metrics` through the application's decoder): passed on first run; red under the mutation "the service account has no realm role" (1 of 1 failed). The mutation first stayed green because Gradle considered the test up to date: the realm file was not a test input; it is now (`bootstrap/build.gradle.kts`, next to `compose.yaml`).
- Step 3, `docker compose up keycloak --wait`: healthy; `.well-known/openid-configuration` names `"issuer": "http://localhost:8180/realms/flight-aggregator"` (the public one, from `KC_HOSTNAME`; its `jwks_uri` is public too, the application uses the internal one). A client-credentials token has `iss` that issuer and `realm_access: {'roles': ['ADMIN']}`.
- Whole stack (`docker compose up --build --wait app keycloak`, then `down -v`): `GET /actuator/metrics` without a token 401, with `Bearer abc` 401, with the client-credentials token 200; `GET /actuator/health` without a token `{"groups":[...],"status":"UP"}`, with the token the components too. This is the README's "Admin access" section, run as written.

Realm file: realm `flight-aggregator`, realm role `ADMIN`, user `admin` (demo password, ADMIN), confidential client `admin-service` (service account with `ADMIN`; standard, implicit and direct-access flows off); its secret `${ADMIN_SERVICE_CLIENT_SECRET:demo-admin-service-secret}`, imported by Keycloak's placeholder substitution; compose passes the variable, so the committed default is overridable. The dashboard's public PKCE client is plan 6's.

Gates: `./gradlew build` green (see the commit). Thermo-nuclear checklist on the diff: one canonical mechanism used for each part (Boot's resource-server auto-configuration, `JwtAuthenticationConverter`, Actuator's `show-details`/`roles`, Keycloak's `--import-realm` and placeholder substitution), no new abstraction; nothing fails silently (a bad token is a 401, a missing role a 403; the decoder fetches the keys lazily, so see "Task 11 — fix round 1" for what a Keycloak that is down really answers); no shared state. One finding fixed: the realm file was not a Gradle test input.

### Extras for the user to decide

- Make `app` wait for Keycloak (`depends_on: keycloak: service_healthy`): not needed, the keys are fetched on the first admin request. Left out.
- Public health shows `groups` (`liveness`, `readiness`) besides `status`: Boot's own output, not removed.

## Task 11 — fix round 1

Review: functional CHANGES REQUESTED, code CHANGES REQUESTED (`task-11-review.md`). Fixed: F1/C1, C2 (the controller's ruling), C3, F5. The other minors wait for the end-of-plan review.

- **F1/C1** `PublicAccessOverHttpTest.anAnonymousSearchIsNeverAskedForAToken` (`RANDOM_PORT`, `TestRestTemplate`): red = `Expecting actual: 401 UNAUTHORIZED not to be equal to: 401 UNAUTHORIZED`; green = `dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()` (now 404). `anAnonymousAdminCallIsStillAskedForAToken`: passed on first run; red under the mutation `anyRequest().permitAll()` (1 of 2 failed).
- **C3** (written before the migration, as characterization tests of the hand-written decoder, so green on first run): `rejectsAnExpiredToken` (red under the mutation "issuer validator only": 1 failed), `rejectsATokenSignedWithAnotherKey` (same key id, other key; the signature check is Nimbus's, no mutation of ours reaches it), `answers403ToAValidTokenWithoutTheAdminRealmRole`. With `rejectsATokenOfAnotherIssuer` and `acceptsASignedToken...` they cover the configuration after C2.
- **C2** Boot's own settings replace the code: `spring.security.oauth2.resourceserver.jwt.jwk-set-uri` (internal URL; compose spells it `SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_JWKSETURI`), `issuer-uri` (public, validated on each token, no discovery), `authorities-claim-expressions: "[realm_access][roles]"` and `authority-prefix: ROLE_`. Deleted: the `jwtDecoder` bean, `security.jwt.*`, `KeycloakRoles` and `KeycloakRolesTest`. All security tests stayed green, `RealmClientCredentialsTest` against a real Keycloak too; the mutation `[realm_access][nothing]` fails `acceptsASignedTokenOfTheIssuerWithTheAdminRealmRole`. Boot's settings read the nested claim, so no converter is needed. The application starts with Keycloak down (`PublicAccessOverHttpTest` runs with nothing on port 8180).
- **What a Keycloak that is down answers** (stack from compose, app restarted with Keycloak stopped, valid token obtained before): admin call with a token 401 (keys not fetchable, cold app); with keys already cached 200; anonymous search 404 (public, unaffected); health 200. Corrects the first report's "401 for admin calls only", which was measured by the reviewer as a 500 turned into 401 by the error dispatch; after the fix the admin call still ends as 401.
- **F5** README "Admin access" rewritten: a readiness poll (step 1), the secret read from `ADMIN_SERVICE_CLIENT_SECRET` with the demo default (step 2, and a note to export a `.env` value), no `down -v` (what resets the realm: change the variable and `docker compose up -d keycloak`, which recreates the container), the console user `admin`, and the `/actuator/metrics` note tied to plan 5 Task 12. Run exactly as written against `docker compose up --build -d --wait`: poll returned, token 947 characters, `/actuator/metrics` with the token `HTTP/1.1 200`, without `HTTP/1.1 401` with `WWW-Authenticate: Bearer`, anonymous `/api/itineraries` 404 and `/v3/api-docs` 404 (not 401). With `ADMIN_SERVICE_CLIENT_SECRET=overridden-secret-123` exported and `docker compose up -d keycloak`: the same command gave 200.
- F6 corrected in passing: the `26.8.2` age above.

Gates: `./gradlew build` green. Thermo-nuclear checklist on the diff: net deletion (two classes, a bean, a namespace) in favour of the framework's own configuration; nothing fails silently; no state added. The plan's Task 11 text still names `KeycloakRoles` and `security.jwt.*`: left to the controller (the ruling supersedes it).

## Task 2 — evidence

Status: stopped at the check the brief asks for first, before any production change. Spring Framework 7.0.10 counts a method-level `@ConcurrencyLimit` per method, so the brief's seam (the annotation on the three operations, one limit shared by them) does not hold; per the brief, the task stops and asks before choosing another seam.

What the source says (`ConcurrencyLimitBeanPostProcessor`, spring-context 7.0.10): the interceptor keeps one holder per proxy instance; inside it, a method with its own annotation gets its own throttle (`perMethod = true`, named after the method), and only the methods covered by a class-level annotation share the holder's `classInterceptor`. The class-level pointcut matches every method of the class (`MethodMatcher.TRUE`), `toString()` included.

Learning test `bootstrap/.../config/ConcurrencyLimitScopeTest` (limit 1, `REJECT`, a call held on a latch, Boot's `AopAutoConfiguration` in the runner as in the application):

- `countsAMethodLevelLimitPerMethod`: first run red, but not for the question: `BeanCreationException: Could not generate CGLIB subclass of class ...AnnotatedMethods ... Cannot subclass final class`. Under Boot's default `spring.aop.proxy-target-class=true`, `AopConfigUtils.forceAutoProxyCreatorToUseClassProxying` sets Spring 7's default proxy config, which `AbstractBeanFactoryAwareAdvisingPostProcessor` applies to the resilience post-processors too, so a `final` class such as `PagedProvider` cannot be proxied in the application (`ProvidersConfigTest`'s runner, without `AopAutoConfiguration`, would not show it). The test class was made non-final. Then green on first run: while `hold()` is held, a second `hold()` is rejected and `other()` runs. Red under the mutation "one class-level annotation instead of the two method-level ones": `InvocationRejectedException: Concurrency limit reached: 1 - not allowed to enter ...AnnotatedMethods` at `operations.other()`.
- `sharesAClassLevelLimitAcrossTheMethodsOfOneInstance`: green on first run: with the annotation on the class, while `hold()` is held, `other()` and `toString()` are rejected, a method annotated `@ConcurrencyLimit(-1)` runs, and a second bean of the same class runs (one limit per proxied instance). Red under the mutation "drop the `-1` exemption": `InvocationRejectedException: Concurrency limit reached: 1 - not allowed to enter ...AnnotatedClass` at `first.exempt()`.

Open decision (pending, for the user): how the three operations share one limit per provider.

- A. Keep the seam, move the annotation: `@ConcurrencyLimit(limitString = "${providers.concurrency-limit}", policy = REJECT)` on the `PagedProvider` class, and `@ConcurrencyLimit(-1)` on `provider()` and on an overridden `toString()`, so neither is ever rejected (both pinned above). `PagedProvider` stops being `final`, or each `@Bean` carries `@Proxyable(INTERFACES)` (Spring 7.0), for the proxy to be built in the application. Cost: an opt-out to keep on every method added to the class later; anything new on it is limited by default.
- B. Keep the brief's method-level annotations, accepting one limit per operation (up to three times `concurrency-limit` calls per provider). Departs from "one limit per provider" (Q9).
- C. Another seam: a decorator in `bootstrap` (beside `CountedFlightProvider`) holding one `Semaphore` per provider, `tryAcquire` or a `RejectedExecutionException`, around `download`, `downloadFrom` and `quote` only. No proxy and no `final` question; the limit is ours to test instead of the framework's.

Gates: `./gradlew build` green. Thermo-nuclear review of the diff (a test file and this report, no production change): the held-call `try`/`finally` was copied in both tests and is now one helper, `whileHeld`; `Gate.hold` bounds its wait to 5 s only so that a broken test cannot hang the build, and the release always runs in `finally`. The learning test is not in the brief's file list: it is kept so that the chosen option can be checked against the framework's behaviour, and may be dropped with its commit.

### Extras for the user to decide

None.

## Task 2 — implementation (after the controller's ruling)

Ruling (controller, recorded in the plan 5 ledger): option A. `@ConcurrencyLimit(limitString = "${providers.concurrency-limit}", policy = REJECT)` on the `PagedProvider` class, `@ConcurrencyLimit(-1)` on `provider()` and `toString()`; `PagedProvider` stays `final` with `@Proxyable(INTERFACES)` on each provider `@Bean`, unless a test shows that the interface proxy does not apply the class-level limit as needed, in which case `final` goes; `ProvidersConfigTest` loads `AopAutoConfiguration`.

Step 1, page formats:

- Design: the three formats differ only in their paging, so `PageFormat` keeps one abstract method, `page(ListingQuery, position, pageSize)`, and gains the brief's `at(origin, position, pageSize)` and `quoting(flight)` as default methods over it. `ListingQuery` (new, package-private) is the query every listing shares (mock-gds, "Origin filter and quote"): nothing, `origin`, or the five key parts with `departure` as `Instant.toString()`. Each page record holds its query and exposes its format as `FORMAT`; `PagedProvider.formatOf` returns it. A quote is the first page of one record (`limit=1`, `pageSize=1`), so a second record fails the page's existing bound.
- `AlphaPageTest.requestsOnlyAnOriginsLegs`: red = `error: cannot find symbol` (`AlphaPage.FORMAT`); green = the origin passed to the format and carried in the request's query (`/alpha/legs?limit=100&cursor=b2Zm%2Bc2V0PTE%3D&origin=MAD`).
- `AlphaPageTest.buildsTheQuotePath`: red = `error: cannot find symbol` (`quoting`); green = `ListingQuery` and the default `quoting`.
- `BetaPageTest.requestsOnlyAnOriginsLegs`: red = `error: cannot find symbol` (`BetaPage.FORMAT`); green = `BetaPage` carries the query.
- `BetaPageTest.buildsTheQuotePath`: passed on first run (shared `quoting`); red under the mutation "Beta's path drops the query" (both Beta tests failed, the quote's with `/beta/segments?page=1&pageSize=1`).
- `GammaPageTest.requestsOnlyAnOriginsLegs`: red = `error: cannot find symbol` (`GammaPage.FORMAT`); green = `GammaPage` carries the query.
- `GammaPageTest.buildsTheQuotePath`: passed on first run; red under the mutation "the departure written with its offset": the three quote tests failed, `but was: ".../departure=2026-11-20T08%3A15%2B01%3A00"`.

Step 2, `PagedProvider` against WireMock:

- `AlphaProviderTest.downloadsOnlyTheOriginsLegs`: red = `error: cannot find symbol` (`downloadFrom`); green = `FlightProvider.downloadFrom(Airport)`, and `PagedProvider.download()` and `downloadFrom` both through one `downloadOf(origin)` over `PagedDownload.download`, so the origin download keeps the whole one's bounds. `FakeFlightProvider` fakes `download()` only and throws `UnsupportedOperationException` on the new calls.
- `AlphaProviderTest.quotesOneFlight`: red = `error: cannot find symbol` (`quote`); green = `FlightProvider.quote(LegKey)`, one request of `format.quoting(flight)` through `ProviderHttp.get`, `quotedAt` from `FetchedPage.receivedAt` (the clock's first arrival in the test).
- `AlphaProviderTest.quotesNothingForAFlightNoLongerSold`: passed on first run; red under the mutation "the quote takes the first record unconditionally" (`java.util.NoSuchElementException`).
- `AlphaProviderTest.failsAQuoteThatAnswersAnotherFlight`: red = `Expecting code to raise a throwable.`; green = a leg whose key is not the one asked for throws `PageMalformed`, which `quote` turns into `MALFORMED_PAGE` as `PagedDownload` does.
- `AlphaProviderTest.failsAQuoteWithAMalformedRecord`: red = `Expecting code to raise a throwable.` (the discarded record read as "not sold"); green = a discarded record fails the quote the same way. More than one record already fails the page's bound of one.
- Beta and Gamma, the same five tests each, written together because they drive no new code (the logic is `PagedProvider`'s, only the format differs): all passed on first run. Red under two mutations: "Beta's and Gamma's paths drop the query" (all ten failed) and "the quote's checks removed" (the two failure tests of each of the three providers failed).
- Seen once while running `:application:test` and `:adapters:providers:test` together: `ProviderHttpTest.failsFastWithAnOpenBreaker` failed with `expected: CIRCUIT_OPEN but was: HTTP_ERROR`. That code is untouched by this task, and it passed in four isolated runs (`--rerun`) and in the next combined run, so it looks like a timing flake under load. Noted for the reviewer; not changed.

Step 2, the limit (with `AopAutoConfiguration` in `ProvidersConfigTest`'s runner, as in the application):

- `ProvidersPropertiesTest.readsTheConcurrencyLimit` (bound with Boot's `Binder`): red = `error: cannot find symbol` (`concurrencyLimit()`); green = `ProvidersProperties` gains `@DefaultValue("4") int concurrencyLimit`.
- `ProvidersPropertiesTest.defaultsToFourConcurrentCallsPerProvider`: passed on first run (the default came with the field); red under the mutation "no `@DefaultValue`": `expected: 4 but was: 0`.
- `ProvidersConfigTest.limitsConcurrentCallsPerProvider` (limit 2; Alpha answers after 1 s; a download and an origin download in flight, seen as 2 requests in WireMock's journal; then a quote): red = `Expecting code to raise a throwable.` (nothing limited the quote); green = `@ConcurrencyLimit(limitString = "${providers.concurrency-limit}", policy = REJECT)` on the `PagedProvider` class, `@EnableResilientMethods` on `ProvidersConfig`, `spring-context` in `adapters/providers`. The quote is rejected with `InvocationRejectedException`, a Beta quote meanwhile answers, and Alpha's journal holds the 2 requests only.
- Found on the way: `@DefaultValue("4")` does not reach the annotation, whose placeholder reads the Environment, not `ProvidersProperties`. `buildsEachProviderFromItsOwnEndpoint` failed with `NumberFormatException: For input string: "${providers.concurrency-limit}"`, and every `FlightAggregatorApplicationTest` and `IngestionEndToEndTest` test with `Could not resolve placeholder 'providers.concurrency-limit'`. Green = `application.yaml` states `providers.concurrency-limit: 4` (Q9), with a comment saying why; the `ProvidersConfigTest` runner states it as the application does. `ProvidersProperties` keeps the field: with `ignoreUnknownFields = false` the property must be declared there.
- `ProvidersConfigTest.answersItsProviderWhileItsLimitIsExhausted` (limit 1, one download in flight): red = `InvocationRejectedException: Concurrency limit reached: 1 - not allowed to enter ...PagedProvider` at `provider()`. `@ConcurrencyLimit(-1)` (`UNBOUNDED_CONCURRENCY`) on `provider()` alone did not turn it green: same failure. Cause, from `ConcurrencyLimitBeanPostProcessor`: the interceptor looks the annotation up on `invocation.getMethod()`, which under the interface proxy of `@Proxyable(INTERFACES)` is `FlightProvider.provider()`, so the exemption on `PagedProvider.provider()` is never seen. Per the ruling's fallback, `PagedProvider` is no longer `final` and the beans lose `@Proxyable`: Boot's class proxy invokes the adapter's own methods. Green. Mutation "`final` again": the `ProvidersConfigTest` contexts fail with `Cannot subclass final class com.flightaggregator.adapter.provider.PagedProvider`, so the runner now catches what the learning test found.
- `ProvidersConfigTest.printsItselfWhileItsLimitIsExhausted`: red = `InvocationRejectedException ... PagedProvider` at `toString()` (the class limit covers it); green = `toString()` overridden (`PagedProvider[ALPHA]`) with `@ConcurrencyLimit(-1)`.

Step 3, the call counter:

- `CountedFlightProviderTest.countsEachOutcome` (OK, not sold, `TIMEOUT`, rejected): red = `error: cannot find symbol` (`CountedFlightProvider`); green = `CountedFlightProvider`, counting `provider.calls{provider, operation=download|origin-download|quote, outcome}` and passing the answer or the failure through. A rejection is caught as `RejectedExecutionException`, the JDK type `InvocationRejectedException` extends.
- `CountedFlightProviderTest.countsAnyOtherFailureAsUnexpected`: red = `MeterNotFoundException: ... No meter with name 'provider.calls'`; green = any other `RuntimeException` counted as `UNEXPECTED`, so every call is counted (global constraints, metrics).
- `ProvidersConfigTest.countsARejectedCall` (limit 1, one download in flight, a quote rejected): red = `MeterNotFoundException`; green = `CountedProviders`, a `BeanPostProcessor` declared `static` in `ProvidersConfig`, wrapping each `FlightProvider` bean. It is not `Ordered`, so it runs after the ordered resilience post-processor, around its proxy. Mutation "`CountedProviders` is `PriorityOrdered`": `countsARejectedCall` and `limitsConcurrentCallsPerProvider` failed with `Expecting code to raise a throwable.`, since a provider wrapped before the resilience post-processor is no longer proxied and loses its limit; the test pins the order.
- `printsItselfWhileItsLimitIsExhausted` then failed with `"...CountedFlightProvider@6b7ded28" to contain "ALPHA"`; green = `CountedFlightProvider.toString()` prints the provider it counts.

Docs: `docs/architecture/ingestion.md` gains "One origin, one flight, and the limit per provider" and the `provider.calls` row of the metrics table.

Gates: `./gradlew build` green. Thermo-nuclear review of the diff, and what it changed:

- The three page formats differed only in paging, so the query they share is one value (`ListingQuery`) and `PageFormat` one abstract method with `at` and `quoting` as defaults; no format repeats the five quote parameters.
- The quote's failures go through the same `PageMalformed` → `MALFORMED_PAGE` path as a download's page; "more than one record" needs no code of its own (the page of one record already refuses a second).
- Test duplication removed: the flight and the path expansion of the six provider tests are in `Fixtures`; the four limit tests share `limitedTo(limit)` and `inFlight(calls...)`.
- Javadoc made true again: `ProviderUnavailableException` and `FailureReason` now say "download or quote"; `ProvidersProperties` says that `concurrency-limit` is declared for the binder while the annotation reads the environment; `PagedProvider` says why it is not `final`.
- Nothing fails silently: every call is counted, an unexpected failure as `UNEXPECTED`, and the failure is rethrown unchanged. Corrected in fix round 1: a missing `providers.concurrency-limit` did not stop startup, as this line claimed; once `provider()` was exempt, the context started and every call failed at its first use. Fix round 1 makes startup stop. Also, `ProvidersConfigTest` now loads Boot's AOP defaults, so a `final` adapter or a lost limit fails it.
- Superseded by fix round 1 (the default is gone and `application.yaml` is the one source): `ProvidersProperties.concurrencyLimit` is bound but not read by production code (the brief's `@DefaultValue("4")`, needed by `ignoreUnknownFields = false`); the value 4 is stated twice, in the record and in `application.yaml`, and `defaultsToFourConcurrentCallsPerProvider` pins only the record's.

### Extras for the user to decide

- Built in fix round 1, by the controller's ruling on C1 (no longer an extra): refusing a `providers.concurrency-limit` below 1 at startup. Before it, 0 would fail every call (`IllegalStateException`, counted `UNEXPECTED`) and -1 would silently remove the limit.

## Task 2 — fix round 1

Review (`task-2-review.md`): functional CHANGES REQUESTED (F1), code CHANGES REQUESTED (C1). The controller's ruling: `application.yaml` is the one source of the limit; the dead `@DefaultValue("4")` goes, and a missing value or one below 1 stops startup, naming `providers.concurrency-limit`. The minors (F2, F3, F4, C2, C3) are deferred to the end-of-plan review.

- Refactor under green first: `ProvidersConfigTest`'s runner is split in two. `withoutLimit` has the endpoints and Boot's AOP defaults. `runner` adds the application's own `application.yaml` through `ConfigDataApplicationContextInitializer`, so the tests read the limit from where production reads it and no longer restate it. The suite stayed green.
- C1, `ProvidersConfigTest.refusesToStartWithoutAConcurrencyLimit`: red = `Expecting: <Started application [AnnotationConfigApplicationContext@3e47d465 ...]> to have failed`. That is the reviewer's Probe A: the context starts and every call fails at its first use. Green = `ProvidersProperties.concurrencyLimit` loses `@DefaultValue("4")`, and the compact constructor refuses a value below 1 with `providers.concurrency-limit must be set to 1 or more, not 0` (a missing setting binds as 0). `ProvidersPropertiesTest.defaultsToFourConcurrentCallsPerProvider` then failed with a `BindException`. It pinned the dead default, so it is deleted; `readsTheConcurrencyLimit` stays.
- C1, `ProvidersConfigTest.refusesAConcurrencyLimitBelowOne(0, -1)`: passed on first run, because the same check covers it. Red under the mutation "the check disabled", which failed it for 0 and for -1, and also failed `refusesToStartWithoutAConcurrencyLimit`.
- F1, `ProvidersConfigTest.limitsEachProviderToTheApplicationsFourCalls`. Set-up: the real `application.yaml`; Alpha answers after 1 s; four downloads are held, each on its own thread, until Alpha's journal holds 4 requests; then a fifth download. It passed on first run. Red under two mutations of `application.yaml`:
  - `concurrency-limit: 5`: `Expecting code to raise a throwable.` (the fifth call went through);
  - `concurrency-limit: 3`: `ConditionTimeoutException` (the fourth call was rejected and never reached Alpha).

  Restored to 4. `inFlight` now starts each call on its own virtual thread, because the common pool might run fewer than four at once on a small machine.
- The explanation now lives in one place, the `ProvidersProperties` Javadoc:
  - the yaml comment says only "Required: see ProvidersProperties";
  - `ingestion.md` says "set in `application.yaml`; startup stops without it";
  - the `ProvidersConfigTest` comment is gone, with the restated value;
  - the `PagedProvider` and `ProvidersConfig` Javadoc keep only what they own: the limit's behaviour, and the class proxy.
- Corrected in place: the Task 2 gates line that claimed a missing setting already stopped startup is marked as corrected, and the "left as they are" line as superseded. The extra "refuse a limit below 1" is marked as built by the ruling.

## User decisions (2026-11-15, after Tasks 2 and 11)

- **Keycloak's own administrator account is removed.** Task 11 had given Keycloak an administrator console account with a committed demo password; nothing in the app or the demo needs it (the realm is imported from `deploy/keycloak/flight-aggregator-realm.json`, and the admin token comes from the client-credentials client). Removed in a follow-up commit, with the README.
- **An admin call with Keycloak down and no cached keys keeps answering 500.** The proposed 503 is not built.
- **The learning test `ConcurrencyLimitScopeTest` stays,** as a guard: the per-provider limit relies on how Spring 7.0.10 counts `@ConcurrencyLimit` (per method, or per bean on the class).

## Task 4 — evidence

The itinerary reader: keyset pages over the active slot. One test at a time; the module's suite ran after each cycle. A test that passed on its first run is labelled and shown red by a mutation of the production code, which was then restored.

Design notes:

- Port types as the brief gives them: `SortOrder`, `SearchCriteria`, `SeekPosition` (with `of(Itinerary)`), `PageCursor`, `SearchPage` in `port/in`; `ItineraryReader`, `InventoryState`, `StoredPage` in `port/out`. `PageCursor` and `SearchPage` are plain records with no behaviour, so no test drives them; Tasks 5, 6, 8 and 9 compile against them.
- `KeysetQuery.of(slot, criteria, after, limit, now)` builds `SELECT legs FROM <slot> WHERE departure > ? [AND origin = ?] [AND destination = ?] [AND price <= ?] [AND single_carrier = ?] [AND (<key>, id) > (?, ?)] ORDER BY <key>, id LIMIT ?` from fixed fragments, each optional condition with its own values, the table from `Slot`, every value a parameter. `<key>` and its seek value come from one switch over `SortOrder`: `price` (the amount), `departure` (the instant at UTC), `duration_seconds` (seconds). A row becomes `new Itinerary(LegsJson.read(legs))`.
- Checked before fixing the SQL text (manual, not committed): on a scratch container of the pinned image with V3 and V5 and 200,000 rows, generic plans with JDBC's parameter types (`varchar` for a `String`, `bigint` for a `long`). PostgreSQL casts the `varchar` to `bpchar`, so `origin = ?` and `destination = ?` are index conditions of the route indexes; `(price, id) > (?, ?)`, `(departure, id) > (?, ?)` and `(duration_seconds, id) > (?, ?)` are index conditions of their indexes under a Merge Append. So the fragments need no casts.
- **One snapshot needs `REPEATABLE READ`.** The brief asks for a read-only `TransactionTemplate` "so the generation and the rows of a page are one snapshot". At PostgreSQL's default `READ COMMITTED` each statement takes its own snapshot, so a Q2-B refresh (delete from the active slot, `COPY`, generation + 1, one transaction) committing between the reader's two statements gives a page of the refresh's rows labelled with the generation before it. `readsTheGenerationAndItsRowsInOneSnapshot` shows it: red with a read-only transaction at the default isolation, green at `REPEATABLE READ`, whose snapshot is taken at the transaction's first statement. A read-only transaction at `REPEATABLE READ` never fails with a serialization error. All three reads (`state`, `page`, `byId`) use the one template.
- `byId` reads the active slot by id with no departure condition (Q4); an id only in the inactive slot, the previous generation, is not found.
- Two tests beyond the brief's list, each needed by a production line the brief requires: `JdbcItineraryReaderTest.readsTheGenerationAndItsRowsInOneSnapshot` (the snapshot) and `bootstrap/.../PersistenceConfigTest.declaresTheItineraryReader` (the bean). The latter is an `ApplicationContextRunner` test like `ProvidersConfigTest`, so `FlightAggregatorApplicationTest` (Task 11's) is untouched.
- Test support: the slot reset of `JdbcItineraryRepositoryTest` (truncate both slots, drop every day partition, generation 0) moved to `support/ItinerarySlots`, with the partition listing, because the reader's tests need the same reset (a partition left by another class, such as `itinerary_b_archive`, would fail their loads).

Cycles:

- `KeysetQueryTest.buildsTheQueryOfEachSortWithEveryFilter` (one case per sort order, every filter and a seek position): red = `expected: "SELECT legs FROM itinerary_b WHERE departure > ? AND origin = ? AND destination = ? AND price <= ? AND single_carrier = ? AND (price, id) > (?, ?) ORDER BY price, id LIMIT ?" but was: ""` (a stub returning an empty query), the same for `departure` and `duration_seconds`. Green = the fragments written unconditionally, since this test has every filter.
- `JdbcItineraryReaderTest.returnsNothingBeforeTheFirstPublication`: red = `expected: InventoryState[generation=0, publishedAt=Optional.empty] but was: null` (stub). Green = `state()` reads `generation` and `published_at`; `page()` returned that generation with no rows. Refactor: the reset moved to `ItinerarySlots`.
- `readsAPageInPriceOrder` (three trips, inserted out of price order, the cheapest departing last, limit 2): red = `but was: StoredPage[generation=1, itineraries=[]]`. Green, first step = `page()` reads the active slot and generation and runs the `KeysetQuery`; second red, on both tests = `NoSuchElementException: No value present` at `KeysetQuery.of` (no filters here). Green = each filter and the seek became optional fragments.
- `pagesThroughTiesWithoutRepeatsOrGaps` (25 at 105.00, pages of 10, the oracle `SELECT id FROM itinerary_b ORDER BY price, id`): passed on first run (`SeekPosition.of` was written as its first consumer). Mutation "seek on the key alone, without the id": `Expected size: 25 but was: 10`. Mutation "`(key, id) >= (?, ?)`": `Expected size: 25 but was: 27`.
- `ordersByDepartureAndByDuration` (four trips whose three orders all differ, pages of 2, so each key's seek value runs in PostgreSQL): passed on first run. Mutation "departure and duration sort on `price`": the departure assertion got the price order. Mutation "duration seek value in minutes": `AssertionError: still paging after 20 pages: 40 ids` at the duration assertion.
- `appliesEachFilterAndTheirCombination` (each trip but one fails exactly one filter; one at exactly 105.00; an interline MAD→CDG→LHR trip with an IB leg): passed on first run. Mutation "`price < ?`": the Max Price assertion `could not find` the three trips at 105.00. Mutation "Carrier on any leg (`legs @> jsonb_build_array(jsonb_build_object('carrier', ?))`)": the Carrier assertion found the interline trip `unexpected`. Mutation "destination compared with `origin`": the destination assertion got `[]`.
- `hidesDepartedItineraries` (at +01:00: departed at 09:00Z, departing exactly at now 10:00Z, later at 11:00Z): passed on first run. Mutation "`departure >= ?`": the trip departing at now was returned. Mutation "no departure condition": all three returned.
- `readsTheActiveSlotWithItsGeneration` (two runs: generation 2 in slot a, generation 1 still whole in slot b): passed on first run. Mutation "the page reads the other slot": `but was StoredPage[generation=2, ... flightNumber=1]`, generation 1's row. Mutation "`state()` ignores `published_at`": `expected: InventoryState[generation=2, publishedAt=Optional[2026-11-20T10:30:00Z]] but was: ...Optional.empty`.
- `readsTheGenerationAndItsRowsInOneSnapshot` (a second connection locks slot b; the page reads generation 1, then waits on slot b, awaited through `pg_stat_activity`; the connection deletes slot b's rows, bumps the generation and commits): red with two autocommit statements = `expected: StoredPage[generation=1, itineraries=[...]] but was: StoredPage[generation=1, itineraries=[]]`. Green, first step = a read-only `TransactionTemplate` at the default isolation: still red, the same message. Green = `ISOLATION_REPEATABLE_READ`. Stable over five reruns of the class. `setReadOnly(true)` is the brief's and no test can observe it (the reader never writes): removing it leaves the suite green.
- `findsAnItineraryByIdEvenOnceDeparted` (left at 09:00Z, read at 10:00Z): red = `Expecting actual not to be null` (stub). Green = `byId` reads the active slot by id in the same snapshot, with the row mapper shared with `page()`.
- `findsNothingForAnUnknownId` (an id never published, and one only in the previous generation's slot): passed on first run. Mutation "the id ignored": `Expecting an empty Optional but was containing value: ...flightNumber=2`. Mutation "`byId` reads the inactive slot": `... containing value: ...flightNumber=1`.
- `state()` then moved into the same template, as the brief says of all its reads: a single statement is one snapshot by itself, so no test can see the difference.
- `PersistenceConfigTest.declaresTheItineraryReader`: red = `[Bean of type <interface ...ItineraryReader> from <Started application [... beanDefinitionCount = 12]>] Expecting actual not to be null`. Green = the `itineraryReader(DataSourceTransactionManager, JsonMapper)` bean.

Refactors under the green suite, from the review of my own diff: `KeysetQuery` builds a list of optional conditions, each with its fragment and its values, instead of threading a `StringBuilder` and a parameter list through a helper, so a fragment and its values cannot drift apart; the reader reads the `inventory_publication` row with one query for its three uses; `JdbcItineraryRepositoryTest`'s pass-through `partitions` wrapper became a static import of `ItinerarySlots.partitions`; `PersistenceConfig`'s Javadoc no longer suggests the application test proves the reader's wiring.

Gates: `./gradlew build` green. Thermo-nuclear checklist on the diff: no file near 1,000 lines (`KeysetQuery` 85, `JdbcItineraryReader` 98, its test 314); no new branching in existing flows; table names only from `Slot`, every value a parameter; one row mapper and one read of the publication row; each read is one transaction, so nothing is half-read; failures surface (`LegsJson.read` and `Itinerary` throw, `optional()` throws on more than one row); E6 holds (the reader takes only the manager).

### Extras for the user to decide

- **Defensive copies in `StoredPage` and `SearchPage`.** The codebase's records copy their lists (`IngestionReport`, pinned by `copiesTheProviderReports`); the brief's two records have no constructor, and `StoredPage` holds `JdbcClient`'s mutable list. Value: an immutable page, as elsewhere. Cost: two compact constructors and two tests. Left out unless you approve.
- **A statement timeout on search reads.** The reader's statements have no bound of their own (the 5-minute `StatementTimeout` is for the stores' writes); a slow search holds `ACCESS SHARE` on its slot until it ends. Value: a stuck search fails instead of holding a connection. Cost: one `SET LOCAL` per read and a value to choose. Left out unless you approve.
- **A guard on `limit`.** The reader passes `limit` straight to `LIMIT ?`: 0 returns an empty page, a negative value fails in PostgreSQL. The page size is validated at the endpoint (Q9: 20, at most 100). Value: the port refuses a bad limit itself. Cost: one check and a test. Left out unless you approve.

### Keycloak's administrator account removed (user decision)

The user's decision of 2026-11-15 (see "User decisions (2026-11-15, after Tasks 2 and 11)"). Changed:
- `compose.yaml`: `KC_BOOTSTRAP_ADMIN_USERNAME` and `KC_BOOTSTRAP_ADMIN_PASSWORD` removed from `keycloak` (they had no comment of their own). `KEYCLOAK_ADMIN_PASSWORD` and `demo-keycloak-admin-password` no longer appear anywhere.
- `README.md`, "Admin access": the console sentence is replaced by "Keycloak has no administrator console account"; the client-credentials steps are untouched. The realm reset (change `ADMIN_SERVICE_CLIENT_SECRET`, `docker compose up -d keycloak`) still holds: the realm is imported from the file each time the container is created, with no account involved.
- Plan 5, Task 11, Keycloak line: says there is no administrator account.
- The F5 entry above (the console user `admin`) is history and stays as written.

Test first: `ComposeFileTest.givesKeycloakNoAdministratorAccount` (no `KC_BOOTSTRAP_ADMIN*` key in the `keycloak` environment). Red against the old `compose.yaml`: the assertion failed because both keys were present. Green after removing them.

Check: `docker compose -p kcadmin up -d keycloak`; the README's readiness poll returned; the README's token command, as written, gave a token of 947 characters (value not recorded); `docker compose -p kcadmin down`.

Review follow-up: `compose.yaml`'s header comment no longer names Keycloak's own administrator (it now names the client secret and the realm's admin user). The test also rejects Keycloak's older `KEYCLOAK_ADMIN*` pair; red by mutation (a `KEYCLOAK_ADMIN: admin` key added to the `keycloak` environment: `givesKeycloakNoAdministratorAccount` failed), restored, green.

## Task 8 — evidence

Re-price on selection, with the alternative flow. One test at a time; the module's suite ran after each cycle. A test that passed on its first run is labelled and shown red by a mutation of the production code, which was then restored.

Design notes:

- Port types as the brief gives them: `RepriceItinerary`, the sealed `RepriceResult` (`Confirmed`, `PriceChanged` with `previousPrice`, `Alternative`, `Unconfirmable`, `NotFound`) and `RepriceSettings(maxAlternatives, budget)` in `port/in`; `OfferStore.offersOf(LegKey)` and `ItineraryRepository.updateInPlace(Itinerary) → boolean` on the outbound ports; `Itinerary.isSellable(now)` in the domain, delegating to `ConnectionRules.isSellable` for the first leg (R5).
- A leg its provider does not confirm is one of the brief's three cases: no longer sold (an empty quote), the provider fails (`ProviderUnavailableException`), or it rejects the call (`RejectedExecutionException`, which the concurrency limit's `InvocationRejectedException` is). The stored offers of the flight say which other providers sell it and in which order, the cheapest first; each is then quoted live in turn until one sells it. A leg is never sold from the store, since §6.5 never sells at an unconfirmed price. A failure of another kind propagates (decision 2 below).
- The legs of a trip are quoted at once on virtual threads, as `IngestInventoryService` downloads its providers. The executor is closed, so every quote has ended, before the results are read: a quote in flight is bounded only by its provider's own timeouts. The budget is checked against the `Clock` port before each quote, so no quote starts after it.
- Alternatives come from one `ItineraryReader.page` of the choice's origin and destination by price. It asks for `maxAlternatives + 1` rows, so that leaving the choice out still leaves `maxAlternatives`, and it hides departed trips. They are confirmed in turn (a lazy stream); the first confirmed is updated in place and returned.
- `updateInPlace` (Q5, with N1 of Task 4's review) is one transaction of the store's own `TransactionTemplate` (E6):
  - `SET LOCAL lock_timeout` with the load's 5 s, then the active slot read with the load's own `activeSlotLocked()` (`FOR UPDATE` on the publication row);
  - then the row is deleted by `(id, departure)` and copied back through the load's own `copy(slot, writer)` and `ItineraryRow`, so every column derived from the legs follows the re-quote: price, `quoted_at`, legs, arrival and its offset, duration, departure offset, stops and single carrier;
  - id, departure and generation are unchanged; no row deleted means false, and nothing is copied. There is one mapping, and no hand-picked column list.
- A refactor under the green suite, from Task 4's review C3 (one owner for an instant bound to a `timestamptz` parameter, once Task 7 or 8 adds a use). `ProviderTime.instantParameter(Instant)` is now used by `offersOf`, `updateInPlace` and `KeysetQuery`, whose private `utc` is gone. `JdbcItineraryRepository.switchTo` still converts inline: Task 7 edits that method, so folding it in is left to Task 7.
- `RepriceConfig` declares the use case with Q9's values (3 alternatives, 10 s) as a constant, as `PersistenceConfig` keeps its partition horizon; `application.yaml` is untouched.
- Tests beyond the brief's list, each needed by a production line the brief requires:
  - `JdbcOfferStoreTest.indexesTheOffersByFlight` (V6);
  - in `JdbcItineraryRepositoryTest`: `answersFalseForAnItineraryTheActiveGenerationNoLongerHolds` (the brief's "false when the row is gone"), `keepsAPageByDurationWholeWhenARequoteMovesTheArrival` (N1), and `givesUpAnUpdateWhileARunHoldsThePublication` and `writesIntoTheSlotARunSwitchedToWhileItWaited` (the lock);
  - `RepriceItineraryServiceTest.propagatesAProviderFailureOfAnotherKind` (the narrow catch);
  - `bootstrap/.../RepriceConfigTest.declaresTheRepriceUseCase` (the bean).
- `confirmsALegWithAnotherProvidersOfferOfTheSameFlight` is parameterised over the brief's three triggers. `FlightAggregatorApplicationTest.startsAgainstPostgresAndMigrates` now expects V6.

Cycles:

- `ItineraryTest.isSellableUntilItsFirstLegDeparts` (one second before the first leg departs, at it, and one second before the second leg departs): red with a stub `return false` = `Expecting value to be true but was false`. Green = `ConnectionRules.isSellable(legs.getFirst(), now)`. Mutation "the last leg": `Expecting value to be false but was true`.
- `RepriceItineraryServiceTest.answersNotFoundForAnUnknownId`: red with a stub returning `null` = `expected: NotFound[] but was: null`. Green = return `NotFound` (the minimum; the next test forces the lookup).
- `confirmsAnUnchangedPrice` (Alpha and Beta sell their legs again at 100.00, quoted later): red = `expected: Confirmed[itinerary=...quotedAt=2026-11-20T08:00:01Z...] but was: NotFound[]`. Green = `byId`, each leg quoted from its own provider (a map by `provider()`), `new Itinerary(quoted)`, `updateInPlace`, `Confirmed`. `ItineraryRepository.updateInPlace` was added to the port here, with a stub in `JdbcItineraryRepository` until Step 3; `IngestInventoryServiceTest`'s lambda repository became an anonymous class.
- `reportsAChangedPriceAndStoresIt` (Alpha now at 120.00: 210.00 -> 231.00): red = `expected: PriceChanged[...] but was: Confirmed[...]`. Green = `Money.compareTo` against the stored price.
- `confirmsALegWithAnotherProvidersOfferOfTheSameFlight`, parameterised over the three ways the brief says Beta does not confirm its leg (`UNAVAILABLE`: a `ProviderUnavailableException`; `REJECTED`: a `RejectedExecutionException`, which `InvocationRejectedException` is; `NO_LONGER_SOLD`: an empty quote). Stored offers of the flight: Beta's own at 100.00 (the cheapest), Gamma's at 105.00, Alpha's at 110.00; now Gamma sells it at 95.00 and Alpha at 90.00. Red = the failure escaped: `ProviderUnavailableException: BETA unavailable: TIMEOUT`, `RejectedExecutionException: over Beta's limit`, `NoSuchElementException: No value present`. Green = `OfferStore.offersOf(LegKey)` on the port (with the in-memory fake, cheapest first, and a stub in `JdbcOfferStore` until Step 3); a leg is its own provider's quote, `or` the first quote that sells it from the providers of the stored offers, in their order, the leg's provider skipped; a `ProviderUnavailableException` or `RejectedExecutionException` is no quote. Expected: Gamma's 95.00 (the cheaper stored offer is asked first, and asking stops there), and the quotes asked are exactly Alpha's leg, Beta's leg once, Gamma's. Mutation "the leg's own provider not skipped": `Expecting actual: [... BETA ..., BETA ..., GAMMA ...] to contain exactly in any order` in all three cases.
- `treatsRequotedLegsThatNoLongerConnectAsUnconfirmed` (Alpha's re-quote lands 30 min before the next leg departs, under the 45 min connection): red = `IllegalArgumentException: Leg IB2 LHR-BCN 2026-11-25T11:15:00Z cannot follow IB1 MAD-LHR 2026-11-25T07:15:00Z` escaped. Green = `confirm(itinerary)` returns no itinerary when `new Itinerary(quoted)` throws; the choice is then `Unconfirmable`, nothing stored.
- `offersTheNextCheapestConfirmableItinerary` (Beta down, no other provider stored its leg; Madrid-Barcelona trips at 157.50 no longer sold, 189.00 sold by Gamma, 199.50 sold; cheaper trips Madrid-London and London-Barcelona, both sold): red = `NoSuchElementException: No value present` (the unsold leg escaped). Green, first step = a leg no provider confirms makes the itinerary unconfirmable; second red = `expected: Alternative[...] but was: Unconfirmable[]`. Green = `ItineraryReader.page` of the choice's origin and destination by price, `maxAlternatives + 1` rows, the choice left out, at most `maxAlternatives`, confirmed in turn until one is (a lazy stream), updated in place, `Alternative`. The dearer trip is never quoted. Mutation "no origin in the page's criteria": `but was: Alternative[...flightNumber=20, origin=LHR...]`. Mutation "no destination": `but was: Alternative[...flightNumber=1, ... destination=LHR...]`.
- `aDepartedChoiceGoesToTheAlternative` (Q4; the choice left at 07:00Z, now is 08:00Z, and Alpha still lists it): red = `expected: Alternative[...flightNumber=11...] but was: Confirmed[...flightNumber=30, ... departure=2026-11-20T07:00Z...]`. Green = `confirm` refuses an itinerary that is not `isSellable(now)` before quoting it; `now` is read once, at the start, for the check and the alternatives' page.
- `answersUnconfirmableWithAllProvidersDown` (Review Focus 4; every provider down; five Madrid-Barcelona trips, the choice the cheapest; every flight stored by every provider): passed on its first run, since the cycles above built it. Mutation "the choice not left out of the alternatives": the quotes asked hold `IB40` (the choice) twice over and no fourth trip. Mutation "a page of `maxAlternatives` rows, without the extra one": `Expected size: 12 but was: 9`. Mutation "store the choice when nothing confirms": `Expecting empty but was: [Itinerary[...flightNumber=40...]]` (and `treatsRequotedLegsThatNoLongerConnectAsUnconfirmed` failed the same way).
- `startsNoQuoteAfterItsBudget` (each quote takes 300 ms and fails; budget 500 ms; four two-leg trips Madrid-London-Barcelona; the test's clock runs in real time from `NOW`): red = `Expecting actual: [IB50 ..., IB52 ..., IB54 ..., IB56 ...] to contain exactly in any order: [IB50, IB51, IB52, IB53]` (no budget, and the legs quoted one after the other, so every trip's first leg was asked and no second leg). Green, first step = `quote` starts no call once `clock.now()` is not before the start plus the budget; second red = `Expecting actual: [IB50 ..., IB52 ...]` (the legs were still quoted in turn, so only first legs fit in the budget). Green = a trip's legs re-quoted at once, each on a virtual thread (`Executors.newVirtualThreadPerTaskExecutor()`, closed before the results are read, as `IngestInventoryService` downloads its providers); a failure a re-quote did not handle propagates as it was thrown. Stable over five reruns of the class (0.63 s: two rounds of 300 ms). Mutation "no budget check": all eight legs of the four trips were asked.
- `JdbcOfferStoreTest.findsEveryProvidersOfferOfAFlight` (IB3170 Madrid-London stored by the three providers at 120.00, 100.00 and 110.00, Beta's at the same instant written at UTC; the same number a day later, and the same number to Paris, stored too): red with the stub returning an empty list = `Expecting actual: [] to contain exactly (and in same order): [Leg[...100.00...], Leg[...110.00...], Leg[...120.00...]]`. Green = `SELECT provider, <the offer's columns> FROM provider_offer WHERE carrier = ? AND flight_number = ? AND origin = ? AND destination = ? AND departure = ? ORDER BY price`, mapped by the existing row mapper; the column list is shared with `offers(provider)`. Mutation "no destination condition": the Paris offer came back too.
- `JdbcOfferStoreTest.indexesTheOffersByFlight` (beyond the brief's list: V6 is production code, and Task 3 tested its indexes the same way): red = `Expecting actual: [] to contain exactly (and in same order): ["carrier, flight_number, origin, departure"]`. Green = `V6__provider_offer_flight_index.sql`.
- `JdbcItineraryRepositoryTest.updatesAnItineraryInPlaceWithoutChangingTheGeneration` (the itinerary in both slots, generation 2 active in slot a; Beta's re-quote at 120.00): red with a stub returning `false` = `Expecting value to be true but was false`. Green, the minimum = the brief's original subset: in a transaction of the store's `TransactionTemplate`, the active slot read with `activeSlotLocked()`, then `UPDATE <slot> SET price, quoted_at, legs WHERE id = ? AND departure = ?`. The test reads the row back through `JdbcItineraryReader.byId` (the legs), and `price` and `quoted_at` from SQL; the generation and its `published_at`, the other row and slot b's copy are unchanged.
- `answersFalseForAnItineraryTheActiveGenerationNoLongerHolds` (the itinerary only in the previous generation's slot): red = `Expecting value to be false but was true`. Green = true only when one row was updated.
- `keepsAPageByDurationWholeWhenARequoteMovesTheArrival` (N1, Task 4's review: trips of 2 h, 2.5 h and 3.5 h; the 2 h one re-quoted to land an hour later; read back one row per keyset page by duration): red = `Expecting actual: [4d67b88a-..., f76dd975-...] to contain exactly (and in same order): [be02f7c3-..., 4d67b88a-..., f76dd975-...]`, the 2.5 h trip skipped, as N1 predicted: the row kept `duration_seconds` 7200 and sorted first, while the seek position from its legs was 3 h. Green = the row is rewritten through `ItineraryRow`, the load's mapping: deleted by `(id, departure)` and copied back with the load's own `copy(slot, writer)`, in the same transaction; no row deleted means false and nothing copied.
- `givesUpAnUpdateWhileARunHoldsThePublication` (a second connection holds `inventory_publication` `FOR UPDATE`, as a run does for its whole load): red = `AssertionFailedError: execution timed out after 15000 ms` (the update waited for ever). Green = `SET LOCAL lock_timeout` with the load's `LOCK_TIMEOUT` (5 s) at the start of the update's transaction: it fails with `55P03` after 5.2 s and slot b keeps its price.
- `writesIntoTheSlotARunSwitchedToWhileItWaited` (generation 2 active in slot a; a second connection holds the publication row; the update starts and is seen blocked by that backend through `pg_blocking_pids`, not through any SQL text; the connection switches to slot b and commits): passed on its first run, since the update reads the slot with the load's `activeSlotLocked()`. Mutation "the update reads the active slot without the lock": `AssertionError: nothing waited for backend 568` (it wrote into slot a, the one switched from, at once), and `givesUpAnUpdateWhileARunHoldsThePublication` failed too (`Expecting actual not to be null`: nothing waited, nothing failed). Mutation "copy back even when nothing was deleted": `answersFalseForAnItineraryTheActiveGenerationNoLongerHolds` failed with `Expecting value to be false but was true`.
- `RepriceItineraryServiceTest.propagatesAProviderFailureOfAnotherKind` (Beta throws an `IllegalStateException`, which its port does not name): passed on its first run, since the catch was written narrow in `confirmsALegWithAnotherProvidersOfferOfTheSameFlight`. Mutation "catch every `RuntimeException` as no quote": `Expecting code to raise a throwable`. Mutation "wrap what escapes a leg's thread": `Expecting actual: java.lang.IllegalStateException: java.lang.IllegalStateException: a bug in Beta's adapter` to be the same instance.
- `bootstrap/.../RepriceConfigTest.declaresTheRepriceUseCase` (an `ApplicationContextRunner` over `PersistenceConfig` and `RepriceConfig`, a data source never connected to, one provider, a clock): red with an empty `RepriceConfig` = `[Bean of type <interface ...RepriceItinerary> from <Started application [...]>] Expecting actual not to be null`. Green = the `repriceItinerary` bean.
- The first `./gradlew build` failed one test: `FlightAggregatorApplicationTest.startsAgainstPostgresAndMigrates` pins the applied migrations, `... but some elements were not expected: [("6", SUCCESS)]`. It now expects V6, as Task 3 added V5 to it.

Decisions taken here, for the reviewer and the user:

1. **`updateInPlace` answers false.** This happens when a run replaced the inventory after the choice was read and the new one no longer holds it. The service then keeps its answer, since the price was confirmed live, and nothing is written. The brief says what the port returns, not what the service does with it.
2. **A provider failure its port does not name propagates.** Anything other than `ProviderUnavailableException` or a rejection is a bug in the adapter, so it is not swallowed without its cause (the application layer has no log). `CountedFlightProvider` still counts it as `UNEXPECTED`.
   - Ingestion, by contrast, treats any `RuntimeException` as the provider's failure, and keeps the cause in its report.
   - If the user prefers §6.5's "always try to give the customer a result" here too, it is a one-line change to the catch, and to its test.

Open concern (not changed: the brief and the controller fixed the lock design):

- **During every full run, each re-price's update times out, and the re-price fails.**
  - A run takes the publication row `FOR UPDATE` at the start of its load (`activeSlotLocked()` is the load's first statement after the timeouts) and holds it until it commits. §6.4 says rebuilding the inventory takes minutes.
  - Meanwhile each re-price's `updateInPlace` waits 5 s and fails with a lock timeout (`55P03`). The service propagates it, so the re-price fails although its price was confirmed live; Task 10 would answer 500 unless it decides otherwise.
  - Task 7's refresh will hold the same row for its delete and `COPY`. All re-prices also wait for each other on that row, each for a one-row delete and copy.
  - Options for the user:
    - keep it;
    - let the service answer anyway when the update times out: the stored price stays the run's, and the failure must then be logged or counted;
    - take the publication row only for the switch, which is a change to the load.

Gates: `./gradlew build` green (exit status 0). Every module's tests ran and passed: domain 91, application 58, persistence 63, providers 197, bootstrap 86, architecture 39, mock-gds 201. Coverage gate: lines 98.4 %, branches 95.7 %. `startsNoQuoteAfterItsBudget` was stable over five reruns. Its margin is 200 ms: the first alternative's quotes must start within 200 ms of the choice's ending.

Thermo-nuclear checklist on the diff:

- No file near 1,000 lines: `RepriceItineraryService` about 200, its test about 400, `JdbcItineraryRepositoryTest` about 600.
- No new branch in an existing flow: `load` is untouched, and `KeysetQuery` lost a helper.
- The update reuses the load's mapping, `COPY` and lock, instead of adding a column list or a second read of the publication row. Its delete and copy are one transaction, so nothing is half-applied.
- Failures surface: the lock timeout, a store's failure, and a provider failure the port does not name all propagate as thrown.
- One line is repeated on purpose: `SET LOCAL lock_timeout` in `load` and in the update, kept inside each method so Task 7's edits to `load` merge without touching the update.

### Extras for the user to decide

- **Validate `RepriceSettings`.** Refuse a negative `maxAlternatives` or a non-positive budget at construction, as `JdbcItineraryRepository` refuses a negative horizon. Value: a misconfiguration fails at startup instead of with a PostgreSQL error on the first re-price. Cost: a compact constructor and a test. Left out unless you approve.
- **Stop trying alternatives once the budget has run out.** Today the remaining alternatives start no quote, but each leg still reads its stored offers: at most 3 trips of up to 3 legs, each read an index lookup. Value: no pointless reads. Cost: a `takeWhile` and a test. Left out unless you approve.
- **Bound the update's statements with `StatementTimeout`,** as the load and the offer replacement bound theirs. Value: a hung statement fails the update instead of holding the publication row. Cost: one line and a test. Left out unless you approve. **Built in fix round 1, as the review's C1, by the controller's ruling.**
- **Make Q9's re-price values properties** (`reprice.max-alternatives`, `reprice.budget`) instead of constants. Value: they can be tuned without a build. Cost: a properties record, a change to `application.yaml` (shared with Task 5), and a test. Left out unless you approve.
- **A fixed order for equally cheap offers in `offersOf`** (`ORDER BY price, provider`). Value: providers at the same stored price are asked in the same order every time. Cost: one clause, which no test can pin reliably. Left out unless you approve.

## Task 8 — fix round 1

Review (`task-8-review.md`): functional CHANGES REQUESTED (F1, F2), code APPROVED with five minors. This round fixes F1, F2 and C1; F3 and C2–C5 are deferred to the end-of-plan review, and F4 is covered by F1's tests.

- **User decision (2026-11-15), F1:** when the store cannot happen because an ingestion run holds the publication row, re-price still answers with the live result; the store is skipped, logged at `WARN` and counted.
- **Controller's ruling on how:** `updateInPlace` takes the publication row `FOR SHARE NOWAIT` instead of waiting for `FOR UPDATE`, so re-prices share it and a run's lock refuses it at once. The port answers a `StoreOutcome` (`STORED`, `GONE`, `BUSY`) instead of a boolean, and the service answers the live result in each case.
- **F2:** Q9's values (3 alternatives, 10 s) are pinned as the 37-day horizon is, from what the production wiring does.
- **C1:** the update's statements are bounded by `StatementTimeout`, so `application.yaml`'s note on store transactions is true again.

The round was resumed from an implementer cut off mid-round. Its uncommitted work was kept: the `StoreOutcome` type, the outcome assertions, and the tests `answersBusyAtOnceWhileARunHoldsThePublication`, `keepsTheSlotAnUpdateReadActiveUntilItCommits` and `storesTwoRepricesAtOnce`. Its red → green evidence was lost, so the reds of those tests below are **recorded after the fact by mutation**. As found, its update read the publication row with no lock at all.

Design notes:

- `JdbcItineraryRepository.rewrite` runs in this order:
  1. the load's two bounds: `lock_timeout` 5 s and, new, `StatementTimeout` (5 min);
  2. `SELECT active_slot FROM inventory_publication FOR SHARE NOWAIT`;
  3. the delete and the copy, as before: no row deleted answers `GONE`, else `STORED`.
- **A refused lock** (SQLState `55P03`, `lock_not_available`) answers `BUSY`. Nothing has been written; the refused statement aborted the transaction, so its commit is a rollback.
- **Any other failure to read the row propagates.** Spring leaves `55P03` uncategorized here (`UncategorizedSQLException`, as `IngestionJobTest` already records for the lock timeout), not the `CannotAcquireLockException` the review assumed. So the state is read from `getMostSpecificCause()`.
- **The guarantee "an update that waits for a switch writes the slot the run switched to" is replaced.** An update never waits for a run any more, so `writesIntoTheSlotARunSwitchedToWhileItWaited` no longer applies. The new lock implies this instead, pinned by `keepsTheSlotAnUpdateReadActiveUntilItCommits`: a run that starts during an update waits for the update's share before it touches either slot. The update's write therefore lands in the slot that stays active until the update commits.
- **The `WARN` and the counter** live in `bootstrap/observability/CountedItineraryRepository`, a decorator of the port. `RepriceConfig` wraps it around the repository it gives the service.
  - Why there: the application module depends only on the domain, with no logger and no meter registry, and the codebase already counts an outbound port's outcomes in a bootstrap decorator (`CountedFlightProvider`).
  - The meter is `reprice.store{outcome=STORED|GONE|BUSY}`, tagged with the enum's names as `provider.calls` and `ingestion.runs` are.
  - The `WARN` line names the itinerary id and no price.
  - The service's code is unchanged: it already answered whatever the update returned. Only its Javadoc changed, and a test now pins that behaviour (F4).
- **F2:** `RepriceConfigTest` drops the never-connected `PersistenceConfig`. It now drives the wired use case over a route, Alpha and a clock that the test holds, and reads Q9's values from what the use case does.
- `docs/architecture/ingestion.md`, step 2, now says how re-price updates share the publication row (the review asked for this whatever F1's fix).

Cycles. Each ran `./gradlew :adapters:persistence:test --tests '*JdbcItineraryRepositoryTest'`, or the service's or bootstrap's test class; the module's suite ran after each green. Every mutation was restored.

- **As found** (recorded after the fact by mutation: no lock on the publication row):
  - `answersBusyAtOnceWhileARunHoldsThePublication`: red = `expected: BUSY but was: STORED`;
  - `keepsTheSlotAnUpdateReadActiveUntilItCommits`: red = `AssertionError: nothing waited for backend 477` (the run did not wait for the update);
  - `storesTwoRepricesAtOnce`: green.
- **Green, first attempt:** `FOR SHARE NOWAIT`, catching `CannotAcquireLockException`. The busy test failed: `UncategorizedSQLException: ... SQL state [55P03] ... ERROR: could not obtain lock on row in relation "inventory_publication"`.
- **Green:** the catch reads SQLState `55P03` from the most specific cause, and rethrows anything else. All 22 tests passed; the busy update answered in 0.21 s.
- **Mutations on the tests found in the worktree** (recorded after the fact by mutation):
  - M1, "the update waits for `FOR UPDATE`, as before the round" (`activeSlotLocked()`): `answersBusyAtOnce…` and `storesTwoRepricesAtOnce` each fail with `execution timed out after 2000 ms`. This is the brief's "today it waits 5 s and throws": the update no longer answers at once.
  - M2, "an exclusive lock that never waits" (`FOR UPDATE NOWAIT`, the analogue of the `FOR UPDATE SKIP LOCKED` the previous implementer was about to try): `storesTwoRepricesAtOnce` fails with `expected: STORED but was: BUSY`.
  - M3, "no row deleted answers stored": `answersGoneForAnItineraryTheActiveGenerationNoLongerHolds` fails with `expected: GONE but was: STORED`.
  - M4, "a rewritten row answers gone": `updatesAnItineraryInPlaceWithoutChangingTheGeneration`, `keepsTheSlot…` and `storesTwoReprices…` each fail with `expected: STORED but was: GONE`.
  - M5, "no `status.setRollbackOnly()` after the refused lock": every test stayed green. PostgreSQL rolls the aborted transaction back at commit, and pgjdbc 42.7.14 does not throw. So the line, which no test required, was removed, and a comment says what happens.
- **C1, `boundsEachStatementOfAnUpdate`:** red = `expected: "5min" but was: "0"`. The setting is read inside a transaction the update joins, as `boundsEachStatementOfTheLoad` reads the load's. Green = `StatementTimeout.boundTheTransaction(jdbc)` in `rewrite`.
- **`boundsEachLockWaitOfAnUpdate`:** the `lock_timeout` line lost its test when `givesUpAnUpdateWhileARunHoldsThePublication` was replaced, and this test pins it again. Past the publication row it still bounds a wait, for a row another re-price of the same itinerary is rewriting, for example. It passed on first run. M6, "no `lock_timeout`": `expected: "5s" but was: "0"`.
- **`failsAnUpdateThatCannotReadThePublication`:** the publication row is deleted inside a transaction the update joins, and the update must fail with `IncorrectResultSizeDataAccessException`, not answer busy. It passed on first run. M7, "any failure to read the row answers busy": `Expecting code to raise a throwable.`
  - The first M7 run also exposed a flaw in the test: when the update answered, the test's transaction committed its delete. That removed the publication row for every later test in that JVM (24 failures, all `EmptyResultDataAccessException`); the container lives for one JVM, so nothing persisted.
  - The test now marks its transaction rollback-only first. Re-run, M7 fails this test alone.
- **Service, `RepriceItineraryServiceTest.answersTheConfirmedPriceWhateverBecameOfItsStore(STORED, GONE, BUSY)`** (F1, F4; `FakeItineraryRepository.answers(outcome)` added): passed on first run, since the service already ignored the answer. M8, "a re-quote not stored answers unconfirmable": `[2] GONE` and `[3] BUSY` fail with `expected: Confirmed[...] but was: Unconfirmable[]`.
- **`CountedItineraryRepositoryTest.countsEachUpdateUnderItsOutcome`:** red, with a stub that only passed the outcome through = `MeterNotFoundException: ... No meter with name 'reprice.store' was found`. Green = the counter. The counting body had been typed before the test ran, so it was swapped for that stub to see the red, then put back.
- **`logsAnUpdateSkippedWhileARunHoldsTheInventory`:** red = `Expecting actual: [] to contain exactly (and in same order): [(WARN, "Skipped storing a re-price: an ingestion run holds the inventory", "itinerary=6581ded3-...")]`. Green = one `LOG.atWarn()` with the `itinerary` key, for `BUSY` only.
- **`passesALoadThroughUncounted`:** red, with a `not yet` stub = `UnsupportedOperationException: not yet`. Green = forward the call.
- **F2, `RepriceConfigTest.triesTheChoiceAndThreeAlternatives`** (six trips of one route; Alpha down; no other provider's offers): passed on first run. Red under two mutations of `RepriceConfig`:
  - M9, `RepriceSettings(4, …)`: `... but some elements were not expected: [IB5 MAD-BCN ...]`;
  - M10, `RepriceSettings(2, …)`: `... but could not find the following elements: [IB4 MAD-BCN ...]`.
- **F2, `startsNoQuoteTenSecondsAfterTheStart(PT9.999S, 2 trips; PT10S, 1 trip)`:** the choice's quote moves the clock there and fails, and the route's one other trip is quoted after it, or not. It passed on first run. Red under two mutations:
  - M11, `Duration.ofSeconds(11)`: case `[2] PT10S` fails with `... some elements were not expected: [IB2 MAD-BCN ...]`;
  - M12, `Duration.ofSeconds(9)`: case `[1] PT9.999S` fails with `... could not find the following elements: [IB2 MAD-BCN ...]`.
- **`countsWhatBecameOfEachStore`** (Alpha sells, the repository answers `BUSY`, and the answer is `Confirmed`): red = `MeterNotFoundException: ... No meter with name 'reprice.store' was found`. Green = `RepriceConfig` wraps the repository in `CountedItineraryRepository`, with the `MeterRegistry`.
- **Refactor under green**, from the thermo-nuclear self-review, in `JdbcItineraryRepositoryTest`:
  - five tests built their own manager and repository; they now share the test's `transactions`, `repository` and an `enclosing` `TransactionTemplate` (an update inside it joins it);
  - the two copies of "hold an update open after its write" became one `HeldUpdate`;
  - the two bound tests became `boundsTheTransactionOfAnUpdate(statement_timeout 5min; lock_timeout 5s)`;
  - `requoted(itinerary, euros)` replaces the repeated four-argument call in this round's tests.

  Still 25 tests, all green. The as-found "no lock" mutation, M1, M2, M6, M7 and C1's removal were run again on the refactored tests; each still fails the same test with the same message.

Gates:

- `./gradlew build --rerun-tasks`: green (exit status 0, every task executed).
- Every module's tests ran and passed, 749 in all, 0 failed: domain 91, application 61, persistence 67, providers 197, bootstrap 93, architecture 39, mock-gds 201.
- Coverage gate: lines 98.6 %, branches 95.7 %.
- The Testcontainers tests connected on every run, so none needed a re-run (another agent's Valkey containers ran meanwhile).

Thermo-nuclear checklist on the diff:

- **No file near 1,000 lines.** `JdbcItineraryRepositoryTest` has 730, `RepriceItineraryServiceTest` 419, `RepriceConfigTest` 265, `JdbcItineraryRepository` 225.
- **Existing mechanisms reused:** `StatementTimeout` for the bound; a `Counted…` decorator in `bootstrap/observability`, as for providers; Logback capture, as in `LoggedDiscardedRecordsTest`; the test-runner style of `SchedulingConfigTest` and `ProvidersConfigTest`.
- **No new branch in an existing flow.** The busy branch is the update's own. The `WARN` branch lives in the decorator, and the service's code is unchanged.
- **Nothing fails silently:**
  - `BUSY` is counted and logged, and `GONE` is counted;
  - only `55P03` counts as busy, and any other failure to read the row propagates (pinned by M7);
  - the busy transaction rolls back and writes nothing (pinned by the busy test's stored price).
- **Atomicity is unchanged:** the delete and the copy remain one transaction.

Concerns, for the reviewer:

- **Two re-prices of the same itinerary at the same moment.** Under the shared lock, the second's `DELETE` waits for the first's row lock (milliseconds), then finds no row. It answers `GONE` and is counted so, while the store holds the first's re-quote. `StoreOutcome.GONE` documents this; no test pins it. Before the round, the two serialised on the publication row.
- **A run's `FOR UPDATE` waits behind every share in progress, and PostgreSQL does not queue new `FOR SHARE` lockers behind a waiting `FOR UPDATE`.** So an unbroken stream of overlapping re-prices could hold a run past its 5 s `lock_timeout`. Each share lasts one indexed delete and a one-row `COPY` (milliseconds), and re-price is limited to 10 per minute per IP (Q9), so this needs hundreds of re-prices per second. Not tested.
- **Task 7 must forward `replaceThrough` in `CountedItineraryRepository`** once it adds that method to the port; the compiler will force it. Task 7's refresh takes the publication row `FOR UPDATE`, so a re-price during a refresh also answers `BUSY` at once. Its `WARN` line then says "an ingestion run", which a refresh is.

### Extras for the user to decide

- **Pin the same-itinerary race** described in the first concern above with a test. Value: the `GONE` answer of the second re-price is then pinned, not only documented. Cost: one persistence test that holds the first update open with `HeldUpdate`. Left out unless you approve.

## Task 5 — evidence

Valkey for the result cache, the refresh coordination and the request counters, all failing open. One test at a time; the module's suite ran after each cycle. A test that passed on its first run is labelled and shown red by a mutation of the production code, which was then restored.

Versions (§6.1), checked on 2026-11-15:

- **Valkey image: `valkey/valkey:9.1.3-trixie@sha256:3add1a336a920ea54770a001ab6feb3da5fceea96a40373a3a2cd469b815f0b7`**, in `testFixtures/.../support/ValkeyImage.java`. Valkey's GitHub releases: 9.1.3 published 2026-10-26 (20 days old), the newest GA; 9.2.0 is at `rc2` (2026-11-04) and does not count. Docker Hub: `9.1.3-trixie` (and `9.1.3`) pushed 2026-10-27. The registry (`registry-1.docker.io/v2/valkey/valkey/manifests/9.1.3-trixie`) answers an OCI image index, `docker-content-digest` as pinned, for linux/amd64, arm64, arm/v7 and ppc64le. Docker Hub also lists a floating `9.2` tag pushed 2026-09-21 with no 9.2.0 release behind it; not used. The `-trixie` suffix matches `PostgresImage`.
- **`com.redis:testcontainers-redis` 2.2.4**, the Boot 4.1.2 BOM's `testcontainers-redis-module.version`, as the global constraints say for this library. Maven Central's `maven-metadata.xml` lists up to 3.0.1 (2026-10-28); not taken, since the BOM manages it.
- From the Boot 4.1.2 BOM, no version in the catalog: Spring Data Redis 4.1.2 and `spring-boot-starter-data-redis`, Lettuce 7.5.2.RELEASE, Micrometer 1.17.2, Logback 1.5.38 (tests). Catalog entries added: `spring-data-redis`, `spring-boot-starter-data-redis`, `lettuce-core` and `logback-classic` (persistence tests only: Spring Data Redis leaves the client to the application, and the failures' log is read as Logback events), `micrometer-core`, `testcontainers-redis`.

Design notes:

- Ports as the brief gives them, in `port/out`: `SearchCache`, `SearchKey` (`search:<generation>:<sort>:<origin|->:<destination|->:<carrier|->:<maxPrice|->:<limit>`, the sort as its enum name, Max Price as `Money`'s two-decimal amount, so equal criteria give one key), `RefreshCoordination`, `RequestCounter`, `WindowCount` with `unavailable()` = count 0, which no increment returns, so Task 10's limit admits it.
- A page is one JSON string (`SearchPageJson`): the itineraries, each its legs' array as `LegsJson` writes it, the cursor field by field (generation, sort, price, departure instant, ISO duration, id) or `null`, and `inventoryChanged`, kept so that a page comes back equal to the one put. `LegsJson` gained `write(List<Leg>, JsonGenerator)` and `read(JsonParser)` for use inside a document; its `String` forms delegate to them, under its own nine green tests. The module uses `jackson-core` streaming only, as `LegsJson` does. Only this class writes the document, so it reads it back in order; anything else is an `IllegalStateException`, not a guessed page.
- `claim` is `setIfAbsent(refresh:<origin>, "claimed", claimTtl)` (`SET … NX EX 120` in production); `refreshed` is `SET fresh:<origin> <instant>` for the freshness threshold, and `refreshedAt` reads it.
- **The counter is one Lua script**: `INCR`, `PEXPIRE … NX`, `PTTL`, on `rate:<bucket>`. A script makes the count and its expiry atomic: two separate commands could leave a count without an expiry, which would count that client for ever. `PEXPIRE` is `EXPIRE`'s millisecond form with the same `NX`; it keeps a `Duration` window exact (`EXPIRE` would truncate it to whole seconds, and a window under 1 s would delete the key at once), and `PTTL` gives `resetsIn`. A deviation in the command's unit from the brief's "`EXPIRE … NX`", not in its meaning.
- **Fail open through `ValkeyFailures`**, one instance shared by the three stores (`ValkeyConfig`), because an outage is Valkey's: `call(command, fallback)` catches Spring's `DataAccessException`, to which Spring Data Redis translates every Lettuce failure (timeout → `QueryTimeoutException`, refused connection → `RedisConnectionFailureException`, rejected command → `RedisSystemException`); anything else, such as a document that does not parse, is a bug and is thrown. One `AtomicBoolean` gives one `WARN` (with the failure) on the first failure while Valkey was answering, and one `INFO` on the first answer after it, however many threads fail meanwhile.
- `search.cache{result=hit|miss|error}` counts lookups only, all three registered at start, so `hit + miss + error` is the number of lookups and the ratio is well defined; a dropped `put` is in the `WARN`, not in this meter.
- **Settings.** The two Lettuce timeouts are Boot's own settings in `application.yaml` (`spring.data.redis.timeout: 250ms`, `connect-timeout: 1s`, Q9). `REJECT_COMMANDS` is a `LettuceClientOptionsBuilderCustomizer` in `ValkeyConfig`: Boot builds the client options (socket options with the connect timeout, timeout options) and then applies customizers, so the customizer adds to them instead of replacing them. `management.health.redis.enabled: false`. Search's settings are a new `SearchProperties` (`search.cache-ttl: PT5M`, `search.freshness-threshold: PT10M`, `search.refresh-claim-ttl: PT2M`), `ignoreUnknownFields = false`, each required and above zero, as `ProvidersProperties` does for `concurrency-limit`. Task 6 can read `freshnessThreshold` from it.
- Spring's cache abstraction (`@Cacheable`, `RedisCacheManager`, a logging `CacheErrorHandler`) was considered: it would put the cache on the application service, which ArchUnit keeps free of Spring, its meters are `cache.gets`, not §6.10's `search.cache`, and the page must go through `LegsJson`. So the brief's port and adapter.
- Test support: `support/ValkeyTestServer`, one `RedisContainer` per JVM reached with Q9's client settings, `empty()` (`FLUSHALL`) before each test, and `whileDown(call)`, which pauses the container (`docker pause`: connections stay open, nothing answers) for one call, fails the test unless the call returns within 500 ms, and resumes it. `adapters/persistence/src/test/resources/logback-test.xml` sets the root at `INFO`: without it, Logback's default printed every library's `DEBUG` lines (1,604 in one report).

Cycles:

- `SearchKeyTest.namesTheGenerationTheSortEachFilterAndTheLimit`: red = `expected: "search:7:DURATION:MAD:LHR:IB:150.00:20" but was: ""` (stub). Green = `value()` joining the parts, each filter taken with `orElseThrow()`.
- `SearchKeyTest.writesEachAbsentFilterAsADash`: red = `NoSuchElementException: No value present`. Green = an absent filter written `-`.
- `WindowCountTest.anUnavailableCountIsZeroAndResetsAtOnce`: red = `expected: WindowCount[count=0, resetsIn=PT0S] but was: null` (stub). Green = `new WindowCount(0, Duration.ZERO)`.
- Step 1: `ValkeyImage` and `ValkeyTestServer`; the container started on the first store test.
- `ValkeySearchCacheTest.roundTripsAPage` (a two-page case: a page with a cursor and the flag set, and a last page; legs at `+01:00`, `+00:00` and `-05:00`, a quote with microseconds): red = `Expecting Optional to contain: SearchPage[...] but was empty` (stub). Green = `SearchPageJson`, the `LegsJson` refactor, `GET`/`SET` of the document.
- `expiresAfterItsTtl`: red = `Expecting actual: -1L to be between: [295L, 300L]` (no expiry). Green = the `SET` given the cache's TTL as its expiry (`EX 300` for 5 min).
- `keysDifferByGeneration`: passed on first run. Mutation "the key leaves the generation out": `Expecting an empty Optional but was containing value: SearchPage[...]`.
- `ValkeyRefreshCoordinationTest.claimsOncePerOriginUntilTheClaimExpires` (claim TTL 300 ms; the key and its expiry checked; a claim again after the TTL): red = `Expecting value to be true but was false` (stub). Green = `setIfAbsent` with the claim TTL.
- `remembersWhenAnOriginWasRefreshed` (the mark's expiry 595–600 s for a 10 min threshold): red = `Expecting Optional to contain: 2026-11-20T10:00:00.123456Z but was empty.` Green = `fresh:<origin>` written and read.
- `ValkeyRequestCounterTest.countsWithinAWindowAndResets` (window 500 ms; a second request 200 ms later must not move the window's end; another bucket counts alone; a new window after the end): red = `expected: 1L but was: 0L` (stub). Green = the script. Mutation "`PEXPIRE` without `NX`": `Expecting actual: 0.5S to be less than or equal to: 0.3S`.
- `ValkeySearchCacheTest.countsEachLookupAsAHitOrAMiss`: red = `MeterNotFoundException: ... No meter with name 'search.cache' was found`. Green = the three counters.
- Fail open, each against the paused container, each with a successful command first: `ValkeySearchCacheTest.findsNothingWhileValkeyIsDown` (also: counted as one error, not a hit or miss), `dropsAPageWhileValkeyIsDown`, `ValkeyRefreshCoordinationTest.claimsNothingWhileValkeyIsDown`, `knowsOfNoRefreshWhileValkeyIsDown`, `dropsARefreshWhileValkeyIsDown`, `ValkeyRequestCounterTest.countsNothingWhileValkeyIsDown` (equals `WindowCount.unavailable()`): each red = `org.springframework.dao.QueryTimeoutException: Redis command timed out` reaching the test. Green, in turn = `ValkeyFailures.call` (catching `DataAccessException`, the error counter in the cache's fallback), `ValkeyFailures.run`, and each store's operation through them.
- `ValkeyFailuresTest.failsOpenWhenValkeyIsDown` (Step 3, Review Focus 3: the three stores sharing one `ValkeyFailures`; every operation with Valkey paused, each within 500 ms and with its fail-open value; `search.cache{result=error}` = 2 for the two lookups): red = `Expecting actual: [] to contain exactly: [(WARN, "Valkey is unavailable: ...")]` (every value and the count already held). Green = the `WARN`, with the failure, on the first failure while answering.
- `ValkeyFailuresTest.logsTheEndOfAnOutageAndTheNextOne` (outage, answers from two stores, a second outage): red = only the first `WARN` was logged. Green = one `INFO` ("Valkey answers again") on the first answer after a failure, which also lets the next outage warn again.
- `SearchPageJsonTest.refusesADocumentThatIsNotAPage` (six documents: not JSON, an unknown property in place of `next`, a string flag, content after the page, an itinerary without legs, an unknown sort): passed on first run (the reader's checks came with `roundTripsAPage`). Mutations: "no check of the property's name" → the `previous` case `Expecting code to raise a throwable`; "no check after the page" → the trailing `{}` case; "any token but `true` reads as `false`" → the `"no"` case.
- `management.health.redis.enabled: false`: adding `spring-boot-starter-data-redis` turned Boot's Redis indicator on, and with no Valkey in those tests `FlightAggregatorApplicationTest.startsAgainstPostgresAndMigrates` failed (`expected: UP but was: DOWN`), as did `SecurityTest.healthNeedsNoTokenAndShowsNoDetails` and `healthShowsItsDetailsToTheAdmin` (`Status expected:<200> but was:<503>`). Green = the setting. Those tests pin it.
- `ValkeyConfigTest.wiresEachStoreWithItsSetting` (Boot's `DataRedisAutoConfiguration`, `ValkeyConfig` and `application.yaml`, against a Valkey container: the page's expiry 295–300 s, the claim's 115–120 s, the mark's 595–600 s, a first count of 1): red = `NoSuchBeanDefinitionException: No qualifying bean of type '...RefreshCoordination'` (empty config). Green = `SearchProperties`, the four beans, the three settings. Mutation "claim TTL and threshold swapped": `Expecting actual: 599L to be between: [115L, 120L]`.
- `boundsEachCommandAndConnectionAsQ9Says`: red = `expected: 0.25S but was: 1M`. Green = the two Boot settings. Mutation "no `connect-timeout`": `expected: 1S but was: 10S`.
- `rejectsCommandsWhileDisconnected`: red = `expected: REJECT_COMMANDS but was: DEFAULT`. Green = the customizer.
- `refusesToStartWithoutASearchSetting` (one case per setting): red, all three = `Expecting: <Started application [...]> to have failed but context started successfully`. Green = each setting required, its message naming it.
- `refusesASearchSettingThatIsNotPositive` (`PT0S`, `-PT10M`, `PT0S`): red, all three = the context started. Green = above zero.
- `refusesAnUnknownSearchSetting` (`search.cache-tll`): red = the context started. Green = `ignoreUnknownFields = false`.

Refactors under the green suite, from the review of my own diff: the claim's key got its own helper beside the mark's; the health setting's comment names Q8 (it said Q5).

Gates: `./gradlew build` green (exit 0, coverage gate included; 77 tests in `adapters/persistence`, 95 in `bootstrap`, 49 in `application`, 0 failed). During one earlier `:bootstrap:test` run, `ProvidersConfigTest.countsARejectedCall` (untouched by this task) failed with Awaitility's 5 s timeout while other agents used the machine; it passed on three reruns of its class and in the full build. Thermo-nuclear review of the diff: no file near 1,000 lines (largest production file `SearchPageJson`, 152); no branch added to an existing flow (`LegsJson` only split into document-level methods); every Valkey write is one atomic command or script; nothing fails silently beyond the brief's fail-open (each outage is a `WARN` and an `INFO`, failed lookups are counted, non-Valkey errors are thrown); Boot's settings and customizer, Spring's exception translation and Spring Data's script support are used instead of hand-made ones. The one cast is `ValkeyRequestCounter`'s script result type (`RedisScript.of` takes a raw `List.class`), kept in one constant with its reason.

### Extras for the user to decide

- **Skip Valkey while it is known to be down.** Q9 bounds each command at 250 ms, and a frozen Valkey (paused, or a network that drops packets) answers nothing, so each command waits that long. A search will send up to five (Task 10's count, the cache lookup, `refreshedAt`, `claim`, the cache write), about 1.25 s added while Valkey hangs; a refused connection costs almost nothing (`REJECT_COMMANDS`). Review Focus 3 reads "plus at most the command timeout" per search. Value: one timeout per outage instead of one per command, e.g. a Resilience4j breaker (already in the project) inside `ValkeyFailures`. Cost: a breaker, its settings and tests. Left out unless you approve; Task 12's `SearchWithoutValkeyTest` and Task 13's run will measure it.
- **Treat an unreadable cached value as a miss.** A cached page or refresh mark that does not parse throws, so the search fails until the key expires (5 min for a page). Only a different version of this code could write one, as during a rolling deploy that changes the format. Value: such a search is served from PostgreSQL instead. Cost: one catch, a count and a test. Left out unless you approve.
- **Refuse a cache TTL not below the freshness threshold.** §6.4 sets the TTL below the threshold "so the cache never extends staleness"; nothing checks it. Value: a configuration cannot break that guarantee. Cost: one check and a test. Left out unless you approve.
- **A meter for failed claims and counts.** Only the cache's failures are counted (§6.10's minimum); a failed claim or count is in the outage's `WARN` only. Value: the outage's cost in metrics. Cost: a tag or two counters. Left out unless you approve.

## Task 5 — fix round 1

Review (`task-5-review.md`): functional CHANGES REQUESTED (F1 and F2 Important, F3 Minor), code CHANGES REQUESTED (C1 Important, C2 to C6 Minor). **The user's decision of 2026-11-15 on F1 and C1:** after a failed Valkey command, Valkey is skipped for 10 s, then one call probes it. This is built with Resilience4j's circuit breaker, the project's own mechanism, inside `ValkeyFailures`, so all four operations share it. The WARN on the first failure and the INFO on recovery are tied to the breaker. The 10 s is the required setting `search.valkey-skip`, `PT10S` in `application.yaml`. F2 is fixed here, and C2 with it (a page that fails to parse was counted as a hit). F3 and C3 to C6 are deferred to the end-of-plan review. These decisions build two of the extras listed in Task 5's section: "Skip Valkey while it is known to be down" (F1) and "Treat an unreadable cached value as a miss" (F2).

**Resumed round.** A network drop cut off the round's first implementer. Its uncommitted work was kept where it was right: the breaker in `ValkeyFailures` (with `tryAcquirePermission`), four tests in `ValkeyFailuresTest`, `ValkeyTestServer.commandsRun` and `withNewClient`, and `support/SettableClock`. Its 10 s hard-coded in `ValkeyConfig` was not kept. Its red → green evidence was lost, so each test it wrote is shown red below by a labelled mutation, **recorded after the fact by mutation**. Docker Desktop was not running when this session began; it was started before any test ran.

Design:

- **The breaker.** `ValkeyFailures.circuitBreakerConfig(skip)` sits beside the providers' `ProviderHttp.circuitBreakerConfig()`. Its settings:
  - A count window of 1 call and a 100% failure-rate threshold, so one failed command opens it.
  - It stays open for `skip`, then lets 1 call through half-open.
  - It starts `HALF_OPEN`: until Valkey has answered once, one caller at a time tries it, and the others get their fallback at once instead of queueing on `LettuceConnectionFactory`'s lock (C1).
  - Only `DataAccessException` counts against it.

  The bean `valkeyCircuitBreaker` in `ValkeyConfig` builds it from `search.valkey-skip` and passes it to `ValkeyFailures`. It is not in the providers' `CircuitBreakerRegistry`: that registry's breakers are named after providers, and `LoggedBreakerTransitions` logs each of their transitions as "A provider's circuit breaker changed state".
- **`call` asks `tryAcquirePermission()` and records the outcome itself**, instead of using `executeSupplier`. In a closed breaker, `executeSupplier`'s `acquirePermission()` lets every call through. `tryAcquirePermission()` reads the flag that the thread opening the breaker clears first. With `executeSupplier`, a caller whose own command had just failed could pass while another thread was opening the breaker, and pay a second timeout. Observed: with `executeSupplier`, `aBurstOfSearchesPaysOneTimeoutWhileValkeyHangs` failed in 5 of 6 class runs, at 0.510 to 0.525 s. With `tryAcquirePermission` it passed 6 of 6, the test taking 0.32 to 0.35 s.
- **A bug is recorded and thrown.** An exception other than `DataAccessException` goes to `breaker.onError`, which counts it as an answer (only `DataAccessException` is recorded as a failure) and so releases the call's permission. Then it is thrown. Without this, a bug thrown by the first call or by a probe would keep the only half-open permission, and Valkey would be skipped from then on with nothing logged.
- **Logging**, with one `AtomicBoolean outage`:
  - The first `DataAccessException` while no outage is open is one WARN, with the failure, logged in `call`.
  - The breaker's transition to `CLOSED`, seen by a listener on its event publisher, is one INFO if an outage was open.
  - The flag is needed because the breaker starts half-open: its first closing at startup is not a recovery, and a probe that fails during an outage (half-open to open) does not start a new outage. Logging on transitions alone would print an INFO at startup and a WARN every 10 s while Valkey stays down.
- **F2, the search cache.** `ValkeySearchCache` counts:
  - a miss when Valkey holds no document;
  - a hit only after `SearchPageJson.read` has returned;
  - an error, with one WARN ("A cached search page cannot be read, so the search runs without it"), when it throws `IllegalStateException`. That is the reader's documented refusal, and it also wraps Jackson's, the domain's and `java.time`'s failures.
- **F2, the refresh mark.** `ValkeyRefreshCoordination.refreshedAt` reads a mark that `Instant.parse` refuses (`DateTimeParseException`) as no refresh, with one WARN ("An origin's refresh mark cannot be read, so the origin counts as not refreshed"). There is no meter for marks; see the extras.
- **What the two WARNs leave out.** Neither carries the value or the exception, because their messages can quote the value: "Unrecognized token 'not'", "Text 'yesterday' could not be parsed".
- **Where the value is parsed.** Both are parsed after Valkey has answered: the page inside the command, the mark after it. A value that cannot be read counts as Valkey answering, so it never opens the breaker.
- `failsOpenWhenValkeyIsDown` (Step 3) now sees one command fail and the six after it skipped. Each still returns within 500 ms with its fallback, two lookups are counted as errors, and there is one WARN. The six per-store fail-open tests are unchanged apart from their constructors.

Recorded after the fact by mutation (tests the first implementer wrote). Mutations M1 to M5 were run with the WARN still logged from a breaker error-event listener. The self-review below then moved that WARN into `call` and extracted `since(started)`. That change does not touch the permission check or the configuration that M1 to M5 mutate. Mutations M6 to M8 and the bug test ran on the final code:

- `ValkeyFailuresTest.aBurstOfSearchesPaysOneTimeoutWhileValkeyHangs`. Set-up: 8 concurrent searches of 5 operations each, with Valkey paused on an open connection, must finish within 500 ms. Then one more search must take under 50 ms and send Valkey nothing (`INFO commandstats`). Mutation M1, "the breaker is not consulted" (`if (false)` in place of the permission check): `Expecting actual: 1.28981175S to be less than: 0.5S`.
- `probesValkeyOnceTheSkipHasPassed`. Set-up: Valkey paused for one lookup. After that, 10 s minus 1 ms on the breaker's clock still skips Valkey; 2 ms later one call probes it. Valkey answers, so the breaker is `CLOSED` and a claim reaches Valkey. The log is WARN, INFO, then WARN for the next outage. Three mutations:
  - M1: `Expecting an empty Optional but was containing value: SearchPage[...]`, because Valkey was used during the skip.
  - M2, "the breaker waits 1,000 times the skip": `Expecting Optional to contain: SearchPage[...] but was empty`, because there was no probe.
  - M3, "no INFO line": `[(WARN, ...), (WARN, ...)] to contain exactly [(WARN, ...), (INFO, "Valkey answers again"), (WARN, ...)]`.
- `letsOneCallAtATimeProbeValkey`. Set-up: after the skip, 8 concurrent lookups while Valkey still hangs. Valkey must have run exactly one `GET` (the probe). Mutation M4, "`permittedNumberOfCallsInHalfOpenState(1)` removed" (the default is 10): `expected: 1L but was: 8L`. M4 also failed `callersDoNotQueueBeforeTheFirstConnection` (2.40 s).
- `callersDoNotQueueBeforeTheFirstConnection` (C1). Set-up: a client of its own that has never connected, Valkey paused before its first command, 8 concurrent lookups, all within 500 ms. Mutation M5, "the breaker starts `CLOSED`": `Expecting actual: 2.375729834S to be less than: 0.5S`. The 8 connection attempts ran one after another, as the reviewer measured.

New in this session, one test at a time:

- `ValkeyFailuresTest.warnsOnceHoweverManyProbesFail`. Set-up: an outage, then two probes that Valkey does not answer. Valkey ran both probe `GET`s, and the log is one WARN. It passed on its first run, because the flag already gave this. Mutation M6, "every failure warns": `Expecting actual: [WARN, WARN, WARN] to contain exactly: [WARN]`.
- `callersDoNotQueueBeforeTheFirstConnection` gained an assertion that the outage is one WARN when Valkey is down from the start. It passed on its first run. Mutation M7, "only a failure while the breaker was closed warns" (the WARN tied to the closed-to-open transition): `Expecting actual: [] to contain exactly: [WARN]`.
- Mutation M8, "every closing of the breaker logs the INFO", also failed `failsOpenWhenValkeyIsDown`: an INFO at the first answer at startup, before the WARN. The first run of M8 ended with `Could not connect to Ryuk` (Testcontainers, while another agent's containers were running); the re-run gave the red above.
- `throwsABugWithoutSkippingValkey`. Set-up: the first call throws `IllegalStateException`, and a page put afterwards is found again. The branch it covers came from the first implementer, so this is recorded after the fact by mutation. Mutation "no `catch (RuntimeException)` branch": `Expecting Optional to contain: SearchPage[...] but was empty`, because the half-open permission was never released.
- `ValkeyConfigTest.refusesToStartWithoutASearchSetting`, new case `search.valkey-skip`. Red: `Expecting: <Started application [...]> to have failed but context started successfully`. Green: `SearchProperties.valkeySkip`, required, and `valkey-skip: PT10S` in `application.yaml`.
- Refactor under green: `ValkeyConfigTest`'s search key and empty page became constants, so that two tests share them.
- `skipsValkeyForItsSettingThroughTheStoresBreaker`. Set-up: the application's settings with `search.valkey-skip=PT3S`; a page is put, the context's `CircuitBreaker` is opened, and the page that Valkey holds is not read; the breaker waits 3,000 ms. Red: `NoSuchBeanDefinitionException: No qualifying bean of type 'io.github.resilience4j.circuitbreaker.CircuitBreaker'`. Green: the `valkeyCircuitBreaker` bean, built from the setting, passed to `valkeyFailures`. Mutation "the stores get a breaker of their own": `Expecting an empty Optional but was containing value: SearchPage[...]`.
- `refusesASearchSettingThatIsNotPositive`, new case `search.valkey-skip, -PT10S`. It passed on its first run, because the same check covers it. Mutation "no check of `valkey-skip`": startup still failed, but with Resilience4j's `waitDurationInOpenState must be at least 1[ms]`, which does not name the setting, so the test was red. A missing setting failed the same way, with an NPE inside Resilience4j.
- `skipsValkeyTenSecondsAfterAFailedCommand` checks `application.yaml`'s value, as `boundsEachCommandAndConnectionAsQ9Says` does for Q9's. It passed on its first run. Mutation "`valkey-skip: PT11S`": `expected: 10000L but was: 11000L`.
- `ValkeySearchCacheTest.findsNothingInAPageItCannotRead` (`{"itineraries":"not a page"}` under the key). Red: `IllegalStateException: the cached document is not a search page: expected START_ARRAY but found VALUE_STRING` reached the caller. Green: a miss when there is no document, an error when the read refuses it, a hit after the read. Mutation "the hit counted before the read" (the reviewer's C2): `expected: 0.0 but was: 1.0`.
- `warnsOfAPageItCannotReadWithoutItsContent` (`not json` under the key). Red: `Expecting actual: [] to contain exactly [(WARN, "A cached search page cannot be read, so the search runs without it", null)]`. Green: the WARN. Mutation "the WARN carries the failure": the event has a `ThrowableProxy` where `null` is expected.
- `ValkeyRefreshCoordinationTest.knowsOfNoRefreshFromAMarkItCannotRead` (`yesterday` under `fresh:MAD`). Red: `DateTimeParseException: Text 'yesterday' could not be parsed at index 0` reached the caller. Green: a refused mark is no refresh.
- `warnsOfAMarkItCannotReadWithoutItsContent`. Red: no WARN. Green: the WARN. Mutation "the WARN carries the failure": the event has a `ThrowableProxy` where `null` is expected.

Self-review (thermo-nuclear) of the diff, and what it changed:

- The WARN is logged in `call`'s catch instead of from a breaker error-event listener. `call` is the only caller of `breaker.onError` and already holds the exception, so the listener was an extra hop. Only the closing transition stays a listener.
- `since(started)` replaces three copies of the elapsed-time expression.
- The `catch (RuntimeException)` branch had no test, and a mistake in it fails silently (Valkey skipped for good), so it got `throwsABugWithoutSkippingValkey`.
- Existing mechanisms were looked for first. The breaker is Resilience4j, already used for the providers. `LoggedBreakerTransitions` was not reused, for the reason in the design notes. Spring's exception translation decides what counts as Valkey's failure. Each codec's documented refusal (`IllegalStateException`, `DateTimeParseException`) is caught, not `RuntimeException`.
- No file near 1,000 lines: `ValkeyFailures` is 117, `ValkeyFailuresTest` 284. No branch was added to a flow outside the Valkey adapter. No state is left half-applied: the breaker's state machine is Resilience4j's, and the flag only removes duplicate log lines.
- What can still go unnoticed is listed below.

Gates: `./gradlew spotlessApply`, then `./gradlew build --continue`: `BUILD SUCCESSFUL`, exit 0, coverage gate included. Tests: 86 in `adapters/persistence`, 99 in `bootstrap`, 49 in `application`, 90 in `domain`, 197 in `adapters/providers`, 201 in `mock-gds`, 39 in `architecture-tests`, none failed. During the round, one Testcontainers run failed to reach Ryuk and passed when re-run (M8, above).

### Extras for the user to decide

- **The Valkey breaker's state as a meter.** The providers' registry binds `TaggedCircuitBreakerMetrics`; the Valkey breaker is a bean outside that registry and has no meter. Value: an outage is visible in metrics, not only in the WARN and `search.cache{result=error}`, and Task 12 could read it for `DEGRADED`. Cost: one binding in `ValkeyConfig` and a test. Left out unless you approve.
- **A half-open breaker that heals itself.** If a command threw an `Error` (not an `Exception`) during the first call or a probe, the call's permission would never be released and Valkey would be skipped until restart. `ValkeyFailures` catches `RuntimeException`, and Resilience4j's own decorators catch only `Exception`. `maxWaitDurationInHalfOpenState` would reopen the breaker after a while and probe again. Value: robustness against a JVM `Error` in a command. Cost: one setting and a test. Left out unless you approve.
- **Fewer WARNs for unreadable values.** Each unreadable page or mark is one WARN. After a format change, each key is unreadable until the next search writes it again (5 min for a page), so a busy deploy logs one WARN per key searched. A once-per-format WARN plus a count would bound the log. Left out unless you approve.
- **A meter for unreadable refresh marks.** Only the cache's unreadable pages are counted (`search.cache{result=error}`); a mark is in the WARN only. Left out unless you approve.

## Task 5 — round 2 (extras and re-review minors)

Re-review of fix round 1 (`task-5-rereview-1.md`): functional and code APPROVED, with three Minor code findings (C-R1 to C-R3). This round fixes them and builds the two extras the user approved on 2026-11-15: the state of Valkey's breaker as a meter, and fewer WARN lines when many cached values fail to parse at once. C-R1 is built as the user decided after the re-review: a probe or call that throws anything other than Valkey's failure gives the breaker's permission back and is thrown on.

### C-R1: a throwing call gives its permission back

- `ValkeyFailuresTest.keepsAnOutageWhenItsProbeThrowsABug`. Set-up: Valkey paused for one lookup, the skip passed on the breaker's clock, then the probe throws `IllegalStateException`. The breaker must still be `HALF_OPEN` and the log hold only the outage's WARN. Red: `expected: HALF_OPEN but was: CLOSED`: `onError` with an exception outside `recordExceptions` is recorded as a success, so the bug closed the breaker (and logged a false "Valkey answers again"). Green: the non-Valkey branch calls `breaker.releasePermission()` instead of `onError`, then rethrows.
- `letsALaterCallProbeWhenAProbeThrowsAnError`. Set-up: a page put, an outage, the skip passed, then the probe throws `StackOverflowError`. A later lookup must find the page. Red: `Expecting Optional to contain: SearchPage[…] but was empty.`: the `Error` escaped the `RuntimeException` branch, the half-open permission was never given back, and every later call was refused. Green: the branch catches `Throwable` (a precise rethrow, so `call` still declares nothing).

### C-R2: the burst and first-connection tests count instead of timing

`ValkeyTestServer.whileDown` gained an overload that takes a hang guard instead of the 500 ms bound; both tests use it with 5 s (`HANG_GUARD`), far above one command timeout and far below Lettuce's 60 s. Step 3's `failsOpenWhenValkeyIsDown` and every other caller keep the 500 ms per operation. The burst test's `< 50 ms` check on the next search went with its `elapsed` helper: the count that follows it already shows that the search sent nothing, so it could not have waited on Valkey.

- `aBurstOfSearchesPaysOneTimeoutWhileValkeyHangs`: the 8 callers' commands are counted from `INFO commandstats` on the connection they went on, before and after the burst, and must be at most 8, one per caller; the search after the burst must add none. Green on its first run (it pins existing behaviour), so shown red by mutation:
  - M-a, "`acquirePermission()` in place of `tryAcquirePermission()`" (what `executeSupplier` does): `Expecting actual: 14L to be less than or equal to: 8L`, then 14 and 11 in two more runs (3 of 3 red).
  - M-b, "the breaker is not consulted" (`if (false && …)`): `Expecting actual: 40L to be less than or equal to: 8L`. It stayed within the hang guard.
- `callersDoNotQueueBeforeTheFirstConnection`: the breaker given to the never-connected client must have refused 7 of the 8 callers (`getNumberOfNotPermittedCalls()`; Resilience4j counts a refusal in the half-open and the open state alike, and the open state keeps the half-open state's metrics). Green on its first run, so shown red by mutation M-c, "the breaker starts `CLOSED`": `expected: 7L but was: 0L`. The run took 2.65 s, within the hang guard, so the count alone made it red.

### C-R3: a Valkey skip under a millisecond

- `ValkeyConfigTest.refusesAValkeySkipUnderAMillisecond` (`search.valkey-skip=PT0.0005S` over the application's settings): startup must fail naming `search.valkey-skip`. Red: the stack trace held only Resilience4j's `waitDurationInOpenState must be at least 1[ms]`, from the `valkeyCircuitBreaker` bean. Green: `SearchProperties` refuses a skip under 1 ms (`SHORTEST_VALKEY_SKIP`), with "search.valkey-skip must be at least 1 ms, not PT0.0005S". `require`'s comment now gives the reason that holds for each setting: Valkey refusing the writes for the three times to live, Resilience4j refusing the breaker for the skip.

### Extra (approved): the state of Valkey's breaker as a meter

Built with Resilience4j's own binding, `TaggedCircuitBreakerMetrics`, as `ProvidersConfig` binds the providers' breakers. Valkey's breaker stays out of the providers' registry (their transition WARN names a provider, and `ProvidersConfigTest.givesEachProviderItsOwnBreakerNamedAfterIt` pins that registry to ALPHA, BETA and GAMMA): `valkeyCircuitBreaker` now builds it from a registry of its own, local to the bean method and not a bean, and binds that registry. The binding's meters are renamed from `resilience4j.circuitbreaker.*` to `valkey.circuitbreaker.*` through `CircuitBreakerMetricNames`, so a Grafana panel over the providers' breakers never shows Valkey and Valkey's has a name of its own. Tags are the binding's (`name=valkey`, plus `state` or `kind`), all from fixed sets.

- `ValkeyConfigTest.measuresTheValkeyBreakersState`: the gauge `valkey.circuitbreaker.state{name=valkey,state=half_open}` is 1 at startup, before Valkey has answered. Red: `MeterNotFoundException: … No meter with name 'valkey.circuitbreaker.state' was found.` Green: the breaker built from its own registry, bound with the state meter renamed.
- `namesEveryMeterOfTheValkeyBreakerApartFromTheProviders`: every meter tagged `name=valkey` is named `valkey.circuitbreaker.*`. Red: `Expecting all elements of: ["resilience4j.circuitbreaker.slow.calls", "resilience4j.circuitbreaker.failure.rate", "resilience4j.circuitbreaker.calls", …, "valkey.circuitbreaker.state", …]`: the binding's other six meters still had the providers' names. Green: `VALKEY_BREAKER_METERS` renames all seven.

### Extra (approved): fewer WARN lines when many cached values cannot be read at once

**Mechanism chosen.** Logback has none that fits: its `DuplicateMessageFilter` is a turbo filter on the whole logger context (every logger at once), drops a message for good after a few repeats, and counts nothing. The codebase's closest one is `ValkeyFailures`' "one WARN per outage" flag, but an unreadable value has no event that ends its episode (after a format change, readable pages written by the new version interleave with the old unreadable ones). So a small class of its own, `EpisodeWarning` (package-private in `adapters/persistence`), built on the application's `Clock` port, as `PagedProvider` takes it:

- The first unreadable value logs the WARN and starts an episode; the values until the episode ends are only counted; the first one after it logs the WARN again, with that count as the structured field `unloggedRepeats` (Boot's ECS JSON makes it a field). The message and the "no value, no throwable" rule are unchanged.
- An episode lasts the values' own time to live: `search.cache-ttl` for pages, `search.freshness-threshold` for refresh marks. That is the longest a value written in another format can still be read, so a format change is one WARN per store.
- It is lock-free: one `AtomicReference` to an `Episode` record, updated with `getAndUpdate`, so of callers racing at an episode's start exactly one logs.
- `search.cache{result=error}` still counts every unreadable page, logged or not (asserted below). Applied to both stores because the extra, as listed in fix round 1, covers "each unreadable page or mark"; the marks still have no meter (that extra stays out).
- Wiring: `ValkeySearchCache` and `ValkeyRefreshCoordination` take a `Clock` as their last argument; `ValkeyConfig` passes the application's. `ValkeyConfigTest`'s slice now loads `SystemClock` (red without it: 7 of 17 tests, `No qualifying bean of type 'com.flightaggregator.application.port.out.Clock'`).

Evidence, one test at a time. The reds of the first three tests were seen in this session before the usage limit interrupted it, and are quoted as seen; the coordinator asked for any evidence not yet in this report to be re-established by mutation, so each is also shown red by a labelled mutation, **recorded after the fact by mutation**.

- `ValkeySearchCacheTest.warnsOnceOfManyPagesItCannotReadAtOnce` (`not json` under three keys, each looked up once): one WARN, and `search.cache{result=error}` is 3. Red: `Expecting actual: [WARN, WARN, WARN] to contain exactly (and in same order): [WARN]`. Green, minimal: a once-only flag in the cache (replaced by the next test).
- `warnsAgainOnceItsTimeToLiveHasPassedCountingTheOnesNotLogged` (one unreadable key: three lookups, one at TTL − 1 ms, one at TTL): the log must be `(WARN, unloggedRepeats=0)`, `(WARN, unloggedRepeats=3)`. First red: the test did not compile, as `ValkeySearchCache` took no clock; with the parameter added and unused: `Expecting actual: [(WARN, null)] to contain exactly (and in same order): [(WARN, 0L), (WARN, 3L)]`. Green: `EpisodeWarning`, with the cache's TTL. Refactor under green: the test compares SLF4J `KeyValuePair`s (it has `equals`) instead of a helper of its own.
- `ValkeyRefreshCoordinationTest.warnsOnceOfManyMarksItCannotReadAtOnce` (`yesterday` under `fresh:MAD` and `fresh:BCN`; three reads): one WARN. First red: no constructor with a clock; with the parameter added and unused: `Expecting actual: [WARN, WARN, WARN] to contain exactly (and in same order): [WARN]`. Green: `EpisodeWarning` with the freshness threshold.
- `warnsAgainOnceTheFreshnessThresholdHasPassedCountingTheOnesNotLogged` (reads at 0, threshold − 1 ms and threshold): `(WARN, 0)`, `(WARN, 1)`. Green on its first run, because the previous step already passed the threshold. Mutation M-e, "the marks' episode lasts the claim's TTL": `Expecting actual: [(WARN, [unloggedRepeats="0"]), (WARN, [unloggedRepeats="0"])] to contain exactly …[(WARN, [unloggedRepeats="0"]), (WARN, [unloggedRepeats="1"])]`.
- Mutations, recorded after the fact by mutation:
  - M-d, "every event warns" (`if (true || …)` in `EpisodeWarning.warn`): all four red. The two "at once" tests: `Expecting actual: [WARN, WARN, WARN] to contain exactly (and in same order): [WARN]`. The two episode tests: five and three WARNs where two were expected.
  - M-f, "the count is not carried" (`unloggedRepeats` always 0): both episode tests red, `Expecting actual: [(WARN, [unloggedRepeats="0"]), (WARN, [unloggedRepeats="0"])]`.
  - M-g, "the pages' episode lasts twice the TTL": `Expecting actual: [(WARN, [unloggedRepeats="0"])] to contain exactly (and in same order): [(WARN, [unloggedRepeats="0"]), (WARN, [unloggedRepeats="3"])]`.

What it does not do: the count of an episode's unlogged values is told only by the next episode's first WARN, which may come much later or never; `search.cache{result=error}` has the exact count for pages at any time, and marks have none.

### Self-review (thermo-nuclear) and gates

- Existing mechanisms first: Resilience4j's `releasePermission()` for C-R1 (direct, rather than its "ignored exception releases the permission" rule, which a reader would have to know); Resilience4j's own Micrometer binding and `CircuitBreakerMetricNames` for the meter; the application's `Clock` port for the episodes; Logback's `DuplicateMessageFilter` looked at and rejected, for the reason above.
- No branch added to a flow outside the Valkey adapter and its wiring. `SearchProperties` gained one check for the one setting whose floor is not zero, beside the shared `require`.
- No file near 1,000 lines: `ValkeyFailuresTest` 338, `ValkeySearchCacheTest` 294, `ValkeyFailures` 118, `EpisodeWarning` 63.
- Nothing left half-applied: the breaker's state is Resilience4j's; an episode is one atomic reference.
- What can still go unnoticed: an episode's last count is told only by a later WARN (see above). `recordExceptions(DataAccessException.class)` in `ValkeyFailures.circuitBreakerConfig` no longer decides anything, since only a `DataAccessException` now reaches `onError`; it was left as it is, outside this round's items.
- Gates: `./gradlew build --continue` green (exit 0) before each of this round's commits, coverage gate included, after `./gradlew spotlessApply` where Spotless asked. Totals at the end: persistence 103, bootstrap 111, application 64, domain 91, providers 197, mock-gds 201, architecture 39, none failed. No container connection failure was seen in this round.

### Extras for the user to decide

- **A floor of 1 ms for the three times to live too.** `cache-ttl`, `freshness-threshold` and `refresh-claim-ttl` under a millisecond pass `require` and reach Spring Data Redis as zero milliseconds (`Expiration.from`); whether Valkey then refuses each write (which the stores would take for an outage) or keeps the key without expiry was not checked. Value: the same clear refusal at startup as `valkey-skip`. Cost: `require`'s rule changed to "at least 1 ms" and three test cases. Left out unless you approve.
- **The hang guard for the two other concurrent Valkey tests.** `letsOneCallAtATimeProbeValkey` and `warnsOnceHoweverManyProbesFail` already assert counts, but still sit under `whileDown`'s 500 ms bound, the flake risk C-R2 removed from the burst and first-connection tests. Value: no wall-clock failure on a loaded CI runner. Cost: two lines in tests. Left out unless you approve.

## Task 6 — evidence

The search use case. One test at a time; the module's suite ran after each cycle. A test that passed on its first run is labelled and shown red by a mutation of the production code, which was then restored.

Design notes:

- Ports as the plan gives them: `SearchItineraries`, `SearchQuery` and `InvalidCursorException` in `port/in`; `RefreshScheduler` in `port/out`. `InvalidCursorException` is a `RuntimeException`, not an `IllegalArgumentException`, so no global mapping of the latter can reach it (global constraints); Task 9 maps it to 400. `RefreshScheduler`'s Javadoc states its contract: `request` returns at once and never throws (Task 7's `BackgroundRefresh` implements it). `SearchQuery` does not check `limit`: the endpoint validates it (Q9), as the user decided for the reader in Task 4.
- The service takes the application's own `Clock` port and reads it once per search, for the reader's `now` and for staleness. It reads `state()` once per search: its generation keys the cache and its `publishedAt` measures staleness. A page after a cursor, or with Max Price, and without an origin does not use it; one primary-key read was kept rather than a second path.
- **Q1:** `inventoryChanged` compares the cursor's generation with the generation the page was read from (`StoredPage.generation`), and the cursor's position is the seek position either way.
- **Q3:** only queries without a cursor and without Max Price look the cache up and write it. A page after a cursor has the same `SearchKey` as its first page (the key holds no cursor), so looking it up would serve the first page again; the test pins it.
- **A page of a newer generation** (a run published between `state()` and `page()`) is served, with its own generation in its cursor, and not cached, as the plan says.
- **Staleness (§6.4):** `now − max(publishedAt, refreshedAt(origin)) > threshold`, strictly: exactly 10 min is fresh. The origin is claimed only when stale, since a claim on a fresh origin would hold for 2 min (Q9) and could stand in the way of the refresh once it becomes due. The check runs once per search with an origin, after the page is ready, whether the page came from the cache or from PostgreSQL; a search that fails to read claims nothing.
- **Before the first publication** (`publishedAt` empty) nothing is refreshed (confirmed by the user on 2026-11-15). The plan's formula has no value then; the first run is loading every origin, and Task 7's refresh rewrites an active slot that holds nothing yet. `doesNotRefreshBeforeTheFirstPublication` is beyond the plan's list, required by that production line: the minimal `orElseThrow()` failed every search with an origin before the first run.
- **How the refresh request never delays or fails a search.** (1) It comes after the page. (2) `refreshedAt` and `claim` go through Task 5's `ValkeyFailures`: a Valkey failure is the fail-open answer (no refresh known, no claim), each command bounded by Q9's 250 ms and skipped for 10 s after a failure, so a hung Valkey costs one search one command timeout per 10 s, as for the cache. With Valkey answering, they are two round trips at most, the claim only when stale. (3) `request` returns at once and never throws, by its port's contract, which Task 7's `BackgroundRefresh` must honour as the plan describes it (the refresh on a virtual thread, its failure logged, pinned by `logsAFailureWithoutThrowing`). What the service itself does not guarantee: an exception that is not Valkey's (a bug) in the coordination or the scheduler propagates and fails that search, by the project's rule that bugs are thrown, not swallowed (`ValkeyFailures`); the application has no logger, so a catch there would be silent (see the extras). If a claim is won and the request then never starts a refresh, the claim blocks the origin's refresh for its 2 min (Q9), no longer.
- Test support: `fakes/ScriptedItineraryReader` (new) answers the state and rows the test sets and records each page asked; seeking and filtering stay the real reader's (Task 4), and the global constraints forbid ordering by `UUID` in Java. `InMemoryItineraryReader` was not extended: re-price's tests rely on it failing any other filter, order or seek. `fakes/InMemorySearchCache` and `fakes/InMemoryRefreshCoordination` are new; Task 7 can reuse the latter.

Cycles (`SearchItinerariesServiceTest`):

- `returnsAPageWithACursorWhenMoreRowsExist` (three rows, limit 2): red = `expected: SearchPage[itineraries=[... flightNumber=1 ..., ... flightNumber=2 ...], next=Optional[PageCursor[generation=7, sort=PRICE, ...]], inventoryChanged=false] but was: null` (stub). Green = a page of `limit` rows, always with a cursor at its last row.
- `returnsNoCursorOnTheLastPage` (two rows, limit 2): red = `expected: SearchPage[..., next=Optional.empty, ...] but was: SearchPage[..., next=Optional[PageCursor[generation=7, ...]]]`. Green = `limit + 1` rows read; the cursor only when the extra row exists, at the last row returned.
- `flagsACursorOfAnotherGeneration` (cursors of generations 6 and 7 against generation 7; both seek from the cursor's position): red = `expected: SearchPage[..., inventoryChanged=true] but was: SearchPage[..., inventoryChanged=false]`. Green = the cursor's position as the seek, and the flag when its generation differs from the page's.
- `rejectsACursorOfAnotherSortOrder` (a price cursor on a departure search): red = `Expecting code to raise a throwable.` Green = `InvalidCursorException` before anything is read.
- `servesACachedFirstPageWithoutReading`: red = expected the cached page (flight 3, no cursor), got the page read (flights 1 and 2, with a cursor). Green = `state()`, then the cache looked up under `SearchKey(generation, criteria, limit)`.
- `cachesOnlyFirstPagesWithoutMaxPrice` (a page already cached under the Max Price search's key; a first page, then its next page and the Max Price page): red = the Max Price page came from the cache, `expected: ... flightNumber=3 ... but was: ... flightNumber=1 ...`. Green = only queries without a cursor and without Max Price look up and write the cache. Mutation "the cache ignores the cursor": this test got the cached first page (flights 1 and 2, with a cursor) for the next page, and `flagsACursorOfAnotherGeneration` got a cached page.
- `doesNotCacheAPageOfAnotherGeneration` (pages read from generation 8, state at 7): red = `Expecting empty but was: {SearchKey[generation=7, ...]=SearchPage[... PageCursor[generation=8 ...]]}`. Green = a page is cached only when read from its key's generation.
- `requestsOneRefreshForAStaleOrigin` (parameterised: the page served from the cache, or a Max Price page read from PostgreSQL; published 11 min ago; two searches): red, both cases = `Expecting actual: [] to contain exactly (and in same order): [Airport[code=MAD]]`. Green = after the page, `request(origin)` when `now − publishedAt > threshold` and `claim(origin)` succeeds. Mutation "request without the claim": both cases `[Airport[code=MAD], Airport[code=MAD]]`. Mutation "no check for a page that can be cached": the cache case `[]`.
- `doesNotRefreshAFreshOriginOrASearchWithoutOrigin` (four cases; nothing requested, nothing claimed): red, case "published 11 min ago, refreshed 5 min ago" = `Expecting empty but was: [Airport[code=MAD]]`. Green = the later of `publishedAt` and `refreshedAt(origin)`. The other three cases passed on first run: mutation "`>=` instead of `>`" fails "published exactly 10 min ago"; mutation "the refresh time wins whenever present" fails "published 1 min ago, refreshed 11 min ago"; mutation "the destination when there is no origin" fails "to Madrid from anywhere, published 11 min ago".
- `doesNotRefreshBeforeTheFirstPublication` (beyond the plan's list): red = `java.util.NoSuchElementException: No value present` (the search failed). Green = no publication, so nothing is stale.

Refactors under the green suite, from the review of my own diff: the sort-order check is its own method with a plain `if` (it threw from an `Optional.ifPresent` lambda); the read and the page's assembly are separate methods, so the cache path can compare the page's generation; the service's Javadoc states its contract at the ports' level instead of naming Valkey's breaker; `cachesOnlyFirstPagesWithoutMaxPrice` uses the class's `FROM_MADRID` and `FROM_MADRID_UP_TO_200` instead of building its own Max Price criteria. That test changed after its evidence above, so it was checked again on its final version, recorded after the fact by mutation: "the cache ignores the cursor" fails it (and `flagsACursorOfAnotherGeneration`), and "the cache ignores Max Price" fails it, each with the cached page served instead of flight 3.

Gates: `./gradlew build` green (exit 0, Spotless and the coverage gate included): 78 tests in `application` (14 in `SearchItinerariesServiceTest`), 108 in `bootstrap`, 97 in `adapters/persistence`, 197 in `adapters/providers`, 91 in `domain`, 39 architecture rules, 201 in `mock-gds`, none failed. Thermo-nuclear review of the diff: no file near 1,000 lines (the service 139, its test 313); no branch added to an existing flow (every file but the progress report is new); the service is one flow (refuse a foreign cursor, read or serve the page, then the refresh step), with the cache rule, the page's assembly and staleness each in one method; existing mechanisms reused: the application's `Clock` port, `SeekPosition.of`, `SearchKey`, and Task 5's fail-open ports; nothing written half-way (the claim is the only write, and its TTL bounds it); what is not guaranteed is stated above and offered as an extra.

### The wiring (`SearchConfig`): decided by the user

**Decided by the user on 2026-11-15: A** (recorded under Task 6 in the plan), built in "Task 6 — the wiring, after the user's decision" below. The user also confirmed that no refresh is requested before the first publication. The question as it was put:

The plan's Task 6 creates `bootstrap/.../config/SearchConfig.java`, whose bean needs a `RefreshScheduler`, and Task 7 builds the only implementation. With `SearchConfig` in this commit, no application context starts until Task 7 lands (`FlightAggregatorApplicationTest`, `IngestionEndToEndTest`, the security tests and the compose `app` service), and Task 9, parallel with 7, needs the bean for its `OpenApiContractTest`. Checked, not committed: with a `SearchConfig` declaring the use case as the plan describes, all 17 tests of `FlightAggregatorApplicationTest` failed to load the context, `No qualifying bean of type 'com.flightaggregator.application.port.out.RefreshScheduler'`. Every way out is a design choice the plan does not make, so `SearchConfig` is not in this commit, and the choice is the controller's or the user's:

- **A.** `SearchConfig` now, with an interim `RefreshScheduler` in its own `RefreshConfig` (the file Task 7 may create for its wiring) that logs one `WARN` per request ("origin X is stale, but its background refresh is not built yet") and returns. Task 7 replaces it; a second bean would stop startup, so it cannot stay by accident. Keeps 7 and 9 parallel. Cost: about 25 lines and a test, deleted by Task 7.
- **B.** `SearchConfig` moves to Task 7, beside `BackgroundRefresh`. No interim code; Task 9's Steps 1 to 3 run in parallel with 7, and its Step 4 (`OpenApiContractTest`, on the whole application) waits for 7 on `main`.
- **C.** Task 7 before Task 9, with `SearchConfig` in 7.

Recommendation: A, which keeps the plan's parallelism; B if no interim code may reach `main`.

### Extras for the user to decide

- **A guard so that even a bug in the refresh step cannot fail a search.** Today an exception that is not Valkey's, thrown by the coordination or the scheduler, fails that search. A catch around the refresh step would stop it, but the application has no logger (ArchUnit allows only Java and Spring there), so it needs a bootstrap decorator that logs or counts what it catches, or the failure would be silent. Value: a search answers even with a broken refresh path. Cost: a decorator and a test. Left out unless you approve.
- **Skip reading the refresh mark while the last run is fresh.** `refreshedAt` is read on every search with an origin; while `publishedAt` is within the threshold, the origin is fresh whatever the mark says. Value: one Valkey command less per such search during the first 10 min after each run (a third of the time with the 30 min cadence), and no wait on a hung Valkey then. Cost: one condition and a test. Left out unless you approve.

### Task 6 — the wiring, after the user's decision

Option A, built one test at a time, as the use case was.

Design notes:

- `SearchConfig` declares `SearchItineraries` over the reader, the cache, the coordination, the scheduler, the application's `Clock` and `SearchProperties.freshnessThreshold()`, as `RepriceConfig` declares re-price. It enables `SearchProperties` itself, as `ValkeyConfig` does, so it can be built without Valkey's configuration; Spring registers the properties bean once. It only consumes the `SearchCache` and `RefreshCoordination` beans, so Task 5's round 2, which adds a `Clock` to their constructors in `ValkeyConfig`, does not touch it.
- `RefreshConfig` holds the provisional `RefreshScheduler`: a lambda that writes one `WARN` per request, with the origin as a structured field (`origin=MAD`), and returns. Its Javadoc says Task 7 replaces it. Task 7 may keep the file for its own wiring, as the plan allows (`IngestionConfig` or a new `RefreshConfig`). Since a request comes only after a won claim, a stale origin gives at most one `WARN` per claim TTL (2 min) across instances.
- Startup stops when it stands beside another scheduler: `SearchConfig`'s one injection point finds two beans (`NoUniqueBeanDefinitionException`). If Task 7's bean took the same name, `refreshScheduler`, Boot's refusal to override a bean definition stops startup too.

Cycles:

- `SearchConfigTest.declaresTheSearchUseCase` (an `ApplicationContextRunner` with `application.yaml`'s settings and the test's own reader, cache, coordination, scheduler and clock): red = `[Bean of type <interface ...SearchItineraries> ...] Expecting actual not to be null` (an empty `SearchConfig`). Green = the bean.
- `SearchConfigTest.asksToRefreshAnOriginOnlyOnceItIsMoreThanTenMinutesOld` (published `PT10M` ago: no request; `PT10M1S`: one): passed on first run. Mutation "the cache TTL wired as the threshold": `Expected size: 0 but was: 1 in: [Airport[code=MAD]]` for `PT10M`.
- With `SearchConfig` in place, `FlightAggregatorApplicationTest`: red, all 17 = `No qualifying bean of type 'com.flightaggregator.application.port.out.RefreshScheduler'`.
- `RefreshConfigTest.warnsOfEachOriginItCannotRefreshYet` (two requests, MAD then BCN; the log read as Logback events, as in `LoggedDiscardedRecordsTest`): red = `NoSuchBeanDefinitionException: No qualifying bean of type '...RefreshScheduler' available` (an empty `RefreshConfig`). Green = the provisional scheduler with its `WARN`. `FlightAggregatorApplicationTest` then green, 17 of 17.
- `SearchConfigTest.refusesToStartWithTheProvisionalSchedulerBesideAnother` (the user's decision: both beans together stop startup; the test's own scheduler, named `backgroundRefresh`, stands for Task 7's). The first run failed, for a reason in the test's setup: the runner had registered its scheduler under `refreshScheduler`, the provisional bean's name, so the context stopped with `BeanDefinitionOverrideException` instead of the clash being pinned. With the test's scheduler named `backgroundRefresh`, it passed with no production change. Mutation "the provisional scheduler marked `@Primary`" (it would silently win over Task 7's): `Expecting: <Started application [...]> to have failed but context started successfully`.

Gates: `./gradlew build` green (exit 0, Spotless and the coverage gate included): 113 tests in `bootstrap` (5 new: 4 in `SearchConfigTest`, 1 in `RefreshConfigTest`; every whole-application test passed, `FlightAggregatorApplicationTest` 17 of 17), the other modules as above, none failed. Thermo-nuclear review of the diff: two new configuration classes of 36 and 27 lines, written as `RepriceConfig` is, and no existing file changed. The provisional scheduler is loud, one `WARN` per request; it is documented as provisional, and a test makes sure it cannot outlive Task 7. The test's fakes are inner classes, as in `RepriceConfigTest`, because bootstrap cannot see the application's test fakes.

## Task 6 — fix round 1

Review (`task-6-review.md`): functional CHANGES REQUESTED (F1 Important, F2 Minor), code CHANGES REQUESTED (C1 and C2 Important, C3 Minor, C4 informational). **The user's decisions of 2026-11-15 on C1 and C2** (recorded under Task 6 in the plan): a page served from the cache keeps only the itineraries still sellable at `now`; a refresh is claimed and requested only for an origin in the airport catalogue, checked in the search service. The branch was first rebased on `main` (`964ca92`, with Task 5's round 2). Its only conflict was textual, in this report, where both branches appended: both sections are kept, `main`'s first. Round 2's `Clock` arguments in `ValkeyConfig` do not touch `SearchConfig`. One test at a time, as before; a test that passed on its first run is shown red by a labelled mutation of the production code, which was then restored.

Design notes:

- **F1:** `asksTheReaderForTheQuerysFiltersAfterNowWithOneRowMore` pins what the reader is asked: the query's four filters with a sort other than price, no seek position, `limit + 1` and `now`. Every other test's criteria are empty or carry an origin only.
- **C1:** on a cache hit, the page keeps only `itinerary.isSellable(now)`, the domain's rule (R5). **The page's size:** it may hold fewer rows than the page size, as many fewer as have departed since it was cached. **`next`:** kept as cached. It seeks after the cached page's last row, so "Load more" lists what comes after that row, and the next page is read from PostgreSQL with the departure cutoff applied again, so nothing is listed twice and nothing departed comes back. If every row of a cached page has departed, the page comes back empty with its `next`. "Load more" still continues correctly from there. Nothing is written back to the cache.
- **C2:** the service takes the `AirportCatalogue` port, the owner of "the airports the system knows" (§4), the one ingestion already uses. The check comes first in the refresh step, before Valkey's `refreshedAt` and `claim`, so an unknown code costs no Valkey command either. `JdbcAirportCatalogue` reads the `airport` table on every call. That table is migration V2's fixed 32 airports, with no runtime writes, so `SearchConfig` hands the use case the catalogue through Spring's `SingletonSupplier`, the framework's canonical lazy singleton. It reads the catalogue once, at the first search with an origin; a failed read is not kept, and the next search reads again. Ingestion keeps reading it per run. This read adds no failure mode: `reader.state()` reads PostgreSQL first on every search, so a search reaches the catalogue only while PostgreSQL answers.
- **F2 (Minor, fixed: one case):** `flagsACursorOfAnotherGeneration` gains a page read from the next generation while the state still names this one (a run publishing between the two reads), with a cursor of that next generation: not flagged.
- **C3 (Minor, fixed: one test):** `FlightAggregatorApplicationTest.holdsOneRefreshScheduler` counts the application's `RefreshScheduler` beans. Two unmarked ones already stop startup (`refusesToStartWithTheProvisionalSchedulerBesideAnother`); this also fails when either is marked `@Primary` to out-vote the other. `RefreshConfig`'s Javadoc says so. Task 7's brief should still say to delete the provisional bean.
- **C4 (informational):** no change. `limit` stays validated at the endpoint, as the user decided in Task 4; Task 9's `rejectsEachInvalidParameter` keeps the `limit 0` case.

Cycles:

- `SearchItinerariesServiceTest.asksTheReaderForTheQuerysFiltersAfterNowWithOneRowMore` (Origin MAD, Destination LHR, Max Price 200.00, Carrier IB, by duration, limit 2): passed on first run. The reviewer's mutation M1, "the reader asked with empty criteria, the sort kept": `Expecting actual: [PageAsked[criteria=SearchCriteria[origin=Optional.empty, destination=Optional.empty, maxPrice=Optional.empty, carrier=Optional.empty, sort=DURATION], after=Optional.empty, limit=3, now=2026-11-20T08:00:00Z]] to contain exactly ...`. M2, "`Instant.EPOCH` instead of `now`": the reader saw `limit=3, now=1970-01-01T00:00:00Z`.
- `dropsFromACachedPageWhatHasDepartedSince` (a first page by departure from Madrid, cached 3 min ago with flights 11 at 07:59, 12 at 08:30 and 13 at 09:00, and a cursor after 13; searched at 08:00): red = `expected: SearchPage[itineraries=[... flightNumber=12 ..., ... flightNumber=13 ...], ...] but was: SearchPage[itineraries=[... flightNumber=11 ...` (the departed flight served). Green = a cache hit keeps what `isSellable(now)`, with the cached cursor and flag.
- The catalogue as a constructor argument, with the application test's catalogue (MAD, LHR, BCN, as `IngestInventoryServiceTest`'s) and `SearchConfig` passing the bean: a signature change only, the suite green.
- `doesNotRefreshAnOriginOutsideTheCatalogue` (QQQ, published 11 min ago): red = `Expecting empty but was: [Airport[code=QQQ]]`. Green = the catalogue checked before staleness and the claim. `SearchConfig`'s new argument had no red of its own, being needed to compile. Mutation "an empty catalogue wired" (`Set::of`): `SearchConfigTest.asksToRefreshAnOriginOnlyOnceItIsMoreThanTenMinutesOld` case `PT10M1S` = `Expected size: 1 but was: 0 in: []`.
- `SearchConfigTest.readsTheAirportCatalogueOnce` (two searches from Madrid; the catalogue counts its reads): red = `Expecting AtomicInteger(2) to have value: 1 but did not.` Green = `SingletonSupplier.of(airports::airports)`, its `obtain` passed to the use case.
- `flagsACursorOfAnotherGeneration`, the F2 case: passed on first run. The reviewer's mutation M4, "the flag compared with the state's generation": the page read while a run publishes came back `inventoryChanged=true`.
- `FlightAggregatorApplicationTest.holdsOneRefreshScheduler`: passed on first run (18 of 18). Mutation "a second scheduler marked `@Primary` in `RefreshConfig`" (startup goes on): `Expected size: 1 but was: 2`.

Gates: `./gradlew build` green (exit 0, Spotless and the coverage gate included):
- `application` 81 (3 new);
- `bootstrap` 118, with `FlightAggregatorApplicationTest` 18 of 18 and `SearchConfigTest` 5;
- `adapters/persistence` 103, `adapters/providers` 197, `domain` 91, architecture 39, `mock-gds` 201;
- none failed.

Thermo-nuclear review of the round's diff:
- The service gains one argument, one method (`stillSellable`, three lines) and one condition at the head of the refresh step; `SearchConfig` gains one memoised argument.
- Existing mechanisms reused: `Itinerary.isSellable`, the `AirportCatalogue` port, Spring's `SingletonSupplier`.
- No file near 1,000 lines: the service 158, its test 406.
- Nothing is written half-way, and no new way to fail a search.

## Task 6 — fix round 2

Re-review (`task-6-rereview-1.md`): functional APPROVED, code CHANGES REQUESTED on one new finding, C5. The mock departs on 5-minute slots, so a cached first page by departure often loses every row at once, and round 1's filter then served it empty, with its cursor, for the rest of its TTL. **The user's decision of 2026-11-15** (recorded under Task 6 in the plan): a cached page that has lost any row to departure is a cache miss. It is read again from the reader and cached again, so a page is never served shorter or empty. This replaces round 1's C1 behaviour: a shortened page with the cached cursor, and nothing written back.

Design notes:

- On a cache hit, the page is served only if every row `isSellable(now)`. Otherwise it goes the way of a miss: read with `limit + 1` rows, served with the fresh cursor, and cached again under the same key and generation (when the page's generation is the key's, as on any miss). Writing it again resets its TTL. Round 1's `stillSellable` rewrite of the page is gone.
- Cost: one read from PostgreSQL per departure-sorted key per departure slot, about one every 5 minutes, as the reviewer estimated. Other sorts lose a row more rarely.
- Valkey's adapter counts that lookup as `search.cache{result=hit}`, since Valkey had the page, though the service reads PostgreSQL. The hit ratio (§6.2) is overstated by those lookups (see the extras).

Cycles:

- `SearchItinerariesServiceTest.readsAgainACachedPageThatHasLostARowToDeparture`, parameterised: one, or all three, of a cached first page's rows departed at 07:55, searched at 08:00 by departure from Madrid; the reader holds flights 21 to 24 from 08:05. It replaces round 1's `dropsFromACachedPageWhatHasDepartedSince`, whose expected page the user's decision supersedes. Red against round 1's code:
  - one departed: `but was: SearchPage[itineraries=[... flightNumber=12 ..., ... flightNumber=13 ...], ...]`, the shortened cached page;
  - all departed: `but was: SearchPage[itineraries=[], next=Optional[PageCursor[...]]`, the empty page of C5.

  Green = a cached page is served only when every row is still sellable, otherwise read and cached again. Mutation "`anyMatch` instead of `allMatch`" (only an all-departed page is a miss, the reviewer's option b): the one-departed case served the cached page with departed flight 11.
- F1 to C3's tests stay as they were, all green.

Gates: `./gradlew build` green (exit 0, Spotless and the coverage gate included):
- `application` 82, the replaced test now two cases;
- `bootstrap` 118, `adapters/persistence` 103, `adapters/providers` 197, `domain` 91, architecture 39, `mock-gds` 201;
- none failed.

Thermo-nuclear review of the round's diff:
- One rule replaces a page rewrite: a hit is a whole, still-sellable page, or it is a miss. The method that built a shortened page is deleted, and with it the asymmetry of a served page that the cache never held.
- No file near 1,000 lines: the service 156, its test 430.
- No new way to fail a search: the re-read is the miss path that already existed.

### Extras for the user to decide

- **Count a page re-read for a departed row as a miss in `search.cache`.** Today Valkey's adapter counts it as a hit. Value: the hit ratio §6.2 cites as evidence matches what PostgreSQL was spared. Cost: the cache port learns of the service's verdict (a method or a result type) and a test in each module. Left out unless you approve. *Superseded in fix round 3 by the user's decision on C6: no page outlives its first departure.*

## Task 6 — fix round 3

The re-review of round 2 (under "Fix round 2" in `task-6-rereview-1.md`) answered functional APPROVED and code CHANGES REQUESTED on C6: a stale page thrown away and read again still counted as `search.cache{result=hit}`, so a departure-sorted key read about 100% hits. **The user's decision of 2026-11-15** (recorded under Task 6 in the plan): Valkey's adapter caches a page for the shorter of `search.cache-ttl` and the time until its earliest departure, by the adapter's `Clock` (from Task 5's round 2). A cached page then never holds a departed flight, and a hit is always a usable page. The service's "any departed row is a miss" check stays as a safety net.

Design notes:

- `ValkeySearchCache.put` computes the time to live before writing: the earliest of the page's departures (`Itinerary.departure()`, the first leg's, the same instant `isSellable` compares), as time from `clock.now()`, truncated to the millisecond, Valkey's finest expiry unit. That time is used when it is shorter than `search.cache-ttl`; otherwise the TTL stands. At the departure instant the key expires and `isSellable` turns false, so the two rules agree.
- **A page whose first flight has left, leaves now, or leaves within a millisecond is not written at all.** No time to live keeps it without a departed flight. A 1 s floor would store a page holding a flight that departs, or has departed, within that second, which is the C6 problem again: a hit the service must throw away. Not writing costs nothing, since the search has already been served from the fresh read and the next one reads PostgreSQL. Writing anyway was also a latent defect, seen in the red run below:
  - Valkey refuses a negative expiry, and Spring sends a sub-second one as a `SETEX 0` it refuses too. `ValkeyFailures` takes that refusal for an outage, so the breaker opens and Valkey is skipped for 10 s.
  - Spring sends a zero duration as a plain `SET`, so the page would be kept with no expiry at all.
- An empty page has no departure to wait for and keeps `search.cache-ttl`.
- The serialisation is skipped when nothing is written. The port's `put` Javadoc states the contract the service relies on; the service's Javadoc calls its own check a safety net (for a cache that does not honour it, or instances whose clocks disagree).
- Test fixture: `ValkeySearchCacheTest`'s clock started at 2026-11-20T10:00Z, after its `MADRID_NEW_YORK` fixture departs (07:15Z that day). Under the new rule `roundTripsAPage` would no longer store that page. The start moved to 2026-11-19T10:00Z, before every fixture flight; the tests that move the clock are relative to it.

Cycles (`ValkeySearchCacheTest`, against the Valkey container, each TTL read with `PTTL`, no sleeping):

- `keepsAPageNoLongerThanUntilItsFirstDeparture` (a page whose second row leaves 90 s after the write, its first 3 min after): red = `Expecting actual: 299990L to be between: [88000L, 90000L]`. Green = the shorter of the cache's TTL and the time until the earliest departure.
- `keepsTheCacheTtlWhenTheFirstDepartureIsLater` (first flight 6 min away): passed on first run, since cycle 1's green keeps the TTL whenever it is sooner. Mutation "the time until departure always wins": `Expecting actual: 360000L to be between: [298000L, 300000L]` (and `expiresAfterItsTtl`: `154799L`).
- `keepsNoPageWhoseFirstFlightHasLeft` (parameterised: left a minute ago, leaving now, leaving in 0.5 ms; then another page is written and read back): red, each for its own reason:
  - `-PT1M` and `PT0.0005S` = `Expecting Optional to contain: SearchPage[...]` for the next page: Valkey refused the write, `ValkeyFailures` took it for an outage, and the open breaker dropped the next page;
  - `PT0S` = `Expecting value to be false but was true`: the page was stored, with no expiry.

  Green = the time truncated to the millisecond, and nothing written unless it is positive.
- `keepsAnEmptyPageForTheCacheTtl`: passed on first run. A first mutation, "no rows found, a time of zero", also hit every page whose first flight leaves after the TTL (nine failures), so it was replaced by a sharper one, "an empty page is not kept": this test alone failed, `Expecting actual: -2L to be between: [298000L, 300000L]`.
- The other tests stay as they were: the service's safety-net test (round 2), and everything F1 to C5 added, all green.

Gates: `./gradlew build` green (exit 0, Spotless and the coverage gate included):
- `adapters/persistence` 109 (6 new cases);
- `application` 82, `bootstrap` 118, `adapters/providers` 197, `domain` 91, architecture 39, `mock-gds` 201;
- none failed.

Thermo-nuclear review of the round's diff:
- The rule lives where the time to live is set, in the adapter, as one pure method, before the write.
- No branch is added to the service; its check only gains a sentence of Javadoc.
- The adapter's existing `Clock` port is used, and no file is near 1,000 lines (the adapter 133, its test 376).
- The write is one command or none: nothing written half-way, and no new way for a search to fail.

## Approved extras (Tasks 4 and 8, git guard)

The extras the user approved on 2026-11-15 (plan 5, Tasks 4 and 8; the git guard), built in one worktree, one commit each. One test at a time; a test that passed on its first run is shown red by a labelled mutation of the production code, which was then restored.

### Task 4: a 2 s statement timeout on every search read

Design notes:

- **A constant, as the stores' bound is.** The stores' `StatementTimeout` is a constant (`SET LOCAL statement_timeout = '5min'`), not a setting, so the reader's 2 s is a constant too, as the brief says for that case; `SearchProperties` and `application.yaml` are untouched. `StatementTimeout` became an enum with two bounds, `STORE` (5 min, the stores' writes, unchanged) and `SEARCH_READ` (2 s), and one `boundTheTransaction(JdbcClient)`; the three store call sites name `STORE`.
- Each of the reader's three reads (`state`, `page`, `byId`) runs through one private `read(...)`: the read-only `REPEATABLE READ` template, whose first statement is `StatementTimeout.SEARCH_READ.boundTheTransaction(jdbc)`. `SET` takes no snapshot, so the generation and the rows of a page are still one snapshot (`readsTheGenerationAndItsRowsInOneSnapshot` stays green).
- **How a timeout surfaces:** PostgreSQL cancels the statement with SQLState `57014` (`canceling statement due to statement timeout`), Spring translates it into `org.springframework.dao.QueryTimeoutException` (a `TransientDataAccessException`), and the template rolls the read back before the exception reaches the caller. The web layer can map that type; no mapping is built here.
- The page's keyset statement is the only one that locks the slot (`ACCESS SHARE`), so a read holds that lock for 2 s at most, less than the 5 s `lock_timeout` a run's `TRUNCATE` of that slot waits for.

Cycles:

- `JdbcItineraryReaderTest.cutsAReadThatTakesLongerThanItsBound`. Set-up: one publication; a second connection holds `LOCK TABLE itinerary_b IN ACCESS EXCLUSIVE MODE`, so the page's keyset statement waits; the read runs under `assertTimeoutPreemptively(10 s)`. Red: `AssertionFailedError: execution timed out after 10000 ms` (nothing cut the read). Green: `SET LOCAL statement_timeout = '2s'` at the start of `page()`'s transaction; the test takes 2.3 s. It asserts a `QueryTimeoutException` whose most specific cause has SQLState `57014` and says `statement timeout`, and that no other client backend runs a statement or holds a transaction open.
  - The "no transaction left open" check first counted backends with `xact_start` set, and a mutation showed it vacuous: with "the read opens its transaction by hand and never rolls it back on failure", the test stayed green, because PostgreSQL clears `xact_start` once a statement aborts the transaction. The check now counts backends whose `state` is not `idle`; the same mutation then failed: `expected: 0L but was: 1L` (the backend left `idle in transaction (aborted)`).
- `boundsEachReadToTwoSeconds`, one case per read, each read run inside an enclosing transaction of the same data source and `SHOW statement_timeout` read before its commit, then `0` after it. Red: `[1] state` and `[3] byId`: `expected: "2s" but was: "0"`; `[2] page` passed. Green: all three reads go through `read(...)`, which bounds the transaction. `[2] page` passed on its first run; mutation "the bound is `3s`": all three cases `expected: "2s" but was: "3s"`, while `cutsAReadThatTakesLongerThanItsBound` stayed green, which is why the value needs its own pin.
- Refactor under green: the literal `SET LOCAL` became `StatementTimeout.SEARCH_READ`, and the stores' calls `StatementTimeout.STORE` (their pins, `boundsEachStatementOfTheLoad`, `boundsTheTransactionOfAnUpdate` and the offer store's, stay green at `5min`).

### Task 4: defensive copies in `StoredPage` and `SearchPage`

Each record copies its list in a compact constructor with `List.copyOf`, as `IngestionReport` does; a null list or a null itinerary fails at construction, as there.

- `StoredPageTest.copiesTheItineraries` (a mutable list given to the page, then `null` added to it). Red: `Expecting empty but was: [null]`. Green: `StoredPage`'s compact constructor.
- `SearchPageTest.copiesTheItineraries` (the same, with no cursor). Red: `Expecting empty but was: [null]`. Green: `SearchPage`'s compact constructor.
- Refactor under green: `SearchPageJson.read` copied its list before building the page; the record copies it now, so that copy was removed.

### Task 8: two re-prices of the same itinerary at once

- `JdbcItineraryRepositoryTest.keepsTheFirstOfTwoRepricesOfOneItinerary`, next to `storesTwoRepricesAtOnce`. Set-up: one itinerary published; the first update (`HeldUpdate`, re-quoted at 120.00) is held open after its write; the second (130.00) starts on another thread, and the test waits through `pg_stat_activity` until it is blocked by the first's backend (`awaitAStatementBlockedBy`), then releases the first. The first answers `STORED`, the second `GONE`, and slot b holds one row of that id at 126.00, the first's quote. No sleeps: the threads are coordinated by the held transaction and the blocked statement. It passed on its first run, as the user's re-review had found in a container; stable over five reruns of the class. Two mutations of `rewrite`, each restored:
  - M1, "a rewrite that deleted nothing still copies its quote back and answers `STORED`" (the `deleted == 0` check removed): `ExecutionException: ... UncategorizedSQLException: End COPY; ... SQL state [23505]; ... duplicate key value violates unique constraint`.
  - M2, "the rewrite updates the row in place" (`UPDATE <slot> SET price = ? WHERE id = ? AND departure = ?` instead of the delete and the copy, so the second follows the row the first rewrote): `expected: GONE but was: STORED`. `storesTwoRepricesAtOnce` stayed green under M2, so only the new test pins "the first re-price wins".

### The git guard: each pattern reads one simple command

Each pattern's `.*` spanned the whole command line, so a plain push followed by another command with a `+` (`git push -q origin main && date +%H:%M`), or a heredoc whose text mentions a push and later holds a `+`, was blocked as a force push. This session's own agent hit it once: a scratch note written through a shell heredoc was refused by the live guard.

Design notes:

- **`IN_COMMAND`** replaces every `.*`: any text but `;`, `|` (so `||` too) and `&&`, with a double- or single-quoted string taken whole, so a separator inside quotes does not end the command (a quote can only open a string, never stand alone). A lone `&`, as in `2>&1`, does not end it. The unquoted values of `git`'s global options (`-C <dir>`, `--opt=<value>`) end at `;`, `&` or `|` too, so the prefix cannot cross a command either.
- **Line breaks.** The hook used to join every line before matching. Line breaks are now kept, and grep, which reads one line at a time, never matches across one. Two joins stay: a backslash continuation, and a line that starts with an option, which cannot be a command of its own and is read as part of the line before. The second is needed to keep two existing cases blocked, `git push origin main` with `--force` on the next line and `git reset` with `--hard` on the next line (see C5): the brief asks for no crossing of a line break and for every existing case to stay blocked, and this rule meets both. In bash those two inputs are each two commands, the second failing as "command not found", so the rule only ever blocks more.
- Without `jq`, the raw JSON's `\n` becomes a real line break (and `\\` + `\n`, a continuation, a space), only in that mode, so a literal `\n` in a command read by `jq` is not split.
- The suite passes under `/bin/bash` 3.2 (the shebang on macOS) and bash 5.3 with macOS's BSD grep 2.6.0, and under bash 5.2 with GNU grep 3.11 (what Git Bash on Windows uses) in a throwaway `ubuntu:24.04` container. Timing on large commands (scratch script, not committed), with `/bin/bash`: a 300-line heredoc takes 0.27 s with `jq` (4.3 s before the change, when it was one joined line), a 1,000-line one 2.1 s (over 150 s before). Without `jq`, the 300-line heredoc takes 14 s, as before the change: that cost is the raw path's, unchanged here (see the extras below).
- Neither CI nor `./gradlew build` runs the hook's tests: CI's jobs run `./gradlew build`, the Docker image builds and the frontend's `npm run verify`, and no Gradle build file refers to `.claude/hooks`. They run by hand: `bash .claude/hooks/block-destructive-git.test.sh`.
- `docs/ai/README.md`'s Hook passage now says how a command is read.

Cycles, one case at a time; every red failed with `jq` and without it:

- C1, allowed `git push -q origin main && date +%H:%M`. Red: `FAIL: expected exit 0, got 2`. Green: the force-push pattern's `.*` became a span that does not cross `&&`.
- C2, allowed `git push origin main; date +%H:%M`. Red: `expected exit 0, got 2`. Green: the span stops at `;` too.
- C3, allowed `git push origin main 2>&1 | grep -F +main`. Red: `expected exit 0, got 2`. Green: the span stops at `|`; the `2>&1` in the case pins that a lone `&` does not.
- C4, allowed `git push origin main || date +%H:%M`. Passed on its first run (the excluded `|` covers it). Mutation "`||` read as part of the command" (an alternative `\|\|` in the span): `expected exit 0, got 2`.
- C5, allowed: a heredoc (`cat > notes.txt <<'EOF'`, then `Run git push origin main`, `then date +%H:%M`, `EOF`). Red: `expected exit 0, got 2`. Green, first step: line breaks kept. That turned two existing cases red, `git push origin main` + line break + `--force` and `git reset` + line break + `--hard`: `expected exit 2, got 0`. Green: a line that starts with an option joins the line before.
- C6, blocked `git push -o "merge_request.title=Fix; tidy" --force`. Red: `expected exit 2, got 0` (the span stopped at the quoted `;`, a weakening). Green: a double-quoted string, `\"` in the raw JSON, is taken whole.
- C7, blocked `git push -o 'merge_request.title=Fix | tidy' --force`. Red: `expected exit 2, got 0`. Green: a single-quoted string is taken whole.
- C8 to C14, allowed, one per remaining pattern, each red with `expected exit 0, got 2` and green once that pattern's `.*` became the span: `git push origin --delete feature/x && git checkout main` (deleting push), `git reset --soft HEAD~1 && grep -n -- --hard notes.txt` (reset), `git clean -n; rm -f build.log` (clean), `git branch --show-current && ./gradlew test -Dorg.gradle.debug=false` (`branch -D`), `git branch -d merged-branch && rm -f merged.patch` (`branch` delete and force), `git update-ref refs/heads/backup HEAD; git show-ref -d refs/heads/main` (update-ref), `gh api -X DELETE repos/o/r/git/refs/heads/feature/x && git log -1 refs/heads/main` (`gh api`).
- C15, allowed `git -C repo;echo push -f`. Red: `expected exit 0, got 2`. Green: an unquoted option value ends at `;`, `&` or `|`. C16, allowed `git --git-dir=.git&&echo push -f`. Red: the same. Green: the same for a `--flag=value`.
- C17 to C21, blocked, the brief's cases, added one at a time, each passing on its first run: `git push -f`, `echo a; git push origin +main`, `echo a; git push -f`, `cd x && git push origin --delete main`, `cd x && git push origin :main` (`git push origin +main` alone was already a case). Two mutations:
  - MA, "every pattern anchored at the start of a line" (`grep -qE "^$pattern"`, a tempting way to keep a pattern within one command): with `jq`, the four cases after `echo a;` or `cd x &&` gave `expected exit 2, got 0`, as did the existing `git pull --rebase && git push --force`; without `jq`, 44 cases failed, since the raw JSON's line starts with `"command":`.
  - MB, "the span holds at least one character" (`(…)+` instead of `(…)*`): `git push -f` and `echo a; git push -f` gave `expected exit 2, got 0`, with 15 existing cases (13 force pushes, `git push --delete origin main` and a `gh api` delete).

Every existing case still passes (corrected in fix round 1: the suite's cases did, but the review found commands outside the suite that `6db5049` blocked and this version let through; see "Approved extras — fix round 1"). Self-review (thermo-nuclear): one span definition used by every pattern instead of a per-pattern fix; the join section shrank (two raw-only substitutions moved into the no-`jq` branch); no new branch in the matching loop. The hook is 71 lines, its tests 118.

### Extras for the user to decide

- **The raw path's cost on large commands.** Without `jq` (Git for Windows), a 300-line heredoc takes about 14 s in the hook, before and after this change, mostly in bash's substitutions over the whole raw JSON. Value: no long pause on Windows when an agent writes a file through a heredoc. Cost: a different way to read the command without `jq` (for example a single `sed` pass), and a timing check. Left out unless you approve.
- **A guard that fails closed.** If grep refuses a pattern (exit status 2, a regex its version does not accept), the loop reads it as "no match" and the command is allowed, silently; this predates the change. Value: a grep incompatibility on some machine blocks commands instead of letting every one through. Cost: one check of grep's exit status and a test. Left out unless you approve.
- **Running the hook's tests in CI.** Neither CI nor `./gradlew build` runs them, so a change to the guard is checked only when someone runs them by hand. Value: a broken guard fails CI. Cost: one step in the `backend` job (`bash .claude/hooks/block-destructive-git.test.sh`; the runner has `jq`). Left out unless you approve.

## Approved extras — fix round 1

The double review of the four commits answered CHANGES REQUESTED on both verdicts (`.superpowers/sdd/2026-11-15-search-api/extras-review.md`): the read timeout, the copies and the race test pass; the findings are in the git guard (F1, C1, C2, C6) and in three timeout pins (C3), plus two Javadoc notes (C4, C5) and the report (F2). The user decided the open newline rule (plan 5 Task 8, `647927b`): every newline ends a command except an explicit continuation, `\` in bash and a backtick in PowerShell; the rule that joined a line starting with an option to the line before is dropped. The branch was first rebased on `main` (`14bfe0b`); the only conflict was textual, in this file, and both sections were kept, `main`'s first. One case or one test at a time; a case that passed on its first run is shown red by a labelled mutation, then restored. Every red failed with `jq` and without it unless the line says otherwise.

### The git guard (F1, C1, C2, C6)

- G1, blocked `gh api -X DELETE "repos/o/r/git/refs/heads/main"`. Red: `FAIL: expected exit 2, got 0`. Cause (C1): the span excluded both quote characters, so a quote could only open a balanced string, and `refs/heads/main` inside one quoted word could never be reached. Green: the reviewer's span, where a quote can also be read as a plain character, so quoting the regex cannot pair as written never hides a destructive token; where the reading is ambiguous the guard blocks.
- G2 to G5, blocked, one at a time, each passing on its first run: `gh api -X DELETE 'repos/{owner}/{repo}/git/refs/heads/main'`, `gh api "repos/o/r/git/refs/heads/main" --method DELETE`, `git push -o it\'s --force`, `git push -o "a\"b" --force`. Mutation MC, "a quote is never a plain character" (the span of the commit under review): G1 to G5 all `expected exit 2, got 0`.
- G6 (C6), blocked `git push -o a\;b --force` (bash passes `a;b` as one word). Red: `expected exit 2, got 0`. Green: an escaped separator, a backslash before `;`, `|` or `&` (two in the raw JSON), is part of the span. Refactor under green: the double- and single-quoted strings are named once (`DOUBLE_QUOTED`, `SINGLE_QUOTED`) and used by both the option value and the span, instead of two copies of the same escaped regex.
- G7 (C2), blocked `git push origin` + backtick, then `  +main` on the next line (PowerShell's continuation). Red: `expected exit 2, got 0`. Green: a backtick after a space at the end of a line joins it to the next, as the backslash does. G8 (`:main` on the continued line) and G9 (`git reset` + backtick, then `  HEAD~3 --hard`) passed on their first run; mutation MD, "no backtick join": G7 to G9 `expected exit 2, got 0`.
- G10, allowed: a heredoc whose Markdown list names a push in bullet 1 and `+5 min` in bullet 3 (the reviewer's F2). Red: `expected exit 0, got 2`, because the option-line rule chained every `- ` line onto the one above. Green: the rule removed, as the user decided. That turned the two cases it existed for red, `git push origin main` + line break + `--force` and `git reset` + line break + `--hard` (`expected exit 2, got 0`): in bash each is two commands, the second failing as "command not found", and the user's decision makes them allowed, so both moved to the allowed block as `check 0`, with a comment naming the decision.
- G11, blocked `git push origin main` + backtick, then `--force` on the next line: until now only the option-line rule blocked it (the reviewer's note). It passed on its first run, through the backtick join; mutation MD: `expected exit 2, got 0`.
- G12, blocked `git reset` + backslash, then a tab and `--hard`. Red without `jq` only: `expected exit 2, got 0 without jq`, because the raw JSON spells the tab as the two characters `\t`. Green: in the raw branch, `\t` becomes a real tab, as `\n` becomes a line break.
- G13, allowed: a heredoc line that ends with a Markdown code span (`` Push with `git push origin main` ``) followed by `then wait +5 min`. It passed on its first run; mutation ME, "any backtick at the end of a line continues it": `expected exit 0, got 2`. It pins that only a backtick after a space is a continuation.
- The suite passes under bash 5.3 and `/bin/bash` 3.2 with BSD grep 2.6.0, and under bash 5.2 with GNU grep 3.11 in an `ubuntu:24.04` container.
- **Differential probe** (scratch, not committed): 68 destructive commands in many spellings (quoted and escaped arguments, global options, separators before and after, redirections, continuations in both shells), each through the hook at `6db5049` and this one, with and without `jq`. Every command the old hook blocked is blocked, except one: `git push -o "line1` + line break + `line2" --force`, a line break inside a quoted argument, which the old hook blocked by joining every line and this one reads as two commands, in both modes. That follows the user's decision as written ("every newline ends a command"), and quote-aware joining would bring back the heredoc false positives (an apostrophe in one line pairing with one several lines down). It is listed for the user below and stated in `docs/ai/README.md`. Two commands are now blocked that the old hook allowed without `jq`: tab-indented continuations (G12).
- Known false positive of the new span, in the safe direction (the reviewer's): quotes on both sides of a separator can pair across it, so `git push origin 'main' && echo 'x +1'` is blocked.
- `docs/ai/README.md`'s hook passage now says what the guard reads: one simple command, a separator inside quotes or escaped not ending it, an unpaired quote read as a plain character, only explicit continuations joining lines, and the multi-line quoted argument it does not see.

### The timeout pins (C3)

The "gone after the commit" checks read `SHOW statement_timeout` through `PostgresTestDatabase`'s `PGSimpleDataSource`, which opens a new session per connection, so they always read `0`. Reproduced: with `SET LOCAL statement_timeout` mutated to `SET statement_timeout` (which would leave the bound on a pooled connection), `boundsEachReadToTwoSeconds` [1] to [3], `boundsTheTransactionOfAnUpdate` [1] and `boundsEachStatementOfTheLoad` all stayed green.

- New test support `support/OneSession`: a `SingleConnectionDataSource` over one connection of the test database, with close suppressed, so a transaction and the statements after its commit run in one session; `show(setting)` reads the setting in that session.
- The three pins build their reader or repository, their enclosing transaction and both `SHOW`s on one `OneSession`. Under the same mutation: `boundsEachReadToTwoSeconds` [1] state, [2] page, [3] byId `expected: "0" but was: "2s"`; `boundsTheTransactionOfAnUpdate` [1] and `boundsEachStatementOfTheLoad` `expected: "0" but was: "5min"`. Mutation "`SET LOCAL lock_timeout` → `SET lock_timeout`" in the repository: `boundsTheTransactionOfAnUpdate` [2] `expected: "0" but was: "5s"`. Both mutations restored; the module is green.

### Javadoc (C4, C5)

- C5: `JdbcItineraryReader` says a read must not run inside another transaction: it would join it, lose its own snapshot, and bound the rest of that transaction's statements at 2 s.
- C4: `readsTheGenerationAndItsRowsInOneSnapshot` says its page's lock wait counts toward the 2 s bound, a constant, so a 2 s stall on a loaded machine would fail it. Accepted: the refresh commits within milliseconds of seeing the page wait.

### Report (F2)

The implementer's report described the option-line rule's cost as one bullet line; consecutive `- ` lines chained, so a whole Markdown or YAML list in a heredoc became one command with the line above it. The report now says so, and that the user removed the rule.

### Self-review (thermo-nuclear) and gates

- One span still serves every pattern; the quoted strings are defined once and shared by the option value and the span; the join section is two substitutions for two explicit continuations, and the special case it had (option lines) is gone. No new branch in the matching loop.
- `OneSession` is a test utility, not a production method; it replaces nothing in production and fixes the three pins with one helper.
- Sizes: the hook 78 lines, its tests 136; `JdbcItineraryRepositoryTest` 771, `JdbcItineraryReaderTest` 420, `OneSession` 40.

### Extras for the user to decide

- **A line break inside a quoted argument.** The guard reads it as the end of the command, so `git push -o "line1` + line break + `line2" --force` is allowed (the hook at `6db5049` blocked it by joining every line). Value: no fail-open on a multi-line quoted argument before a destructive option. Cost: quote-aware joining, which brings back false positives on heredoc text with apostrophes on different lines, or a small quote-tracking pass in bash, with tests. Left out unless you approve.
- **Quoted or reordered targets** (the reviewer's note, pre-existing): `git push origin --delete "main"`, `git push origin "+main"`, `git reset "--hard"`, `git update-ref -d "refs/heads/main"` and `git push origin main --delete` are allowed before and after this change, because those patterns need a space right before the token. Value: the guard covers quoted spellings too. Cost: an optional quote before each token, and cases. Left out unless you approve.

## Approved extras — fix round 2

The re-review of fix round 1 (`.superpowers/sdd/2026-11-15-search-api/extras-review.md`, "Fix round 1") answered APPROVED on both verdicts, with three Minors that are one problem: two spellings that `6db5049` blocked still passed (R1, R2). Both are fixed here with the reviewer's decoding change, checked first on his scratch copy. One case at a time, each red first; every mutation was restored.

- R1, blocked `git push origin` + backtick, then a CRLF line ending and `  +main` on the next line. Red: `FAIL: expected exit 2, got 0`, with `jq` and without it: the `\r` sat between the backtick and the line break, so PowerShell's continuation was not joined. Green: a CRLF becomes a line break before the joins, and the raw JSON's `\r\n` is decoded as one. Mutation MF, "no CRLF normalisation before the joins": `expected exit 2, got 0 with jq`. Mutation MG, "the raw branch does not decode `\r\n`": `expected exit 2, got 0 without jq`. So each line is needed, one per mode.
- R2, blocked `git -C C:\tools\repo reset --hard`. Red without `jq` only: `expected exit 2, got 0 without jq`. The raw JSON's escaped backslash before `t` (`\\t`) was decoded as a tab, which ended the `-C` value. Green: the raw JSON's escapes are decoded in order. An escaped backslash is set aside first (as `\001`; JSON never holds a raw control character), then `\r\n` and `\n` become a line break and `\t` a tab, then each escaped backslash becomes one backslash. This also replaced the special substitution for an escaped backslash before `\n` (a continuation), which now reaches the common join as a backslash before a line break. Checked first in a scratch script: `C:\tools\repo`, a line break, a tab and a continuation decode the same under `/bin/bash` 3.2 and bash 5.3.
- The suite passes under `/bin/bash` 3.2 and bash 5.3 with BSD grep, and under bash 5.2 with GNU grep 3.11 in an `ubuntu:24.04` container; every case runs with `jq` and without it.
- **Differential probe** (scratch, not committed): 83 commands through the hook at `6db5049` and this one, with and without `jq`. The 68 commands of round 1, plus CRLF continuations in both shells, Windows paths (`C:\tools`, `C:\temp`, `C:\new`, `C:\rel`, quoted and unquoted), an escaped `&`, a multi-line quoted `gh api` field, and the heredoc lists.
  - Every command the old hook blocked is blocked, except the undecided multi-line quoted argument (`git push -o "line1` + line break + `line2" --force`, and `gh api -X DELETE -f note="a` + line break + `b" …/refs/heads/main`), left as it is for the user.
  - The rest of the differences are those the user decided: the Markdown and YAML list heredocs, and `--force` alone on the next line.
  - It blocks more than the old hook in two shapes: tab-indented continuations without `jq`, and `git -C C:\new\repo reset --hard`, which the old hook let through with `jq` and without.
- A false positive in the safe direction, noted by the reviewer and unchanged: `ESCAPED_SEPARATOR` also reads a literal backslash before a real separator as escaped, so `git push origin main\\; date +%H:%M` is blocked.
- Gates: `./gradlew build --rerun-tasks` green, exit 0, 832 tests, none failed. No Java changed in this round.
- Self-review (thermo-nuclear): the raw branch now decodes the JSON in one ordered pass instead of piecemeal substitutions, and its special continuation substitution is gone; the common part gains one line (CRLF). No new branch in the matching loop. The hook is 84 lines, its tests 139.

## Task 9 — evidence

The search endpoint, its opaque cursor and strict parameters, and the committed OpenAPI contract with its drift test (moved here from Task 13 by the user's decision). One test at a time, in the order of the plan's Steps; a test green on its first run is shown red by a labelled mutation of the production code, then restored.

**Version (§6.1).** `org.springdoc:springdoc-openapi-starter-webmvc-api` **3.2.0**, outside Boot's BOM. Checked on 2026-11-15 against Maven Central:
- `maven-metadata.xml` gives `<release>3.2.0</release>`; the list ends `3.1.0, 3.1.1, 3.2.0`, with no newer milestone or release candidate.
- The 3.2.0 POM's `Last-Modified` is Sat, 24 Oct 2026 14:13:30 GMT: 22 days old, so past the 14-day rule.
- Its parent, `org.springdoc:springdoc-openapi:3.2.0`, is built on `spring-boot-starter-parent` **4.1.2**, our Boot, and declares swagger-core 2.2.55.
- The Jackson 2 that swagger-core needs resolves to 2.21.7, constrained by the Boot BOM (`./gradlew :bootstrap:dependencies`).

Design notes:
- **Files as the plan lists them, with three additions:**
  - `InvalidParameterException`: the web adapter's own parameter error. The advice maps it, so no domain `IllegalArgumentException` is mapped to 400 (global constraints).
  - `bootstrap/.../config/WebConfig.java`: `@Import`s the controller and the advice. The adapter's package is not scanned, as for the stores in `PersistenceConfig`. The plan gives Task 9 no wiring, but the endpoint must exist in the application for the contract and for the anonymous-access check. Task 10's plan says "Create `WebConfig.java`"; it now modifies it.
  - Test fixtures `RecordingSearch` (a fake `SearchItineraries`) and `Cursors`.
- **Dependencies of `adapters/web`:**
  - `spring-web`, not `spring-webmvc`, and no `jackson-databind`: the code uses only spring-web's annotations and `ProblemDetail`, and Boot's converter writes the records.
  - springdoc is an `api` dependency (the module applies `java-library`). Its annotations are on the controller that bootstrap's `WebConfig` names, and javac failed on `-Xlint:classfile` without them (`Cannot find annotation method 'responseCode()' in type 'ApiResponse'`).
  - Tests use `spring-boot-starter-webmvc-test`.
- **Named `@RequestParam`s, not one bound object.** Spring 7's `ExtendedServletRequestDataBinder` binds request headers into a model attribute, filtering only a few names (`origin` among them). A `Destination`, `Sort` or `Limit` header would set a filter.
- **`SearchParameters`:**
  - Each value must match its pattern in full, ASCII only, before it is upper-cased, so `ıst` (dotless i, which upper-cases to `IST`) is refused.
  - An empty value (`origin=`) is a 400, and so is a repeated one (`origin=MAD&origin=BCN` reaches the parser as `MAD,BCN`).
  - After the patterns, no domain constructor can throw.
  - `sort` takes exactly `price`, `departure` or `duration`. `limit` takes 1 to 100 (Q9, default 20).
- **`CursorCodec`** writes the plan's format. Decoding accepts only what encoding writes:
  - the base64url alphabet without padding, at most 200 characters;
  - then each field in its own form: version `1`; generation and duration up to 19 digits, with an overflow refused; the sort order by name; the price with its two decimals; the departure as `Instant.toString()` writes it in the years 1000 to 9999; the id as a canonical lower-case UUID (`UUID.fromString` alone accepts `1-1-1-1-1`).
  - So a forged cursor can seek only within public data (E3, signing, was rejected), and it cannot bring PostgreSQL a timestamp it would refuse, which would be a 500 (Review Focus 2).
- **Responses:**
  - The fields are the plan's. `Price` and `Leg` are records nested in `ItineraryResponse`, and the contract's schemas are `Price` and `Leg`.
  - `id` is a UUID. Times are `OffsetDateTime` at the provider's offset. `quotedAt` is an instant in UTC. `durationMinutes` is an int64. There is no provider and no leg price.
- **Problems.** Spring's `ProblemDetail`, returned from a `@RestControllerAdvice`, is written as `application/problem+json` with `instance` set to the path. Only the two web errors are mapped: any other exception stays Boot's 500.
- **The contract:**
  - springdoc 3 serves OpenAPI 3.1. `springdoc.writer-with-order-by-keys: true` (`application.yaml`) orders its keys, and the test pretty-prints it with two spaces and LF on every platform.
  - Every field of the search's answer is `required`, and only `nextCursor` is nullable (`"type": ["string", "null"]`).
  - The 400 is documented on the operation with `@ApiResponse`, not taken from the advice. Otherwise the 404, 409, 429 and 503 handlers that Tasks 10 and 12 add to the shared advice would be documented on every operation.
  - The parameters are documented with the parser's own constants (patterns, default sort, default and largest page size, cursor length).
- **The write path.** `bootstrap/build.gradle.kts` forwards `-Dopenapi.write=true` to the test JVM, and the committed file is a test input.
  - A write run is never taken from the cache or up to date. Checked: two write runs in a row both executed, and a deleted file was written again byte for byte.
  - Editing only the committed file reruns the test (mutation below).
- **Security** (`SecurityConfig` unchanged):
  - `GET /api/itineraries` answers an anonymous client 200 on a real server (`PublicAccessOverHttpTest`).
  - `/v3/api-docs` answers 200 through the filter chain (`OpenApiContractTest`).
  - A throwaway check, not committed, confirmed that nothing else opened: `/v3/api-docs.yaml`, `/v3/api-docs/swagger-config` and `/swagger-ui.html` answer 401, and so does an anonymous `POST /api/itineraries`.
- **A search read that times out** (the parallel branch's 2 s statement timeout, Spring's `QueryTimeoutException`) is not mapped. A throwaway experiment, not committed, used a real server and a `SearchItineraries` that throws it. It answers **500** with Boot's error body as `application/json`: `{"timestamp":…,"status":500,"error":"Internal Server Error","path":"/api/itineraries"}`. It is not a problem, and Tomcat logs the stack trace at ERROR. A mapping is listed below as an extra.

Cycles:

- `CursorCodecTest.roundTrips` (parameterised over the three sort orders, one departure with milliseconds; checks the plan's text exactly, the base64url alphabet, and decode(encode)):
  - red, all 3 cases = `java.lang.UnsupportedOperationException` (stub);
  - green = encode, and a decoder that splits the fields.
- `rejectsGarbageTamperedAndOverlongCursors` (Review Focus 2, 15 cases, each expecting an `InvalidCursorException` that names the cursor):
  - red, all 15 cases. The minimal decoder accepted padding, another version, `1E+3`, a year `+100000`, the id `1-1-1-1-1` and an extra field. It threw the wrong exception for the rest: `Illegal base64 character 20`, `ArrayIndexOutOfBoundsException`, `No enum constant ...SortOrder.BEST`, `Money cannot be negative`, `DateTimeParseException` (month 13), `NumberFormatException` (generation overflow, a missing field) and `Last unit does not have enough valid bits`.
  - green = the alphabet-and-length gate, the strict field pattern, and both parse failures caught.
  - Two cases were added after green: "a length base64 cannot have" (`AAAAA`) and "the year 0". They passed, and were shown red by mutation:
    - M1 "no catch of a base64 failure": `AAAAA` failed with `IllegalArgumentException`;
    - M2 "the year from 0000": "the year 0" was accepted.
  - Further mutations:
    - M3 "no alphabet gate": "padded" was accepted.
    - M4 "no length bound" left the test **green**. The fields' own forms keep any accepted cursor under 180 characters, so the format rejects a 201-character cursor anyway. The 200 bound only spares decoding a long input, and no test can tell it apart. It stays because the plan names it.
- `SearchParametersTest.normalisesCodesAndAmounts`: red = `expected: SearchQuery[... MAD ... JFK ... 100.00 ... IB ... PRICE], limit=20 ...] but was: null`. Green = upper-cased codes, `Money.euros`, sort `PRICE` and limit 20 by default.
- `rejectsEachInvalidParameter` (19 cases: the plan's `MADR`, `1e3`, `-1`, `0`, `IBX`, `best`, `limit` 0 and 101, plus `M4D`, empty, `ıst`, `0.00`, `100.999`, `1234567890`, `I`, `PRICE`, `-1` and `twenty` as limits):
  - red, all 19 cases. The domain's `IllegalArgumentException` leaked (for example `Airport code must be three upper-case letters: MADR`), or the value was accepted (`ıst`, `1e3`, `0`, `best`, every limit).
  - green = a pattern per parameter, the zero check, the sort table and the limit range, each failure an `InvalidParameterException` whose message starts with the parameter's name.
  - Mutations after the refactor below: M10 "zero accepted" failed `0` and `0.00`; M11 "no upper bound" failed `101`.
- `readsTheSortTheLimitAndTheCursor` (beyond the plan's list, required by the cursor line; parameterised over the three sort orders):
  - red = `but was: SearchQuery[... limit=100, cursor=Optional.empty]`;
  - green = the cursor decoded by `CursorCodec`;
  - mutation M5 "`departure` read as `DURATION`" failed its case.
- `SearchControllerTest.searchesWithNoFilters` (`@WebMvcTest`, `WebTestApplication`, the fake search; the body compared in `JsonCompareMode.STRICT`):
  - red = `Status expected:<200> but was:<404>`;
  - green = the mapping, `SearchResponse`, and `ItineraryResponse` as a stub.
- `writesLocalTimesAndThePriceButNeverTheProvider` (MAD 08:15+01:00, via BCN, to JFK 14:10-05:00; legs at 100.00 and 200.00 from ALPHA and BETA):
  - red = `ServletException: ... UnsupportedOperationException: not yet` (the stub);
  - the first green run then failed on the test itself: `durationMinutes Expected: 775 got: 715`. My hand sum was wrong (07:15Z to 19:10Z is 11 h 55 min = 715). The expectation was corrected, not the code.
  - green = the card's fields, with `Price` and `Leg` nested and the price at 315.00 (300 × 1.05). The strict comparison refuses any extra field, and the body contains no `provider`, `alpha`, `beta`, `100.00` or `200.00`.
  - mutation M6 "legs' times at UTC": `Expected: 2026-11-20T08:15:00+01:00 got: 2026-11-20T07:15:00Z`.
- `flagsAChangedInventory`:
  - red = `JSON path "$.inventoryChanged" expected:<true> but was:<false>`;
  - green = the flag and `nextCursor` as `CursorCodec.encode` of the page's cursor, which decodes back to it;
  - mutation M7 "no next cursor" failed it.
- `answersAProblemForABadCursor`:
  - red = `Status expected:<400> but was:<200>` (the cursor was not read). With the parameter read, the second red was `ServletException: ... InvalidCursorException`.
  - green = `ProblemResponses`, which maps it to a `ProblemDetail`.
  - That run also showed a test-isolation defect: the fake, a singleton in the cached context, kept the earlier tests' queries. `RecordingSearch.reset()` now runs before each test.
  - M8: `@ResponseStatus` on the handler turned out redundant (green without it), so it was removed.
- `answersAProblemNamingEachBadParameter` (beyond the plan's list, required by reading the other six parameters; one bad value each):
  - red = `Status expected:<400> but was:<200>` for all 6;
  - green = the seven named parameters and the parameter error mapped;
  - mutation M9 "origin and destination swapped": each case got the other parameter's detail.
- `PublicAccessOverHttpTest.anAnonymousSearchIsAnswered` (was `anAnonymousSearchIsNeverAskedForAToken`, which accepted the 404 "until the endpoint exists"):
  - red = `expected: 200 OK but was: 404 NOT_FOUND`;
  - green = `WebConfig`.
- `OpenApiContractTest.servedContractMatchesTheCommittedOne` (Step 4):
  - red = `Expecting path: ../docs/architecture/openapi.json to exist` (with the message that says how to regenerate);
  - green after `./gradlew :bootstrap:test --tests '*OpenApiContractTest*' -Dopenapi.write=true`.
  - It then went red by itself whenever a later cycle changed the contract, and the file was regenerated each time.
  - Mutation "one character of the committed file changed": red with the regeneration message, and the file restored.
- The contract's faithfulness, beyond the plan's list (the brief asks for every field, nullability and the error responses; Task 13 Step 1 adds the other endpoints):
  - `marksEveryFieldOfTheSearchRequired` (each of `SearchResponse`, `ItineraryResponse`, `Leg`, `Price`): red = `Expecting actual: [] to contain exactly in any order` (springdoc marks nothing required); green = `@Schema(requiredMode = REQUIRED)` on each record component.
  - `marksOnlyTheNextCursorNullable`: red = `Expecting actual: [] to contain exactly ["SearchResponse.nextCursor"]`; green = `types = {"string", "null"}`.
  - `documentsTheProblemOfABadRequest`: red = `expected: "#/components/schemas/ProblemDetail" but was: ""`; green = the operation's `@ApiResponse` for 400 as `application/problem+json`.
  - `documentsEachParameterAsTheSearchReadsIt`: red = `expected: "^[A-Za-z]{3}$" but was: ""`; green = `@Parameter` with the parser's patterns, the sort enum and its default, `limit` as an integer from 1 to 100 with default 20, and `maxLength` 200 on the cursor.

Refactors under the green suite, from the review of my own diff:
- `SearchParameters` gained one `matching(value, pattern, rule)` helper, so each parameter is a pattern, a rule and a conversion, with no block lambdas, and its defaults are named constants that the contract shares.
- `ProblemResponses` has one handler for both errors.
- The test cursors come from one `Cursors` fixture.
- `OpenApiContractTest` names the search's schemas once.
- Javadoc on the new types.
- M10 and M11 above were run after this refactor.

Gates: `./gradlew build` green (exit 0, Spotless, ArchUnit and the coverage gate included), on each of the task's two commits. Tests: `adapters/web` 53 (new), `bootstrap` 145, `adapters/persistence` 109, `adapters/providers` 197, `application` 82, `domain` 91, architecture 39, `mock-gds` 201; none failed. Thermo-nuclear review of the diff:
- No file near 1,000 lines: `SearchControllerTest` 243, `OpenApiContractTest` 177, the largest production file `SearchParameters` 102.
- No branch was added to existing code. `bootstrap/build.gradle.kts`, `application.yaml` and `libs.versions.toml` gain only their lines.
- Spring's and springdoc's own mechanisms are used: `ProblemDetail` and `@RestControllerAdvice`, `@RequestParam`, `@Schema`/`@Parameter`/`@ApiResponse`, and springdoc's key ordering.
- Bean Validation was considered and not used. It would split each rule between an annotation and the parser, its 400 detail does not name the parameter (`Validation failure`), and it needs `hibernate-validator`.
- Nothing is written half-way: the endpoint only reads.
- What fails without a clear answer: a search read that times out answers Boot's 500, not a problem (above), and an unknown parameter is ignored. Both are listed below.

### Extras for the user to decide

- **A search read that times out answers 503 with `Retry-After`, as a problem.** The parallel branch's 2 s statement timeout surfaces as Spring's `QueryTimeoutException`, which today answers Boot's 500 (above). Value: a client, and plan 6's "Try again", gets a retryable answer instead of a server error. Cost: a handler and a test, a `Retry-After` value to choose, and a choice of layer (an application exception from the reader, or Spring's `TransientDataAccessException` in the web adapter). Left out unless you approve.
- **Refuse unknown query parameters.** A misspelt filter (`orign=MAD`) is ignored today, so the search silently answers without it. Value: no silently unfiltered results. Cost: a check of the parameter names against the seven, and a test; the dashboard sends only known ones. Left out unless you approve.
- **Problem details for every framework error** (Boot's `spring.mvc.problemdetails.enabled: true`). An unsupported media type or method answers Boot's error JSON today. Value: one error format for clients. Cost: one setting and a test; Boot's handler and `ProblemResponses` coexist. Left out unless you approve.
- **An exact `ProblemDetail` schema.** springdoc reads Spring's class without the Jackson mixin that flattens its `properties` map, so the contract lists a `properties` member that Spring never writes. It is optional, so no client breaks. Value: an exact TypeScript type. Cost: a documentation-only schema or an OpenAPI customizer, about 15 lines and a test. Left out unless you approve.
- **The contract's metadata.** `info` is springdoc's default ("OpenAPI definition", "v0"), and `servers` is `http://localhost`, "Generated server url", from the test's MockMvc. Value: a titled contract and a relative server URL. Cost: an `OpenAPI` bean or two settings, and a regeneration. Left out unless you approve.
- **`currency` as the enum `["EUR"]` and a pattern on `Price.amount`.** Value: the TypeScript literal type `"EUR"`. Cost: two annotations and a regeneration. Left out unless you approve.
- Not an extra but unassigned: **E2 (Swagger UI)** is approved by the user, but no task builds it. This task adds only the `-api` starter, as its plan says, and `/swagger-ui.html` stays behind `ADMIN` until a task opens it.

## Task 9 — review

The double review (`.superpowers/sdd/2026-11-15-search-api/task-9-review.md`) answered functional APPROVED and code APPROVED, with no Critical or Important finding. The reviewer rebuilt with `--rerun-tasks --no-build-cache` (68 of 68 tasks executed, green), showed the drift test failing on either side's drift, checked springdoc 3.2.0 against Maven Central, and probed bad requests (a repeated parameter, a 5,000-character cursor, `maxPrice=000.00`, `Accept: application/json`): each got a 400 problem naming the parameter. The implementer's departures are judged justified. Six Minors wait for the end-of-plan review (the user's rule of 2026-11-15: a Minor opens no round of its own):
- F1: the plan's Task 10 still says "Create `WebConfig.java`" (Task 9 created it), and Task 13's README step is partly done; amend the plan before briefing Task 10.
- F2: the "over 200 characters" cursor case does not pin the length bound itself (the 400 holds).
- C1: springdoc as an `api` dependency puts Jackson 2 and springdoc on bootstrap's compile classpath; bootstrap should declare it itself.
- C2: `maxPrice` "above 0" is outside the contract's pattern, so the contract lets `0` through.
- C3: the sort values are written twice, in the parser and in the contract annotation.
- C4: `CursorCodec`'s Javadoc claims more strictness than the code has (leading zeros are accepted).
Approved extra E2 (Swagger UI) is built by no task: a plan gap for the user, not a finding.
