# Session 09 — Lost sessions, recovery, and the providers and ingestion plan

- **Date:** 2026-11-15
- **Tooling:** Claude Code (Claude Opus 5.5) as the controller, opened from the folder above the repository; reviewer and implementer subagents
- **Goal:** pick up the work after a dropped session, close plan 3 (mock providers over HTTP), and write plan 4 (providers and ingestion).

## 1. The problem: sessions that drop

The work runs on a laptop behind a corporate VPN client whose disconnections cut the Claude Code session several times over the weekend. Each cut loses the conversation: the question being answered, the reasoning behind the last steps, and any state that lived only in the chat.

Safeguards in place before this session:
- **The repository as the source of truth.** `CLAUDE.md` asks for every decision to be written into the spec or the day's progress report, never left only in the conversation, and for every session to end with a commit and a push.
- **Persistent memory** of the agent on the Mac, holding the user's decisions and the next step.
- **A private handoff page** with the state of the project, the user's rules and the next step, so that a fresh session, or the other machine, can continue.
- **A network watcher** script on the Mac that writes a warning file when the VPN drops, so that the agent can save its state first.

## 2. What this cut left behind

The previous session ended while the user was answering a question about plan 4's persistence. The new session recovered the state from memory, `git status`, `git log` and the progress report, and found:

- Plan 3's six tasks were done, plus **six local commits that were not pushed** and three uncommitted changes.
- The progress report had **no red → green evidence** for three of those commits, and no record that the end-of-plan reviews had run.
- The user's answers to the plan 4 questions, which were only in the lost conversation. The user gave them again at the start of the session.

The controller did not assume that the plan was closed. It ran the full suite and then the three end-of-plan reviews that the protocol requires. All three asked for changes, and they found what the cut had hidden:

| Finding | Why the cut hid it |
|---|---|
| One of the unrecorded commits had **replaced the user's decision on the mock's latency** (uniform 100–5000 ms) with a long-tailed model, and no decision recorded it | The reasoning existed only in the lost conversation |
| The README still documented the **old property names**, which Spring ignored without a warning. A reviewer proved it at runtime: the override changed nothing | The override was checked before the rename, and never again |
| A green Dependabot pull request would have moved the image to **Java 26**, which is not an LTS. Its build passed only because Gradle silently downloaded a JDK 25 | Nobody was watching the pull requests while the session was down |

The user ratified the long-tailed latency, because with the uniform model and a 2.5 s timeout half of the requests would time out. The user also approved an extra that makes a mistyped property stop the mock from starting. The missing TDD evidence was **re-established after the fact by mutation**: the controller broke the code, recorded which test failed and with what message, and restored it. The progress report labels these entries as such instead of presenting them as if recorded at the time.

## 3. A second, quieter problem: working from the parent folder

The user opens Claude Code in the folder that holds all projects, not inside the repository. From there, Claude Code does not load the repository's `.claude/` directory. That came to light when the reviewer subagents could not be started (`Agent type 'risk-reviewer' not found`). The checks that followed showed:

- **The git guard hook was not running.** Its configuration lives in the repository's `.claude/settings.json`, and its path depends on the project directory.
- **Two declared plugins had never been installed** (`elements-of-style` and `frontend-design`), because Claude Code offers them only when the repository itself is opened.
- **The reviewer subagents were not registered.**

What was fixed, with the user's approval:
- The hook is also configured in the parent folder's settings, calling the repository's script by absolute path. It was tested: it blocked a `git push --force --dry-run`.
- Both plugins are installed for the user.
- The reviewers run as general-purpose subagents told to act exactly as their definition in `.claude/agents/`.
- The project skills are read from `.claude/skills/` and applied.

`CLAUDE.md` now says how to work from the parent folder, so that the next session checks this first.

## 4. The continuity policy adopted

The cost of a cut depends on how much state lives only in the conversation, so the policy keeps that amount small:

