# Mock GDS Sample Dataset Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Gradle multi-module skeleton plus the `mock-gds` module's seeded, deterministic generator of the legs that the three mock providers will serve.

**Architecture:** Plain Java library code inside `mock-gds` (no Spring, no HTTP yet). A hand-written reference network (airports with real `ZoneId`s, airlines with home hubs, route rules) is expanded by the generator over a rolling 30-day window into dated legs, assigned to providers (some legs to two or three providers), priced by a pure drift function, and a small configurable share of records is marked malformed for the future serializers.

**Tech Stack:** Java 25 (Gradle toolchain + foojay resolver), Gradle 9.9.1 Kotlin DSL with a `buildSrc` convention plugin and a version catalog, JUnit Jupiter (BOM 6.1.3), AssertJ 3.27.7.

**Spec:** [docs/superpowers/specs/2026-11-13-flight-aggregator-design.md](../specs/2026-11-13-flight-aggregator-design.md) — §4, §4.1, §4.2, §6.1, §6.3, §6.7, §6.8, §8.

## Global Constraints

- Latest stable GA of every tool and library, verified against a primary source (Maven Central `maven-metadata.xml`, `services.gradle.org/versions/current`); no RC or milestone.
- Java 25 through Gradle toolchains; the global JDK is never changed.
- Times are `OffsetDateTime` with the airport's real offset, DST included (§4.2). Prices are EUR `BigDecimal` with scale 2.
- Deterministic: explicit seed, injected `Clock`; no `Math.random`, no `now()`.
- English everywhere (§6.8). TDD: every production class starts from a failing test.
- Out of scope: HTTP endpoints, provider serialization, the `domain` module, ingestion.

## Review Focus

1. DST transitions inside the window (Europe ends DST on the last Sunday of October, US on the first Sunday of November): local departure times in a gap must still produce valid instants — pinned by a test that generates legs across a DST change and checks `arrival > departure` and the offset per date.
2. Rolling the window by one day must not change the legs of the overlapping days — pinned by a determinism test with two clocks one day apart.
3. Prices must stay positive with scale 2 after drift — pinned by an invariant test over the whole dataset.
4. The same leg offered by several providers must share every identity field and differ only in price/provider — pinned by the duplicate test.
5. Malformed records must never leak into the valid leg list — pinned by the malformed-record test.

---

### Task 1: Gradle skeleton

**Files:** `settings.gradle.kts`, `build.gradle.kts`, `gradle/libs.versions.toml`, `buildSrc/build.gradle.kts`, `buildSrc/src/main/kotlin/flightaggregator.java-conventions.gradle.kts`, `mock-gds/build.gradle.kts`, wrapper files, `.gitignore`.

- [ ] Generate the wrapper for Gradle 9.9.1 with a downloaded, checksum-verified distribution; pin `distributionSha256Sum`.
- [ ] `settings.gradle.kts`: foojay resolver convention plugin 1.0.0, `include("mock-gds")`, and a comment listing the remaining modules of §6 (created when needed).
- [ ] Convention plugin: `java`, toolchain `JavaLanguageVersion.of(25)`, JUnit Platform, `-Xlint:all -Werror`.
- [ ] A trivial smoke test proves the toolchain and test setup: `./gradlew build` green. Commit.

### Task 2: Reference data

**Package:** `com.flightaggregator.mockgds.reference` — `Airport(String iata, ZoneId zone, double latitude, double longitude, AirportRole role, String homeHub)`, `AirportRole {HUB, SPOKE, LONG_HAUL}`, `Airline(String code, String homeHub)`, `ReferenceData` (static catalogue: `airports()`, `airlines()`, `airport(String)`, `longHaulLinks()`).

Tests: every IATA code is 3 upper-case letters and unique; every spoke's and airline's home hub is a hub; offsets are correct for a known date (MAD +01:00 in November, +02:00 in July; JFK −05:00/−04:00; BOG −05:00 all year).

### Task 3: Route network

**Package:** `com.flightaggregator.mockgds.network` — `Route(Airline airline, Airport origin, Airport destination)`; `RouteNetwork.build(ReferenceData)` rules: hub↔hub by each hub's home carrier, spoke↔home hub by the hub's carrier, long-haul↔linked hubs by the hub's carrier, low-cost carriers on a fixed list of hub pairs. `Route.blockTime()` from great-circle distance (800 km/h + 30 min).

Tests: no spoke-to-spoke route and no spoke route outside its home hub (this is what makes spoke→spoke across hubs a 2-stop-only trip); every route has its reverse; block time is positive and grows with distance.

### Task 4: Leg model and schedule expansion

**Package:** `com.flightaggregator.mockgds.dataset` — `Provider {ALPHA, BETA, GAMMA}`, `LegKey(airline, flightNumber, origin, destination, Instant departure)`, `Leg(airline, int flightNumber, origin, destination, OffsetDateTime departure, OffsetDateTime arrival, BigDecimal priceEur, Provider provider)` with `key()`, `GeneratorConfig` (record with `defaults()`), `ScheduleGenerator`: each route gets a seeded set of daily scheduled flights (flight number, local departure time); each (flight, date) operates with a configurable probability decided by a hash of (seed, flight, date), so a date's legs do not depend on the clock date.

Tests: departure offset equals the origin zone's offset on that date, arrival offset the destination's; all legs within the window; same seed + same clock date → identical; clocks one day apart → identical legs on the overlapping dates; different seeds → different schedules.

### Task 5: Pricing with drift

`PriceModel.price(LegKey, Provider, Instant at)`: pure function of (seed, key, provider, `floor(at / priceBucket)`); base fare from distance, provider and bucket factors from `DeterministicHash`. Tests: stable within a bucket, changes across buckets, differs across providers, scale 2 and > 0.

### Task 6: Providers, duplicates and malformed records

`DatasetGenerator.generate(Clock) -> Dataset`. Each leg gets one provider, a configurable share two, a smaller share all three, each priced independently. `ProviderRecord` (sealed: `ValidLeg(Leg)`, `MalformedLeg(Leg source, Defect defect)`), `Defect {MISSING_FIELD, BAD_DATE, NEGATIVE_PRICE, UNKNOWN_AIRPORT}`; a configurable share of each provider's records becomes malformed (replaces the valid record). `Dataset.validLegs()`, `Dataset.recordsFor(Provider)`.

Tests: duplicates by 2 and 3 providers exist with different prices; malformed share per provider ≈ configured; every defect present; malformed legs never in `validLegs()`.

### Task 7: Coverage, calibration and statistics (test-only)

`ItineraryCountEstimator` in test sources: cross-provider dedup (cheapest), drop departed legs, count 0/1/2-stop itineraries with the §4 rules (same airport, chronological, MCT ≥ 45 min, layover ≤ 24 h). `ConnectionCensus` classifies consecutive-leg pairs (valid, MCT break, layover > 24 h, airport mismatch, interlining). Tests assert every §4 case is present in the default dataset and that the default parameters give ~1M itineraries (±25 %); the statistics test prints the numbers for the progress report. Tune `GeneratorConfig.defaults()` until it passes, keep generation under a few seconds.

### Task 8: Review, docs, push

Thermo-nuclear review of the module, fixes, `docs/progress/2026-11-13.md`, README links, `./gradlew build`, push after the `dromero001` check.
