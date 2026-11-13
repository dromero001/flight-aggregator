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

## Q2 onwards

Pending.