- **Push every finished task**, and watch its CI run, instead of pushing only at the end of a plan.
- **Update the handoff page after every finished task and every user decision.**
- **Record every user decision in the repository** (the spec, the plan or the progress report) before moving on, and in the agent's memory at once.
- **On resuming**, never assume that a plan is closed: compare the local commits with the progress report and run the reviews whose approval is not recorded.

## 5. Plan 3 and plan 4

- **Plan 3:** closed after two fix rounds; the three reviewers approved the second. See the [progress report](../../planning/progress/2026-11-15.md), "Plan 3 close-out", for the findings and how each was fixed or decided.
- **Plan 4:** [providers and ingestion](../../planning/plans/2026-11-15-providers-and-ingestion.md). The user decided four points before it was written:
  - a double buffer: two itinerary slots, loaded with `TRUNCATE` and `COPY` and switched in one transaction;
  - the stale-route refresh moves to plan 5;
  - a ceiling of 2,000,000 itineraries per run;
  - `JdbcClient` with `COPY` instead of JPA, chosen from a side-by-side comparison of both options for the plan's five persistence jobs.
- **The user's review of plan 4:**
  - approved the one extra, an 8 MB limit on each provider response;
  - moved the per-provider concurrency limit (bulkhead) to plan 5, where re-price and the refresh call providers while ingestion runs;
  - asked to be reminded of spec §6.1 and why the plan departed from it. The plan used only the HTTP client's timeout, while §6.1 names Resilience4j's time limiter. The controller recommended the client timeout alone (option A) over adding a time limiter on each provider's whole download (option B). The user asked whether B was not more complete. Redoing the numbers showed it was: with per-request timeouts alone, a provider that is slow but never fails can stretch a run to about 27 minutes, close to the 30-minute cadence, and the circuit breaker never opens because nothing fails. The controller changed its recommendation, the user chose B, and spec §6.1 now records the two time bounds.

## 6. A skill for the prompts, tested before it was written

The user asked for a skill so that the prompts that added value are saved, translated into English, without having to remind the agent. It was written test-first, as `superpowers:writing-skills` requires:

- **Baseline, without the skill** (fresh subagents, each placed mid-task with a user message that changes the design and a "carry on"): two of two recorded the prompt in the progress report and deferred, or skipped, its entry in the session file. One wrote *"For this session's 'Prompts that added value': the prompt above…"* and left it for later. That is the entry a dropped session loses. A first pair of scenarios that asked the agent to list everything it would do did not show the failure, so it was replaced by the mid-task one.
- **The skill** ([`.claude/skills/prompt-log/`](../../../.claude/skills/prompt-log/SKILL.md)) is a recipe rather than a reminder: the next file change after a qualifying prompt is its entry in the session file, in a fixed shape, before the spec, the plan or any dispatch. It adds a table of what qualifies, translation rules, and the excuses for deferring. `CLAUDE.md` points to it, because sessions opened from the parent folder do not load project skills.
- **With the skill**, same scenarios: two of two made the session entry their first file change, translated the prompt faithfully, and left the routine part out.

## 7. A gate missed during the overnight run

During plan 4, the controller pushed every task as the continuity policy asks, but from Task 5's fix rounds on it stopped watching the CI run of each push, which `CLAUDE.md` requires. Three pushes in a row were red: a Task 5 test whose 500 ms request timeout a cold CI JVM exceeded, so the request was retried and the test saw two calls instead of one. Task 6 was then built on a red main. The code reviewer of Task 6 caught it by reading the CI runs. The fix went first in Task 6's fix round, and the run ledger records the correction. Lesson: a push without its CI result is not a finished step, however many other checks passed locally.

The same kind of slip happened once more on Sunday afternoon, while three worktrees were being integrated: one shell command ran the build of a rebased branch, printed its result and went on to fast-forward and push `main`, joined with `;` instead of `&&`. The build had failed (an application context could not reach its Testcontainers PostgreSQL over SSL while other agents ran containers), so `main` held an unverified commit for a few minutes. The controller saw it in the output, re-ran the failing module and the full build on `main` (both green) and watched CI (green), and recorded the correction in the run's ledger. Since then each push is guarded by the build's exit status. Lesson: a gate that only prints its result is not a gate.

