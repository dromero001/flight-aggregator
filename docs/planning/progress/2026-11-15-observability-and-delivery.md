# Progress — plan 7 (observability and delivery), 2026-11-15

## Task 9 — draft

`SOLUTION.md` drafted at the repository root: Steps 1 and 2 of plan 7 Task 9. Step 3, the user's review, is still to come. Written from `main` at `72bce35`, with the `writing-clearly-and-concisely` skill applied to every paragraph, and from the sources the task names: spec §1, §2, §4–§6, §8, §9 and §11, `docs/architecture/`, the roadmap, plans 0–7, the progress reports, `docs/ai/README.md` and sessions 01–09. It takes no design decision of its own (plan 7, "Decisions this plan takes"): every paragraph restates a decision of the spec, a plan or a session, with the reason and the alternative those record.

### Step 2 — check

**Every item of Task 9's Interfaces list has its section:**

| Interfaces item | Section of `SOLUTION.md` |
|---|---|
| Architecture: modules, ports, flows, linking `docs/architecture/` | 1. Architecture (modules and ports, a runtime diagram, four flows, the stack, the adapters) |
| Modelling: legs and offers, deduplication, itinerary identity, price and half-up, freshness, the airport catalogue, the closed provider set | 2. Modelling, one paragraph each (the catalogue and the provider set share "Reference data the domain controls"), plus connections and the Carrier filter |
| Ingestion and freshness: hybrid model, double-buffered slots, daily partitions, stale-route refresh, cadence, plan 4's measured run | 3. Ingestion and freshness, one paragraph each, plus the two time bounds of a download |
| Pagination at 1M: keyset, one index per sort order, the cross-partition merge, the cursor and its generation, plan 5 Task 13's `EXPLAIN` figures | 4. Pagination at 1M, one paragraph each, plus "Load more"; the `EXPLAIN` figures are a placeholder |
| Caching, outages, re-price, security, observability, with the hit ratio, the honest note on Valkey, who reads the metrics | 5., one paragraph each; the honest note has its own paragraph; the hit ratio is a placeholder |
| Testing and quality gates: TDD, ArchUnit, coverage and formatting gates, end to end, CI | 6. Testing and quality gates |
| Assumptions (four named, and sessions 01–02's) and limitations (six named) | 7. Assumptions and limitations: nine assumptions, the six limitations |
| AI usage: process, `CLAUDE.md`, skills, hooks, sessions, the change of course, what the reviews caught | 8. AI usage, with continuity after the dropped sessions (session 09, `continuation.md`) |

**Every relative link resolves.** A script (kept out of git) read every inline link target in `SOLUTION.md`, checked that the file or folder exists in the worktree and, for a `#fragment`, that the target file has a heading with that anchor under GitHub's slug rules (lower case, punctuation dropped, spaces to hyphens). Result: **114 links, 0 broken.** The script was shown to catch a failure first: on a test file it reported `MISSING ANCHOR` for a wrong fragment and `MISSING FILE` for a missing target, and exited 1. Links to documents later tasks create (`docs/architecture/search-api.md`, `docs/architecture/observability.md`, the session after 09) are not written yet; each is a `TODO` comment instead.

**Every figure matches its source:**

| Figure in `SOLUTION.md` | Source | Matches |
|---|---|---|
| 277,605 of 999,466 itineraries visiting an airport twice | [progress 2026-11-13](2026-11-13.md), "Dataset statistics" | yes |
| 38,227 of 94,639 direct and one-stop itineraries single-airline (40.4%) | [progress 2026-11-14](2026-11-14.md), round 2 "Before / after" | yes |
| 1,021,205 itineraries; 14.4 s cold (`PT14.374S`), 11.9 to 12.0 s later; 316 MiB under `-Xmx512m` | [progress 2026-11-15](2026-11-15.md), plan 4 "Task 11 — evidence" | yes |
| With Beta failing: 1,021,094 itineraries, Beta's 3,579 offers kept | same | yes |
| Valkey: 250 ms command timeout, about 1.25 s for five commands, 10 s skip, fallback within 500 ms | [progress, plan 5](2026-11-15-search-api.md), "Task 5 — evidence" and its extras; plan 5 Q9 and Task 5 | yes |
| Up to 29 minutes of lock after a crash, runs nearly an hour apart | [progress 2026-11-15](2026-11-15.md), "Re-review of the fix wave" | yes |
| 67 tests; about 14 red phases | [progress 2026-11-13](2026-11-13.md) (67 tests); session 04 (14 red phases) | yes |
| 27 ambiguities, 4 questions; four gaps; 50 to 60 minutes per task | sessions 01, 02 and 09; roadmap | yes |
| About 27 minutes for a slow provider without the time limiter | plan 4 decisions; session 09 (a computed bound, not a measurement) | yes |

The other numbers are design values, each cited to its spec section or plan question: the cadence, threshold and TTL (§6.4), 45 minutes and 24 hours (§4), today + 37 days (§4.3; `ingestion.md`), 2.5 s and 60 s (§6.1), two million itineraries (plan 4), six indexes (plan 5 Q6), 200 characters (plan 5 Task 9), 60 and 10 requests a minute (plan 5 Q9), the four alert thresholds (plan 7 Q3–Q4), 90% and 80% (§6.11).

**Length:** about 4,300 words of prose (link labels included), about 15 minutes for a technical reader; the depth stays in the spec, linked from every paragraph.

### Placeholders left, and the task that fills each

Each is an HTML comment `<!-- TODO(plan N Task M): … -->` in `SOLUTION.md`; those that stand for a figure also carry a visible bracketed note in the text.

| Placeholder | Section | Filled by |
|---|---|---|
| The "Draft" note at the top | intro | plan 7 Task 9 Step 3, once the rest are filled |
| Link to `docs/architecture/search-api.md` | 1, "Four flows" | plan 5 Task 13 (creates it) |
| Duration of one stale-origin refresh | 3, stale-route refresh | plan 5 Task 13 (Step 3 run) |
| The reason the cadence stays at 30 minutes (visible note "For the user") | 3, cadence | plan 7 Task 9 Step 3, the user's decision, recorded in spec §6.4 first |
| Load time with six indexes per slot against plan 4's 12 to 14 s | 4, indexes | plan 5 Task 13 |
| `EXPLAIN (ANALYZE, BUFFERS)` of pages 1 and 50 per sort and filter, and the search latency | 4, merge across partitions | plan 5 Task 13 |
| The cache hit ratio | 5, honest note on Valkey | plan 7 Task 6 (Step 3 run), or Task 7 |
| Search with Valkey stopped | 5, honest note on Valkey | plan 5 Task 13 (and plan 5 Task 12's `SearchWithoutValkeyTest`) |
| Re-price with Beta stopped and with all three stopped | 5, re-price | plan 5 Task 13 |
| Link to `docs/architecture/observability.md` | 5, observability | plan 7 Task 7 (creates it) |
| Final test counts and JaCoCo coverage | 6, gates | plan 7 Task 10 (Step 4) |
| Link to the session record after session 09, and any later review finding | 8 | plan 7 Task 10 |

### For the user's review (Step 3)

- **The cadence (§3).** Spec §6.4 chose 30 minutes because "rebuilding ~1M itineraries takes minutes", and said the values would be tuned against the measured run; plan 4 measured 12 to 14 s. `SOLUTION.md` states both and leaves the reason to you rather than inventing one.
- **The hit ratio (§5).** No task measures a hit ratio under realistic traffic: plan 7 Task 6 Step 3 reads the panel after two identical searches, which gives a toy value. Decide which figure to cite, or whether to present the ratio only as the metric that would show it.
- **Rejections stated by inference.** Three "Rejected" lines restate an alternative the sources imply rather than name: a sequence for the itinerary id (§4's "16 bytes instead of 8"), the newest quote for freshness, and in-memory fakes in tests (§6.1's "instead of in-memory fakes").
- **Presentation.** An "At a glance" list and a Mermaid runtime diagram open the document; neither adds a decision.
- **README.** `SOLUTION.md` is not indexed: the index has no slot for it, and the "Work in progress" note already names the file; Task 10 Step 3 links it in the final README. This report is indexed under "Progress reports", with the same line Task 1's branch adds (`f5e6520`), so the two merge cleanly.

### Gates

- `./gradlew build`: `BUILD SUCCESSFUL`, exit status 0 (66 tasks, 36 from the build cache). No code changed; `npm run verify` does not apply (`frontend/` untouched).
- Self-review against the outline and `brief-scope`: every paragraph traces to the spec, a plan, a session or a progress report; no requirement is added or dropped; the open cadence reason is left to the user instead of being decided here.

Extras: none.
