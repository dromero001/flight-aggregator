# Session 08 — The domain plan, its review and its run

- **Date:** 2026-11-14
- **Tooling:** Claude Code (Claude Opus 5.5) as the controller; one implementer subagent per task; the three reviewer subagents of `.claude/agents/`
- **Goal:** plan the pure-Java domain (legs, itineraries, prices, connection rules and the itinerary builder), have the user review the plan, and build it.

## 1. Decisions taken before the plan

The spec left four questions that change the domain's shape. Each was asked with a recommendation, and the user took all four:

| Question | Decision |
|---|---|
| What identifies an itinerary? | A name-based UUID of its leg keys, so the id survives re-ingestion and re-pricing |
| How is the unknown airport `ZZZ` caught, given its valid IATA format? | The aggregator keeps its own airport catalogue (built in plan 4) |
| Rounding mode | Half-up, once per itinerary |
| How fresh is an itinerary's price? | As fresh as its oldest leg quote |

## 2. The user's review of the plan

The plan listed the smaller choices it made in a table for review. Presented again, it also flagged an extra the first presentation had missed: rejecting a leg priced at zero. The user changed four rows:

| Plan | User's decision |
|---|---|
| `ProviderId` as a free-form string | An enum of the three providers: a string is fragile |
| Reject a price with more than two decimals | Round it half-up instead of losing the offer |
| On equal prices, the lowest provider id wins | The most recent quote wins; on a full tie, the enum order (asked as a follow-up, not assumed) |
| Zero-price legs: an extra, left out | A provider error: rejected, with its test |

All four went into spec §4 and the plan's decisions table before the run started.

## 3. The run

Five tasks ([plan](../../planning/plans/2026-11-14-domain.md), [progress report](../../planning/progress/2026-11-14.md)), each approved by its functional and code reviewers. Most per-task findings were the same kind: **a test that could not fail** under a plausible wrong rule.

- The half-up test's values gave the same result under half-even.
- No test put price before quote time, so swapping the tie-break order passed.
- No test built a valid three-leg itinerary, so a limit of two passed.
- The strict and lenient ArchUnit groups were picked by list position, so reordering the rules could silently relax one.

The whole-plan review found what no single task showed:

- **The builder restated two rules** (the connection window and "no airport twice") as silent filters. A rule changed in one place only would either drop itineraries silently or throw midway through an ingestion. `ConnectionRules` and `Itinerary` now own them, and the builder calls them.
- **`Money` accepted any `BigDecimal`.** One malformed price such as `1E+999999999` cost seconds of CPU or threw an exception that a per-record handler would not catch. Its input is now bounded.
- **Eleven requirements for the callers** (ingestion and re-price) were written into the progress report for plans 4 and 5.

## 4. The user's review of the run

The run ended with every ruling the controller had taken on the user's behalf, each with its cost if wrong, and three open questions. The user changed one ruling and answered the questions:

| Item | User's decision |
|---|---|
| R3: the itinerary price test asserted 210.00, "because a mirror test cannot fail" | Compare with `PricingPolicy.finalPrice`. The claim was wrong: the comparison fails when `Itinerary` stops using the policy, and it keeps the expected price in one place |
| The same flight from one provider with two arrival times | A flight with a wrong arrival must never be offered: all of that provider's offers of the flight are discarded, counted and logged. Between providers, the cheapest offer wins with its arrival (asked as a follow-up) |
| Re-price when the chosen offer's provider is down | Offer another provider's offer of the same flight, or else the next cheapest itinerary for the route that can be confirmed, with a notice; a 503 only when nothing can be confirmed |
| The `Money` bound | Confirmed, with a leg price ceiling under 100 million euros, so no itinerary price can reach it |
| The extra rule against the clock and randomness in the domain | Approved and built as ArchUnit rule 7 |

The user also asked for two rulings to be explained before accepting them: picking the lenient ArchUnit rules by identity rather than position, and the `TreeMap` index of the builder, which is now documented with its reason.

## 5. Prompts that added value

- *"Write it now; don't assume anything, ask me every doubt."*: made the four design forks explicit questions instead of silent defaults.
- *"Propose plan 2 to me again."*: a second presentation surfaced an extra (zero-price legs) that the first had wrongly reported as "none".
- *"I prefer an enum for ProviderId; a string looks fragile to me. I'm not sure I want to fail because of a third decimal; I'd rather round half-up. Between two equal offers it is better to pick the one quoted last, the most recent. We assume a leg at 0 is a provider error; I want the test and the rejection. Let's document this decision and the previous ones."*: four domain rules changed before any code existed, at the cost of a plan edit instead of a refactor.
- *"Your assumptions: 3, wouldn't it be more appropriate to use the method? 6, explain it better. 8, document that decision once you have explained it to me."*: reversed a ruling whose reason was wrong, and turned two silent implementation choices into explained, documented decisions.
- *"The same flight cannot exist with two different arrival times. We invalidate both, since we don't want to offer incorrect flights to customers."*: a data conflict the reviewers found became a domain rule, with the discarded offers counted instead of silently dropped.
- *"If confirming returns a 503, the other flight is proposed to the customer as an alternative, with a message saying the flight could not be confirmed and that the other one is the best alternative found. In that case we always give the customer a result."*: changed the re-price behaviour of the spec, and with it what plan 4 must store.

## 6. Lesson

Asking every implementer to show a test that passes on its first run failing under a named wrong implementation turned "the tests pass" into evidence. Most findings this run were tests whose values could not tell the right rule from a plausible wrong one. They are cheap to fix while a task is open and invisible once it is closed.
