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
