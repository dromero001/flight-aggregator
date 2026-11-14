# How this project uses AI

The solution was built with an AI agent, Claude Code. The way the agent was steered is part of the work, so it is documented here and its configuration is versioned in [`.claude/`](../../.claude) and [`CLAUDE.md`](../../CLAUDE.md).

## The process

| Phase | How the agent was used | Guard |
|---|---|---|
| Requirements | Read the brief critically, list the ambiguities, propose a default for each, and ask the hiring team only the questions that change the design | [Session 01](sessions/01-requirements-clarification.md) |
| Design | Brainstorming into a [design spec](../planning/design-spec.md), then a grill-me: one open decision at a time, always with a recommendation, and the user decides | `grilling`, `brief-scope` |
| Planning | Implementation plans in [`docs/planning/plans/`](../planning/plans/), one test-first task at a time | `superpowers:writing-plans` |
| Implementation | Strict TDD, often delegated to subagents for independent tasks or overnight runs, each ending in a [progress report](../planning/progress/) with the red → green evidence | `superpowers:test-driven-development`, `CLAUDE.md` |
| Review | A strict maintainability review of every diff before its commit, and a correctness review before delivery | `thermo-nuclear-code-quality-review`, `deslop` |
| Traceability | Every relevant session is written up in [`sessions/`](sessions/), keeping only the prompts that changed the outcome | `CLAUDE.md` |

## Review protocol

Plans are executed by subagents, one per task, and reviewed by three reviewer subagents defined in [`.claude/agents/`](../../.claude/agents), each with a single concern:

| Reviewer | Question it answers | When |
|---|---|---|
| [`functional-reviewer`](../../.claude/agents/functional-reviewer.md) | Does the change deliver what the brief, the spec and the plan ask, with nothing missing and no unapproved extra? | After every task, and on the whole plan |
| [`code-reviewer`](../../.claude/agents/code-reviewer.md) | Is the code maintainable (thermo-nuclear review) and correct, and does it respect the hexagonal boundaries? | After every task, and on the whole plan |
| [`risk-reviewer`](../../.claude/agents/risk-reviewer.md) | What can go wrong in production: security, data loss, silent failures, half-applied state, concurrency, resilience? | On the whole plan |

**Why separate reviewers.** A single reviewer asked to check everything tends to stop at the first kind of problem it finds. Giving each reviewer one question and its own checklist makes each review thorough, and a reviewer that did not write the code is not anchored on the author's reasoning.

**Why functional and code review after every task.** Both kinds of problem are local: a missing requirement or a poor abstraction is visible in the task's own diff, and it is cheapest to fix before the next task builds on it.

**Why risk review once per plan.** Operational risks usually live *between* tasks: a write in one task and its rollback in another, a fallback whose log line is in a third. A reviewer that sees one task at a time misses them, so the risk reviewer reads the whole plan's diff. It also keeps the cost reasonable: for a seven-task plan, two reviewers per task plus three at the end make 17 reviews instead of 21, and most foundation tasks (formatting, Dependabot) carry little operational risk.

A task moves on only when both of its reviewers approve; a plan closes only when all three do. Findings are fixed, or the user decides to accept them, and the progress report records which.

## Skills and plugins

Project skills live in [`.claude/skills/`](../../.claude/skills); plugins are declared in [`.claude/settings.json`](../../.claude/settings.json), and Claude Code offers to install them when the repository is opened and trusted.

| Skill | Where | Origin | Used for |
|---|---|---|---|
| `brief-scope` | `.claude/skills/brief-scope/` | Written for this project ([how it was tested](sessions/05-scope-guard-and-agent-skills.md)) | Keep every proposal within the brief and the recorded decisions; flag extras for the user |
| `thermo-nuclear-code-quality-review` | `.claude/skills/thermo-nuclear-code-quality-review/` | The author's own skill | Strict maintainability review before every commit |
| `deslop` | `.claude/skills/deslop/` | The author's own skill | Remove AI-generated code slop from a diff |
| `grilling` | `.claude/skills/grilling/` | [Matt Pocock's skills](https://github.com/mattpocock/skills), MIT | The grill-me sessions on open design decisions |
| `ubiquitous-language` | `.claude/skills/ubiquitous-language/` | [Matt Pocock's skills](https://github.com/mattpocock/skills), MIT | DDD glossary of domain terms (user-invoked) |
| `superpowers` | plugin | [obra/superpowers](https://github.com/obra/superpowers) | Brainstorming, writing plans, TDD, subagent-driven development, debugging, verification |
| `writing-clearly-and-concisely` | plugin `elements-of-style` | [obra/elements-of-style](https://github.com/obra/elements-of-style), public domain | Clear prose in `SOLUTION.md`, the README, commit messages and user-facing text |
| `frontend-design` | plugin | Anthropic's official plugins | A dashboard that does not look generic |

The MIT licence of Matt Pocock's skills is in [`.claude/skills/LICENSE-mattpocock-skills.txt`](../../.claude/skills/LICENSE-mattpocock-skills.txt).

## Hook

The PreToolUse hook [`.claude/hooks/block-destructive-git.sh`](../../.claude/hooks/block-destructive-git.sh), adapted from Matt Pocock's `git-guardrails-claude-code`, blocks only destructive git commands: force push (including `--mirror` and flag clusters such as `-fu`), deleting `main` (on the remote, through `gh api`, or with `update-ref -d`), `reset --hard`, `clean -f`, force-deleting a branch (`branch -D` or `-d -f`), `checkout .` and `restore .`, whatever global options (`-C <dir>`, `-c k=v`, `--no-pager`) come before the subcommand. A normal `git push` and deleting any other remote branch stay allowed. It guards both the Bash and the PowerShell tools, so it also applies on Windows; where `jq` is missing (Git for Windows does not ship it), it matches the raw hook input instead. Its tests, each case run with and without `jq`: `bash .claude/hooks/block-destructive-git.test.sh`. A regex guard can still be bypassed (an alias, a variable), so a GitHub ruleset backs it up: GitHub refuses a force push to `main` or its deletion even if the hook is bypassed.

## Working sessions

[`sessions/`](sessions/) holds one write-up per relevant session: its goal, what happened, the decisions, the lesson, and the prompts that added value.
