# Session 04 — Autonomous agent work and a TDD correction

- **Date:** 2026-11-13
- **Tooling:** Claude Code (Claude Opus 5.5); a background subagent for the implementation; the main session for review
- **Goal:** move work forward while I was away, then check that it met the project's standards.

## 1. What was delegated

With about 9 hours of my own time until the deadline, I asked the agent to start the sample data early. It did not depend on the decisions still open. A background subagent built the Gradle skeleton and the deterministic generator of the mock providers' inventory. The brief told it to use strict TDD, review its code before each commit, and write a progress report. The result: [progress report](../progress/2026-11-13.md), 67 tests, and ~1M itineraries reachable from the generated legs.

## 2. What the review found

I asked the main session a direct question: *"Are you applying TDD?"* Instead of trusting the subagent's report, it inspected the evidence: the commit history and the subagent's own transcript. The answer was **only partly**:
- Every commit paired production code with its tests, and the transcript showed compile-time red phases (tests written before the classes existed).
- But there were ~14 red phases for 67 tests: tests were written **several at a time per component**, not one at a time.
- The code-quality review ran once at the end instead of before each commit.

The generated data was sound. The process was not the one we had agreed.

## 3. The correction

TDD is a core requirement of this project, so we made it enforceable instead of relying on a reminder in each prompt:
- A versioned [`CLAUDE.md`](../../CLAUDE.md) at the repository root now binds every agent. It requires the TDD skill, one test at a time, confirming each test fails for the expected reason, a review before every commit, and the red → green evidence recorded in the progress report.
- Every brief to a subagent now names the TDD skill explicitly. The main session checks the work against these rules before accepting it.

## 4. Lesson

Delegating to an agent does not delegate accountability. An autonomous agent tends to relax a process rule when the result still "works", so a passing build is not evidence that the process was followed. Asking for the evidence, and then encoding the rule in versioned configuration, turned a one-off correction into a guarantee for the rest of the project.
