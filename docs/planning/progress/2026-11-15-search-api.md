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
