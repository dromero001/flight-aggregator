# Continuation point

The single place that says what is in flight, where it lives and what comes next, so that any session, on either machine, can take over in a few minutes after a dropped connection. Sessions drop without warning ([session 09](../ai/sessions/09-lost-sessions-and-recovery.md)); the conversation is lost, the repository is not.

**Rule:** the controller rewrites this file and pushes it at every checkpoint: a subagent dispatched or returned, a review verdict, a user decision, a task integrated (`CLAUDE.md`, "Continuity"). If something here contradicts `git log`, `git log` wins: say so and fix this file first.

**Last updated:** 2026-11-15 21:01 CET, on the Mac, `main` at `71184d9` plus the commit that changes this file. The Windows PC's session ("PC de casa") was told the state at 17:00 and not to start any in-flight task unless the Mac is down.

## Resume in five minutes

A usage limit cuts every subagent at once, like a dropped connection, but within the same session each one can be resumed where it stopped (`SendMessage` to its id once the limit resets); in a new session, re-dispatch it from this file.

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
| 5 · Search API | Tasks 1, 2, 3, 4, 5 (with its round 2), 6, 8 and 11 on `main`. Tasks 7 and 9 and the approved extras in flight (below). Order: 2+3+11 → 4 → 5+8 → **6** → 7+9 → 10+12 → 13. One double reviewer (functional, then code) per task; three reviewers at the end of the plan |
| 6 · Dashboard | Tasks 1, 3 and 9 on `main`. Tasks 2 and 4–8 wait for the OpenAPI contract of plan 5 Task 9; Tasks 10–11 after them |
| 7 · Observability and delivery | Approved 2026-11-15 (Q1–Q4 A; extras E1–E5, E7–E10; E6 out). Tasks 1 and 3 on `main`; Task 9's draft waits for the user; Task 9 (draft) may start now; 2 and 4 once plan 5 is closed. E9 (clean clone on the Windows PC) confirmed for Monday by the user |

## In flight

