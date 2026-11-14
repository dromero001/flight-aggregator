# Agent skills used in this repository

The agent configuration is part of the solution, so the skills the agent relies on are versioned here or declared as plugins in `../settings.json`. Claude Code offers to install the declared plugins when the repository is opened and trusted.

| Skill | Where | Origin | Used for |
|---|---|---|---|
| `brief-scope` | `brief-scope/` | Written for this project | Keep every proposal within the brief and the recorded decisions; flag extras for the user |
| `thermo-nuclear-code-quality-review` | `thermo-nuclear-code-quality-review/` | The author's own skill | Strict maintainability review before every commit |
| `deslop` | `deslop/` | The author's own skill | Remove AI-generated code slop from a diff |
| `grilling` | `grilling/` | [Matt Pocock's skills](https://github.com/mattpocock/skills), MIT | The grill-me sessions on open design decisions |
| `ubiquitous-language` | `ubiquitous-language/` | [Matt Pocock's skills](https://github.com/mattpocock/skills), MIT | DDD glossary of domain terms (user-invoked) |
| `superpowers` | plugin | [obra/superpowers](https://github.com/obra/superpowers) | Brainstorming, writing plans, TDD, subagent-driven development, debugging, verification |
| `writing-clearly-and-concisely` | plugin `elements-of-style` | [obra/elements-of-style](https://github.com/obra/elements-of-style), public domain | Clear prose in `SOLUTION.md`, the README, commit messages and user-facing text |
| `frontend-design` | plugin | Anthropic's official plugins | A dashboard that does not look generic |

The PreToolUse hook in `../hooks/block-destructive-git.sh`, adapted from Matt Pocock's `git-guardrails-claude-code`, blocks only destructive git commands (force push, remote branch deletion, `reset --hard`, `clean -f`, `branch -D`, `checkout .`, `restore .`). A normal `git push` stays allowed. Its tests: `bash .claude/hooks/block-destructive-git.test.sh`.

The MIT licence of Matt Pocock's skills is in `LICENSE-mattpocock-skills.txt`.
