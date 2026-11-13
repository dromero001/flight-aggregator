# Session 02 — Hiring team's answers and project plan draft

- **Date:** 2026-11-13
- **Tooling:** Claude Code (Claude Opus 5.5) with persistent memory; the `brainstorming` skill; a fresh subagent acting as an independent "builder" to review the draft
- **Goal:** fold the hiring team's answers into the design, fix the scope and the way of working until Monday, and produce a plan draft whose open points feed the next session (grill-me).

## 1. Answers from the hiring team

| Question (session 01) | Answer | Impact |
|---|---|---|
| Payload schemas per provider | Free to design (Gamma with or without headers); be ready to justify them | Schemas become ours, and the mocks must cover every case |
| Itineraries vs legs | Recommended industry pattern: providers return individual legs, not ready-made itineraries | Building itineraries becomes our core domain logic |
| Mocks: fixed dataset or search-driven | Free; only latency of 100–5000 ms and occasional 500s are mandatory | Latency and errors move from assumption to requirement |
| Our definition of dead legs / invalid connections | Confirmed as proposed | Used as the connection rules of the itinerary builder |
| Mandatory filters | All four are optional and combinable | Search API and indexes must support any combination |

## 2. Decisions taken in this session

1. **All three providers return legs**, and there is a single itinerary builder. The dead-leg rules become the conditions for chaining legs. Considered and rejected: providers returning ready-made itineraries (not how a GDS works) and a mixed model (two code paths for the same concept).
2. **New assumptions** for `SOLUTION.md`: at most 2 stops (to bound the combinatorics), and the Carrier filter means every leg is operated by that carrier (no codeshare).
3. **Full scope, no cut line.** The agent proposed leaving Redis, background refresh, auth, observability and CI as documented future work, given ~9 hours of human time. I rejected it: everything gets built.
4. **Way of working:** design decisions are made together; tedious and test-verifiable work (mock dataset, parsers, infrastructure, failure tests, e2e of approved flows) is done by the agent overnight, autonomously, with small commits and a progress report each morning. When a decision blocks the work, the agent writes it down as pending instead of improvising.
5. **Builder check:** a fresh subagent read the draft as if it had to build it and raised four gaps. Decisions: the mocks expose their full leg inventory, paginated (querying by route would never return connecting legs); a seeded generator produces ~1M real itineraries in a rolling 30-day window; the itinerary table is partitioned by departure day, and past partitions are dropped. The agent settled the minor points itself (UTC times with offsets; interlining allowed).
6. **Order:** plan draft (this session) → grill-me on the open decisions → detailed plan → TDD.

## 3. Output

- [Project plan draft](../superpowers/specs/2026-11-13-flight-aggregator-design.md): intent, goals and anti-goals, confirmed domain rules, approaches considered, hexagonal modules, phases with definition of done, testing strategy, AI usage per phase, and the open decisions for the grill-me.

## 4. Prompts that added value

- Rejecting the proposed cut line and asking for the full scope turned the plan from "build the core, document the rest" into a schedule that relies on autonomous overnight work.
- Separating **what needs me** (design decisions) from **what tests can verify** (everything else) became the basis of the day/night split.