Every implementer below writes its report in its own worktree, at `.superpowers/sdd/<plan>/<task>-report.md` (untracked; worktree isolation refuses writes to the main checkout's `.superpowers/`). Reviews and the earlier reports are in the main checkout's `.superpowers/sdd/` (Mac only).

| Task | State | Worktree (Mac) and branch | Pushed as |
|---|---|---|---|
| Plan 5 Task 6, the search use case | **On `main`** (`e791cdb`, approved twice after three fix rounds; CI running). Minor C7 for later: a `search.cache-ttl` below 1 ms still reaches Valkey and opens the breaker (ties to Task 5's pending extra of 1 ms floors) | — | `wip/plan5-task-6`, `wip/plan5-task-6-r1` (obsolete) |
| Plan 5 Task 7, the stale-route refresh | Implementation, dispatched 20:25 (replaces Task 6's provisional scheduler; forwards `replaceThrough` through `CountedItineraryRepository`; splits `JdbcItineraryRepositoryTest` first); then its double review | `.claude/worktrees/agent-aa306a8d5ebd15803`, `worktree-agent-aa306a8d5ebd15803`, from `e791cdb` | `wip/plan5-task-7` after its first commit |
| Plan 5 Task 9, the search endpoint and OpenAPI | Implementation, dispatched 20:25 (plan 6 Tasks 2 and 4–8 wait for its `docs/architecture/openapi.json`); then its double review | `.claude/worktrees/agent-a95a01133888a6500`, `worktree-agent-a95a01133888a6500`, from `e791cdb` | `wip/plan5-task-9` after its first commit |
| Plan 5 Task 5, round 2 | **On `main`** (`afa79f5`, approved twice, CI running). Minors for the end-of-plan review: M1 `ValkeyFailures.java:57`, the dead `recordExceptions(DataAccessException.class)` line (a trap: delete it, or `ignoreException` with one catch); M2 `EpisodeWarning.java:46-47`, the unlogged count appears only with the next episode's WARN, with no start time. Notes: Task 12's health must not read the startup `half_open` as Valkey unavailable; Task 13's `search-api.md` lists the `valkey.circuitbreaker.*` meters | — | `wip/plan5-task-5-r2` (obsolete) |
| Approved extras (plan 5 Tasks 4 and 8, git guard) | **On `main`** (`71184d9`, approved twice after two fix rounds; full build and hook tests green; CI running). The new guard is live (the hook runs the main checkout's script): a plain push followed by a command with a plus sign now passes. Open for the user: a line break inside a quoted argument of a destructive command is not joined (the reviewer's fix: join only inside a quote opened after a guarded prefix, never in a heredoc body). Accepted as is: a literal backslash before a real separator is read as escaped (safe direction) | — | `wip/plan5-extras`, `wip/plan5-extras-r1` (obsolete) |
| Plan 7 Task 1, telemetry backends in compose | **On `main`** (`48027d8`, approved twice after three rounds; CI running). Deferred to the end-of-plan review: C3 (a broken Tempo or Loki config waits 2 minutes), C4 (duplicated test plumbing, inline compose casts), C5 (Dockerfile inputs listed by hand) | — | `wip/plan7-task-1` (obsolete) |
| Plan 7 Task 3, alert rules | **On `main`** (`1d4b5eb`, approved twice; CI running). For the end-of-plan review: RF1 (progress report line 318 still says `IngestionFailing`'s stale rule covers a missing gauge only), RC1 (its summary could say "unknown on every instance"), F3 (150 minutes with two instances), C2 with Task 1's C4 | — | `wip/plan7-task-3` (obsolete) |
| Plan 7 Task 9, draft of `SOLUTION.md` | Draft done (`59f8727`, checked: 114 links, figures match their reports; 12 `TODO(plan N Task M)` placeholders). Waiting for the user's review (Step 3); points to look at first: the 30-minute cadence's reason (spec 6.4 says a run takes minutes, plan 4 measured 12 to 14 s), the cache hit ratio (no task measures it under real traffic), three inferred 'Rejected' lines. Merging with Task 1: add/add conflict on the plan 7 progress report, keep both, Task 1's first | `.claude/worktrees/agent-ad45d58ed7874f3b3`, `worktree-agent-ad45d58ed7874f3b3` | `wip/plan7-task-9` (at `59f8727`) |

**Plan 5 Task 6:** the plan's Task 6 text, its Global Constraints, Q1–Q9 as decided, and the tests in the order its Step 1 lists. The refresh request must never delay or fail a search.

**Plan 5 Task 5, round 2:** C-R1 as the user decided (a probe that throws anything, a JVM `Error` included, releases the breaker's permission and rethrows); the approved extras, the breaker's state as a meter (Resilience4j's Micrometer binding, outside the providers' registry) and fewer WARN lines when many cached values fail to parse; C-R2 (the burst and before-first-connection tests assert command counts and the not-permitted count, keeping only a hang guard); C-R3 (`search.valkey-skip` under 1 ms refused with a message naming the setting). The re-review it answers: `.superpowers/sdd/2026-11-15-search-api/task-5-rereview-1.md`.

**Approved extras:** a 2 s statement timeout on every search read (`SET LOCAL statement_timeout`, a setting in the `search:` block); defensive copies in `StoredPage` and `SearchPage`; a deterministic test for two re-prices of the same itinerary at once (the second answers `GONE`); the git guard limited to one simple command (not crossing `;`, `&&`, `||`, `|` or a newline), every existing case still blocked, with its new cases in `.claude/hooks/block-destructive-git.test.sh`.

**Plan 7 Task 1, round 2, done in `42bc27f`** (plan 7 Task 1's "Decided by the user" line): the Collector keeps no health check, with the reason in `compose.yaml`; E8, a test that every compose image is pinned by tag and digest; the usage reports of Tempo, Loki and Grafana to Grafana Labs turned off, with a test; `ComposeFileTest` checks the published ports.

**Carried to later tasks:** Task 7 must forward `replaceThrough` through `CountedItineraryRepository`, reword the BUSY WARN and Javadoc to cover a refresh, and split `JdbcItineraryRepositoryTest` before adding tests. End-of-plan review: Task 8's CM1 (no test pins the SQLState comparison) and CM2 (an assertion that cannot fail), starvation wording in the progress report; Task 13 measures the probe's 1 s connect timeout on a network that drops packets.

Lessons for every brief: each subagent uses a private scratch folder of its own (one overwrote another's mutation script in the shared scratchpad). The guard fix is on `main` since `71184d9`, so a plus sign after a plain push no longer blocks; a destructive command quoted as text (for example in a script written by a heredoc) is still blocked, so write such text with a file.

## Next steps

1. As each of the four returns: push its branch as `wip/...`, update this file, dispatch its double review (one subagent acting as `functional-reviewer` then `code-reviewer`).
2. As each is approved twice: rebase on `main`, `./gradlew build` with its exit status checked, fast-forward `main`, push, watch CI, update this file and the handoff page. Progress-report conflicts between parallel tasks are append-only: keep both sections, `main`'s first.
3. With Task 6 on `main`: plan 5 Tasks 7 and 9 in parallel (Task 9 generates the OpenAPI contract that plan 6 Tasks 2 and 4–8 wait for).
4. Plan 7 Task 9: when the draft returns, the user reviews it (its Step 3). Plan 7 Task 3 (alert rules) starts once Task 1 is on `main`.

## Waiting on the user

- **Delivery before 23:59 on Monday 2026-11-16** (the user's commitment); the running application is tried by hand at the close of each plan (roadmap).
- **About 22:00 on Sunday:** the controller proposes the rules for the unattended night work and the user decides them (a session-only reminder is set; if this session is gone, the next one does it).

- Delete each `wip/plan*-task-*` branch once its task is on `main` (the git guard stops the agent from deleting remote branches): `git push origin --delete <branch>`. (`wip/grill-me-observability` is already gone.)
- E9 of plan 7: the clean-clone check on the Windows PC, on Monday.
