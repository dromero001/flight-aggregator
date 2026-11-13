# Session 03 — Grill-me on the open design decisions

- **Date:** 2026-11-13 (in progress)
- **Tooling:** Claude Code (Claude Opus 5.5) with persistent memory; a "grill-me" skill (one question at a time, always with a recommendation); web search to verify release and support dates
- **Goal:** settle the open decisions of the [plan draft](../superpowers/specs/2026-11-13-flight-aggregator-design.md) (§10) one by one, before writing the detailed implementation plan.

## Q1 — Technology stack

### What happened

1. The agent proposed **Java 21 + Spring Boot 3 + Resilience4j + PostgreSQL + Redis + Gradle + Testcontainers**.
2. I challenged it: *is this a modern stack? Why Java 21 and not 25? Isn't Spring Boot on version 4?*
3. The agent checked the official release notes and end-of-life data, and confirmed the proposal was outdated. Spring Boot 3.5 reached end of OSS support on 2026-06-30, and Java 25 has been the LTS since September 2025.
4. I asked it to review **every other component** with the same check rather than fixing only the two I had spotted. That surfaced three more points:
   - **Spring Framework 7 now ships retry and concurrency limits in core**, so part of Resilience4j was redundant. We kept only its circuit breaker and time limiter, and used the framework's canonical mechanism for the rest.
   - **Resilience4j has no official Spring Boot 4 starter yet**, so its core modules are wired by hand.
   - **PostgreSQL 19 is still a release candidate**, with GA planned after the delivery date, so we stay on 18. **Valkey 9** was chosen over Redis for its BSD license and its AWS adoption.
5. I set the version policy: **latest stable GA only, no release candidates or fresh `.0` releases**, because the deadline leaves no room to absorb framework bugs.

The resulting stack and the reasoning per component are in §6.1 of the plan.

### Lesson

An AI agent's knowledge of "the latest version" is a snapshot. Its first proposal was reasonable but out of date, and it would have shipped a framework already out of support. Two habits came out of this and now apply to the rest of the project:

- **Verify any version or support claim against a primary source** (release notes, end-of-life data) before accepting it.
- **When one item turns out wrong, review the whole set**, not just the item that was challenged.

## Q2 — Where itineraries are built

**Decided: materialised at ingestion.** The job builds every valid itinerary (up to 2 stops) and stores it with its final price. Search is then a single indexed, keyset-paginated query. Building itineraries at query time was rejected: every page would need a graph search, and keyset pagination over computed rows is fragile. Once the builder-check gaps were settled (full leg inventory, ~1M real rows), this was the only option consistent with them, so it was closed without further debate.

## Q3 — Role of the cache, and price volatility

### What happened

1. The agent proposed giving Valkey two roles: a result cache for the first page of popular searches, with keys versioned by inventory generation, and coordination of background refreshes (`SET NX`). The job lock would stay in PostgreSQL.
2. I asked whether it had taken into account that **flight prices can change at any moment**. It had treated staleness only as a documented limitation, and had missed three consequences:
   - the price has to be confirmed before selling: **re-price on selection**, with a "price changed" notice, as eDreams does at checkout;
   - **pagination by price breaks** when prices are updated in place (items can be skipped or repeated): the cursor carries the generation, and the dashboard offers a reload;
   - the mocks need **price drift**, so the behaviour can be demonstrated and tested.
3. I asked for a justification of **why** we cache and **which type** of cache, one I could defend in the interview. The result names two levels:
   - PostgreSQL as a refresh-ahead / stale-while-revalidate cache of the providers;
   - Valkey as a cache-aside result cache with versioned keys.

   For each level, the agent wrote down what it costs and which alternatives were rejected, including an honest admission that PostgreSQL alone would cope with the demo's volume.

Details are in §6.2 and §6.3 of the plan.

### Lesson

Asking about the business reality ("prices change") rather than the technology exposed requirements that a purely technical review missed: confirming the price before sale, and keeping pagination consistent. A cache has to be justified by evidence it can expose (hit ratio, and a test with Valkey down), not by habit.

## Q4 onwards

Pending.
