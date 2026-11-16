# User manual tools

The scripts that take the [user manual](../README.md)'s screenshots and build its PDF. The Markdown is the source of truth: after changing it, rebuild the PDF and commit both.

## Prerequisites

- Node, the version in `frontend/.nvmrc`, and the frontend's dependencies with Playwright's Chromium: in `frontend/`, `npm ci` and `npx playwright install chromium`.
- For the PDF: the GitHub CLI, `gh`, signed in to any account (`gh auth status`). The script sends the manual's Markdown to GitHub's Markdown API (`gh api markdown`), so the PDF reads as GitHub shows the page.
- For the screenshots: the compose stack running and healthy (`docker compose up --build`, README), with its first ingestion published, and the demo admin's password (`demo-admin-password`, or `ADMIN_USER_PASSWORD` as compose reads it).

Run every command from the repository's root, in a terminal on macOS or Linux, or in Git Bash on Windows.

## Build the PDF

```bash
node docs/user-manual/tools/build-pdf.cjs
```

It writes `docs/user-manual/user-manual.pdf` (A4, page numbers, an outline from the headings). Links to other files of the repository point at GitHub.

## Take the screenshots

Each script writes its PNGs into `docs/user-manual/images/`, or into the folder `MANUAL_IMAGES_DIR` names, to try a script without touching the manual's images. The data changes with every ingestion, so new screenshots may need new captions.

| Script | Screenshots | What it does to the stack |
|---|---|---|
| `capture-search.cjs` | the search, its filters, sort, "Load more", errors, the two-stop itinerary, the price answers, the phone | About ten searches and seven price checks |
| `capture-admin.cjs` | the sign-in, the provider status, a run on demand, "Results updated." | Starts one ingestion |
| `capture-outage.cjs` | the 503 answer, breakers open, a provider Degraded, legs kept | Stops and starts `mock-gds`, runs two ingestions, ends with every provider Up; about five minutes |
| `capture-alternative.cjs` | the alternative answer | Recreates `mock-gds` with Beta failing every request, then from `compose.yaml` alone; runs an ingestion; ends with every provider Up |
| `capture-grafana-swagger.cjs` | Grafana and Swagger UI | One search; needs an ingestion in the last hour |
| `capture-limits.cjs` | the opened card on a phone, the search's 429 | Spends the address's 60 searches of the minute |

```bash
node docs/user-manual/tools/capture-search.cjs
```

The scripts that change `mock-gds` run `docker compose` in the repository's root. From a git worktree whose stack runs from another checkout, set `COMPOSE_DIR` to that checkout. They only stop, start or recreate `mock-gds`: they never rebuild an image, remove a volume or touch another service.