## 8. Changing the sequence to meet the deadline

On Sunday at 12:30 the user asked how far behind the roadmap the work was. The honest answer, from the measured pace of plan 4 (50–60 minutes per task with its reviews and fix rounds), put the end of the last plan at the very end of Monday with no margin. Three options were proposed and the user took all three: run independent tasks of plan 5 in parallel in git worktrees instead of the agreed sequence; draft plan 6 while plan 5 runs; and merge the two per-task reviews into one reviewer with two verdicts, keeping the three reviewers at the end of every plan. TDD, the suite before every commit, the push and CI after every task, and the user's design decisions stay as they were. The change is recorded in the [roadmap](../../planning/roadmap.md) and in `CLAUDE.md`.

## 9. Prompts that added value

- *"I have always been working with Claude from the parent folder. Make sure you use every skill documented in the GitHub repository of flight-aggregator."*: exposed that the hook, two plugins and the reviewer subagents had never been active in these sessions, and led to the fixes in section 3.
- *"Since the connection can be lost again at any moment, let's set a policy of creating an artifact for every finished task, or if you think that is too much, propose another frequency."*: turned an ad hoc handoff page into the continuity policy of section 4.
- *"You recommend option A, but isn't B more complete?"*: made the controller redo the worst case it had assumed was bounded. Its recommendation was wrong: a slow provider could stretch ingestion to nearly the full cadence, and the plan now bounds each provider's download (spec §6.1).
- *"Remember they also want the prompts. Make a skill for it, so that the relevant prompts are saved translated into English and I don't have to keep reminding you."*: turned a rule the agent kept forgetting into a skill, `prompt-log`, that records each prompt when it happens instead of at the end of the session.
- *"I think we are going to disable Dependabot, it is getting in the way of the project's development; once it is delivered we will turn it back on. Reject the current Dependabot PR."*: paused Dependabot until the delivery (`open-pull-requests-limit: 0`, spec §6.11) while keeping its configuration, after three of its pull requests in one night had each needed a decision (a non-LTS Java, a formatter major, a formatter minor that breaks Spotless).
- Paraphrased (an option the user chose in a question, not typed): asked whether to build the two small extras the reviews had proposed, the user chose to include both: E1, the mock refuses to start on a `MOCKGDS_PROVIDERS_*` environment variable that names no setting, because Spring Boot does not check the environment for unknown keys; and E2, the gate logs each refusal with its provider and path, never a key. Both were built test-first in plan 3's second fix round (`609f2ad`, `bc6d27a`).
- *"If I don't accept E1, how are we going to get in as admin? Explain to me why E4 is good. We drop E3."*: questioned an extra left out by default (a demo password grant on Keycloak). Answering it brought out that the admin signs in through the dashboard's Keycloak login (Authorization Code with PKCE), and that a client-credentials client, not the deprecated password grant, is the right way to get an admin token from the command line (plan 5's open extras).
- Paraphrased (options the user chose after plan 4's three final reviews, not typed): a provider that answers but brings no usable leg keeps its last good offers instead of wiping them (spec §6.5); the provider adapters are restructured into one paged provider before plan 5 builds on them; a correlation id goes on the ingestion's logs through the logging context (MDC), because the log framework does not read Java's Scoped Values; a run with every provider down counts as failed so the outage alert can fire; overlapping runs lock the publication row; skipped runs are logged. The cap on discard log lines was declined: every discarded record keeps its line (§6.7).
- *"You will have to explain to me how to use it for the demo I have to give. Also, it has to be documented, because the reviewers of the take-home must be able to see it in their offline review without me present."*: turned an authentication detail into a deliverable: the client-credentials admin access is documented step by step for reviewers who run the project alone, not only shown live (plan 5).
- *"How are we doing on time against the plan?"*, then *"Document that, to arrive on time, we have had to take decisions to parallelize the work instead of following the sequence agreed at first."*: the first made the controller estimate the remaining work from the measured pace instead of the roadmap's hopes, which exposed that the delivery had no margin; the second made the change of sequence part of the record (roadmap, `CLAUDE.md`, this session) rather than a silent shortcut.
- Paraphrased (options the user chose in a question, after asking *"Do you need me to clarify anything?"*): the dashboard stays on ESLint 9 with the original `eslint-plugin-jsx-a11y` (E7) instead of ESLint 10 with a little-known fork, documented as a deviation from the newest-major rule because the linter never ships; `noUncheckedIndexedAccess` stays off (TypeScript `strict` as the plan says); of plan 4's three leftover extras, the unused test dependency is removed and `ProviderText.quoted` escapes inner double quotes, while the run id on the breakers' lines is left out. The question turned four decisions the controller would otherwise have parked into recorded ones.
- Paraphrased (options the user chose after asking *"Do I have to decide anything?"*): the dashboard's top bar keeps the title "Flight aggregator"; Keycloak loses its own administrator account and committed demo password, since nothing in the app or the demo needs it; an admin call with Keycloak down and no cached keys keeps answering 500 rather than 503; the learning test that pins how Spring 7.0.10's `@ConcurrencyLimit` counts stays, as a guard on the per-provider limit. Each was an open point the reviews had raised, closed in one exchange.
- *"For a normal job it is not viable that only the administrator can see the metrics; they must be available to the rest of the users."*: reversed spec §6.6's "Actuator admin only" for the metrics while plan 7 was being drafted. Asked which users and which surface, the user chose the team through Grafana (anonymous Viewer), with Prometheus scraping a management port reachable only inside compose, and kept the dashboard's provider status page admin only (spec §6.6 and §6.10, plan 7).
- *"Is there anything else to decide to bring the project's delivery forward?"*, then, among the options offered: move the OpenAPI contract from plan 5's last task to its first web endpoint, so the dashboard starts about two hours earlier (plans 5 and 6 updated); *"Don't drop any extra"*, keeping E3, E6 and E15 although the controller recommended cutting them; *"The PDF is out of the project, it can be built on Tuesday"*; and a review of plan 7 the same evening, so it runs overnight. The user kept the scope and bought the time from the order of the work instead.
- Paraphrased (an option the user chose in a question): when a re-price confirms a price live but cannot store it because an ingestion run holds the publication row (about 12–14 s every 30 minutes), the user still gets the live price; the store is skipped, logged and counted, and the next run rebuilds every itinerary anyway. The implementer had found that the whole re-price failed in that window; the user preferred this to keeping the failure or reshaping plan 4's locks (plan 5 Task 8).
- Paraphrased (an option the user chose in a question): a hung Valkey is skipped for 10 seconds after a failed command, through the project's Resilience4j circuit breaker, so a search pays one command timeout per outage window instead of up to five; the review had measured searches about 1.25 s slower and the first connection queueing callers (plan 5 Task 5).
- The brief for the fix round (a subagent prompt): *"For the commits whose evidence was never recorded because the session was cut off, say so honestly and, where you can, re-establish evidence after the fact by mutation, labelled 'recorded after the fact by mutation'."*: kept the TDD record truthful instead of reconstructing it as if it had been recorded at the time.
- Paraphrased (options the user chose in four questions, asked after *"send me plan 7's questions"*, which a second network drop had left unanswered): plan 7's Q1–Q4 all take the recommendation. Grafana's anonymous Viewer gets `viewers_can_edit`, so the approved log-to-trace jump works in Explore while nothing a Viewer changes can be saved; the app exports its logs over OTLP to the Collector and on to Loki, the same on the Mac and on Windows, with no Docker socket or host path; the alerts are the spec's three plus `ProviderDataStale` per provider, because one fresh provider resets the failure count and a provider down all day would otherwise raise nothing; and every value in Q4's table stands (3 failed runs, 90 minutes, 34 days, 15 minutes for NaN, 100% sampling, the 8079 management port, Monday's cut-offs for the optional tasks). Plan 7's extras and its approval stayed open.

## 10. Lesson

The cut itself was not the risk. The risk was state that lived only in the conversation: a design change without a recorded decision, and commits without evidence or review. The reviews caught it because the recovery treated the plan as open until the record proved otherwise.
