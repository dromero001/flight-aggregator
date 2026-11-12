# Session 01 — Requirements clarification

- **Date:** 2026-11-12
- **Tooling:** Claude Code (Claude Opus 5.5), with persistent agent memory so that decisions carry over between sessions
- **Goal:** read the take-home brief critically before writing any code. Find the ambiguities, decide what we can settle ourselves, and ask the hiring team only the questions that would change the design.

## 1. Approach

1. I pasted the brief into Claude Code and asked: *"Read the instructions and prepare a first draft of the points that are not clear."*
2. The agent produced 27 ambiguities, grouped by area. For each one it proposed a default assumption, so that a question left unanswered would never block the work.
3. I went through every point and decided whether to **settle it myself**, **document it as an assumption** or **ask the team**.
4. The agent kept a running decision log in its persistent memory. That log is the basis for this document and for the assumptions section of `SOLUTION.md`.
5. Together we wrote a clarification email with **4 questions**. Every other point was resolved internally.

The principle we followed was not to ask just for the sake of asking. One example: the agent first suggested asking whether Gamma's "comma-separated string streams" meant a chunked HTTP response. I challenged that by asking what legacy systems actually used to do. The answer was that classic GDS integrations (EDIFACT and teletype messages, fixed-width text, nightly flat-file drops over FTP) worked with complete text payloads, and chunked HTTP is a much later practice. A parser that reads line by line handles both cases anyway, so the question was dropped and the point became an assumption.

## 2. Ambiguities identified

| Area | Ambiguity | Resolution |
|---|---|---|
| Delivery | Exact deadline and delivery format | Monday, end of day; public GitHub repository URL |
| Stack | No language or framework imposed | We choose the most suitable stack and justify it in `SOLUTION.md` |
| Scope | Expected depth | As complete as possible, built as it would be in a real production system |
| Mock providers | Do we build them? | Yes. We build all three, with a dataset that covers every domain case, including dead legs and invalid connections |
| Mock providers | Payload schemas (JSON / XML / CSV) | **Asked.** If they are not specified, we design them ourselves |
| Mock providers | Data source and behaviour | **Asked.** Our default: they respond to the search parameters and are backed by a seeded test database |
| Mock providers | Gamma "string streams" | Assumption: a plain-text CSV body (see §1) |
| Resiliency | Frequency of HTTP 500 errors | Randomised and configurable. Tests cover one, two and all three providers failing |
| Ingestion | On-demand fan-out or pre-computed inventory? | **Decision: pre-computed inventory with a hybrid refresh** (see §3) |
| Ingestion | What to do with a provider that takes 5000 ms | A per-provider timeout well below 5 s (around 2–3 s), falling back to cached data |
| Domain | Deduplication key | Airline + flight numbers + connections + departure times. The cheapest offer wins |
| Domain | When the markup is applied | 5% applied **after** the cheapest offer has been selected |
| Domain | Currency | Assumption: all prices are in EUR |
| Domain | Trip type | Assumption: one-way only |
| Domain | Definition of "dead legs" and "invalid connections" | **Asked, with a proposed definition** (see §4) |
| Search | Mandatory filters | **Asked** |
| Search | Date filter | Not requested, so not implemented |
| Search | Sort order and keyset pagination | One composite index per supported sort order (price as the default, departure time, duration), with the id as tie-breaker. The decision is documented in `SOLUTION.md` |
| Search | Total count and jumping to page N | We will check how a real OTA handles this before deciding. OTAs usually use "load more" or infinite scroll, which suits keyset pagination |
| Frontend | What "real-time" means | An interactive search dashboard, used as an end customer would use it |
| Frontend | What "visualize" means | Several options per route (direct, with stops, different carriers), filterable by carrier and price |
| Non-functional | Performance targets | No load testing, but search latency must be low enough for end users |
| Non-functional | Choice of database and cache | Ours to choose and justify |
| Testing | Expected coverage | TDD throughout: unit, integration and end-to-end tests |
| Non-functional | Observability, CI, authentication | Decided later, at production-like quality |
| AI usage | How much to document | Only the prompts that add value, plus the versioned agent configuration (`CLAUDE.md`, skills, subagents) |
| Live session | Setting | Assumed: our own machine, running Claude Code, with a shared screen |

## 3. Key architectural decision: pre-computed inventory with a hybrid refresh

The brief contains two signals that pull in different directions:

- *"queries all 3 mock APIs simultaneously"* suggests an **on-demand fan-out**: the user searches, the system calls all three providers, merges the results and responds.
- *"Cache Optimization System"*, *"100,000 to 1,000,000 combinations daily"* and *"pagination up to 1,000,000 rows"* suggest a **pre-computed inventory** that the search reads from.

With a pure on-demand fan-out, every search is as slow as the slowest provider (up to 5 s), and paginating a million rows makes no sense because each search returns only a small set. So we chose the following:

- **An ingestion job** queries the three providers concurrently and on a schedule. It normalises, deduplicates and validates the results, applies the markup and stores them.
- **The search API** reads only from our own store, through indexed and keyset-paginated queries, so latency stays in the millisecond range.
- **The hybrid part:** when the data for a route is stale, it is refreshed in the background, and when a provider times out or fails, we keep serving the last known good data.

Accepted trade-off: prices shown can be slightly stale. Real OTAs handle this by re-pricing at booking time, which is out of scope here and is documented as a limitation.

Still open: the job frequency, the cache TTL, and the behaviour when all three providers are down.

## 4. Clarification email

The email was sent to the recruiter, who forwards it to the technical team. It contains four questions:

1. **Provider payload formats:** are there expected schemas? If so, a sample per provider, including a multi-segment itinerary. For Gamma: does each line hold a full itinerary or a single segment, and are headers included? If no schemas are expected, we design our own.
2. **Mock provider behaviour:** should results depend on the search parameters, or come from a fixed inventory? Is there a preferred data source? Our default is search-aware mocks backed by a seeded database.
3. **Dead legs and invalid connections:** confirm our proposed definition:
   - a connection shorter than the 45-minute minimum connection time, or longer than 24 hours;
   - the arrival airport of one segment differs from the departure airport of the next;
   - the arrival time is earlier than the departure time;
   - the flight has already departed.
4. **Search filters:** are Origin, Destination, Max Price and Carrier mandatory, or optional and combinable?

The email states that any point left open will be resolved with a documented assumption, so the work is never blocked waiting for an answer.

## 5. Prompts worth keeping

- *"Read the instructions and prepare a first draft of the points that are not clear."* This put a critical reading of the brief before any design work.
- *"In legacy systems, what was the usual practice? I don't want to ask just for the sake of asking."* This pushed back on a question the agent had proposed, and turned it into a documented assumption.
- *"Here I want to leave open the option of defining them ourselves."* This turned blocking questions into offers that only need a confirmation, so the team can simply reply "go ahead".

## 6. Next steps

- A **grill-me** session to stress-test the design: job frequency, TTL, behaviour when every provider is down, choice of stack.
- Check how a real OTA's search results page paginates.
- Create the repository, version the agent configuration (skills, `CLAUDE.md`), and start the domain layer with TDD.
