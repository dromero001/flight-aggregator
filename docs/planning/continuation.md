# Continuation point

The single place that says what is in flight, where it lives and what comes next, so that any session, on either machine, can take over in a few minutes after a dropped connection. Sessions drop without warning ([session 09](../ai/sessions/09-lost-sessions-and-recovery.md)); the conversation is lost, the repository is not.

**Rule:** the controller rewrites this file and pushes it at every checkpoint: a subagent dispatched or returned, a review verdict, a user decision, a task integrated (`CLAUDE.md`, "Continuity"). If something here contradicts `git log`, `git log` wins: say so and fix this file first.

**Last updated:** 2026-11-15 17:47 CET, on the Mac, `main` at `89d879e` plus the commit that changes this file. The Windows PC's session ("PC de casa") was told the state at 17:00 and not to start any in-flight task unless the Mac is down.

## Resume in five minutes

1. `cd flight-aggregator && git pull --ff-only`, then `gh run list --workflow CI --branch main --limit 1` must be green (prefix `gh` with `direnv exec .` on the Mac).
2. Read **In flight** below. A subagent whose dispatching session is gone is dead, even if its worktree looks busy: check its worktree (`git -C <worktree> status -sb`, `git -C <worktree> log --oneline main..HEAD`) and re-dispatch it from what this file records.
3. On the other machine, the worktrees do not exist: take each task's committed work from its `wip/…` branch (`git fetch && git worktree add .claude/worktrees/<name> -b <local name> origin/wip/<…>`). Uncommitted work in a Mac worktree is lost to the PC; redo it from the round's description below.
4. Opened from the parent folder (`~/IdeaProjects`): the repository's `.claude/` is not loaded; reviewers run as general-purpose subagents told to act exactly as their `.claude/agents/<name>.md`, skills are read from `.claude/skills/`. Read `.claude/skills/prompt-log/SKILL.md` at the start.
5. Docker must run for Testcontainers (`open -a Docker` on the Mac). On the Mac, keep it awake with `caffeinate -ims` (one runs until Tuesday 17 00:48, started 2026-11-15 16:48).
6. Then go to **Next steps**.

## Where the truth is

| What | Where |
|---|---|
| Design and every decision | [design-spec.md](design-spec.md) |
| Plans, order and schedule | [roadmap.md](roadmap.md), [plans/](plans/) |
| TDD evidence and review verdicts | [progress/](progress/): `2026-11-15.md` (plans 3–4), `2026-11-15-search-api.md` (plan 5), `2026-11-15-dashboard.md` (plan 6) |
| User decisions and prompts | [session 09](../ai/sessions/09-lost-sessions-and-recovery.md), and the plans' "Decided by the user" lines |
| Task briefs, review reports, implementer reports | `.superpowers/sdd/<plan>/` on the Mac only (git-ignored): each brief is the plan's task text plus its Global Constraints, so it can be rebuilt from the plan; the open findings of every in-flight round are copied below |

## State of the plans

| Plan | State |
|---|---|
| 0–4 | Closed, on `main` |
| 5 · Search API | Tasks 1, 2, 3, 4 and 11 on `main`. Tasks 5 and 8 approved, being integrated (below). Order: 2+3+11 → 4 → **5+8** → 6 → 7+9 → 10+12 → 13. One double reviewer (functional, then code) per task; three reviewers at the end of the plan |
| 6 · Dashboard | Tasks 1, 3 and 9 on `main`. Tasks 2 and 4–8 wait for the OpenAPI contract of plan 5 Task 9; Tasks 10–11 after them |
| 7 · Observability and delivery | Approved 2026-11-15 (Q1–Q4 A; extras E1–E5, E7–E10; E6 out). Task 1 in round 2; Task 9 (draft) may start now; 2 and 4 once plan 5 is closed. E9 (clean clone on the Windows PC) confirmed for Monday by the user |

## In flight

| Task | State | Worktree (Mac) and branch | Pushed as |
|---|---|---|---|
| Plan 5 Task 8, re-price | **On `main`** (`89d879e`, after its rebase and a green build; CI running). Approved twice; 3 code minors for the end-of-plan review | `.claude/worktrees/agent-aa031dfa12b4cd6b5`, `worktree-agent-aa031dfa12b4cd6b5` | `wip/plan5-task-8` (at `f3cb3cc`, before the rebase) |
| Plan 5 Task 5, Valkey | **Approved twice** (re-review of fix round 1: both APPROVED, 3 code minors). To integrate after Task 8 (rebase, build, fast-forward). Then a round 2 on `main` (below) | `.claude/worktrees/agent-a8dc11a74aba5a730`, `worktree-agent-a8dc11a74aba5a730` | `wip/plan5-task-5` (at `4e4d91a`) |
| Plan 7 Task 1, telemetry backends in compose | Built (`f5e6520`, 724 tests green); the same implementer resumed ~17:40 for round 2 before any review: the user's decisions and E8 | `.claude/worktrees/agent-a380b01c59520a201`, `worktree-agent-a380b01c59520a201`, from `04586b6` | `wip/plan7-task-1` (at `f5e6520`) |

