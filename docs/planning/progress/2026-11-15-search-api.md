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