**Plan 5 Task 8:** after integration, update its text in the plan (it still says `updateInPlace` returns a boolean, `activeSlotLocked` and "lock timeout"; the code returns `StoreOutcome` STORED/GONE/BUSY under `FOR SHARE NOWAIT`). Minors for the end-of-plan review: CM1 no test pins the SQLState comparison (`JdbcItineraryRepository.java:165-166`); CM2 an assertion in `boundsTheTransactionOfAnUpdate` cannot fail (one connection per statement); CM3 the same-itinerary race (now an approved extra, below). Task 7 must forward `replaceThrough` through `CountedItineraryRepository`, reword the BUSY WARN and Javadoc to cover a refresh, and split `JdbcItineraryRepositoryTest` (730 lines) before adding tests. Starvation of a run by overlapping re-prices delays it, never fails it; the progress report's wording changes at the end-of-plan review.

**Plan 5 Task 5, round 2** (after integration, on a branch from `main`, then its double review): the user's approved extras, the breaker's state as a meter and fewer WARN lines when many cached values fail to parse; and the re-review's minors: C-R1 (`ValkeyFailures.java:78-82`: a non-Valkey exception thrown by a probe is recorded as a success, closes the breaker and logs a false recovery; fix by releasing the permission and rethrowing; whether to catch every `Throwable`, which also covers a JVM `Error`, is the user's call, since the user left recovery after an `Error` out), C-R2 (the burst and before-first-connection tests assert command counts and the breaker's not-permitted count instead of wall-clock bounds, keeping only a hang guard), C-R3 (`SearchProperties.java:26`: the comment's reason does not hold for `valkey-skip`; a value under 1 ms fails startup with a message that does not name the setting). Note for Task 13: before the first connection on a network that drops packets, the probe waits the 1 s connect timeout, for one caller every 10 s.

**Plan 7 Task 1, round 2** (plan 7 Task 1's "Decided by the user" line): the Collector keeps no health check, with the reason in `compose.yaml`; E8, a test that every compose image is pinned by tag and digest (assigned to this task by the controller); the usage reports of Tempo, Loki and Grafana to Grafana Labs turned off, with a test; `ComposeFileTest` checks the published ports. The implementer's isolation cannot write the main checkout's `.superpowers/`, so its report is in its worktree (`.superpowers/sdd/2026-11-15-observability-and-delivery/task-1-report.md`), copied to the main checkout by the controller. Then its double review.

**Approved extras not yet assigned to a running task** (the user, 2026-11-15, recorded in plan 5): a 2 s statement timeout on every search read and defensive copies in `StoredPage` and `SearchPage` (Task 4, on `main`); a test that pins two re-prices of the same itinerary at once (Task 8, after its integration); the git guard's force-push pattern limited to one command (no crossing `;`, `&&` or `|`), with its case in the hook's tests. Plan: one small "extras" task in its own worktree once Task 8 is on `main`, with its double review.

Lessons for every brief: each subagent uses a private scratch folder of its own (one overwrote another's mutation script in the shared scratchpad). Until the guard is fixed, keep any `+` (such as `date +%H:%M`, or a plus sign in text written by a heredoc) out of a command line that also mentions pushing; write such text with a file instead.

## Next steps

1. Plan 5 Task 8: on `main` (`89d879e`), its plan text updated; watch its CI run.
2. Plan 5 Task 5: rebase on `main` (after Task 8), build, fast-forward, push, CI. Then its round 2.
3. Dispatch the "extras" task (above).
4. With Tasks 5 and 8 on `main`: plan 5 Task 6 (the search use case).
5. Plan 7 Task 1's round 2, then its double review, then integration. Plan 7 Task 9 (draft of `SOLUTION.md`) may start as soon as there is room.

The routine for any task: when a round returns, push its branch to `wip/...`, update this file, dispatch the (re-)review (one subagent acting as `functional-reviewer` then `code-reviewer`); when approved twice, rebase on `main`, `./gradlew build` with its exit status checked, fast-forward `main`, push, watch CI.

## Waiting on the user

- Delete each `wip/plan*-task-*` branch once its task is on `main` (the git guard stops the agent from deleting remote branches): `git push origin --delete <branch>`. (`wip/grill-me-observability` is already gone.)
- E9 of plan 7: the clean-clone check on the Windows PC, on Monday.
