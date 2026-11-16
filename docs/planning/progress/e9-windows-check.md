# E9 — Clean-clone check on Windows (plan 7, extra E9)

**Result: it works on Windows, with three findings.** From a clean clone, `docker compose up --build` builds every image and, at the second `up`, all eleven services are healthy; every README check passes, the dashboard searches and re-prices, and the admin screen signs in through Keycloak. On this slow machine it did not work at the first attempt: Keycloak outran its healthcheck (finding 1), the ingestion's `COPY` can outrun its 5-minute statement timeout (finding 2), and the README's token command needs a Python that Windows does not ship (finding 3).

## Environment

| Item | Value |
|---|---|
| Date and time | 2026-11-16, 12:11–13:45 UTC (13:11–14:45 CET) |
| Commit | `0ddb3a9` Continuation point: Task 10's final review and delivery docs in flight (`main`, cloned fresh) |
| Windows | Windows 11 Home 10.0.26200 (build 26200), AMD Ryzen 5 3500U (4 cores, 8 threads), 6.9 GB of RAM, other workloads stopped for the check |
| Docker Desktop | 4.99.0 (Engine 29.9.3, Compose v5.7.0), WSL 2 backend (WSL 3.0.5); the VM was given 4 GB of memory and 4 GB of swap for the check |
| git | 2.56.0.windows.4 (Git Bash) |
| `core.autocrlf` | `true`, from Git for Windows' system config, left unchanged as a reviewer would have it |

A first attempt on commit `ce0d93c`, with the VM capped at 2 GB and the host's other workloads running, was aborted during the image pulls because the host ran out of free memory; its only result, the line endings, is the same as below.

## Startup

| Step | Result |
|---|---|
| Line endings of the checkout | ✅ `.gitattributes` wins over `autocrlf=true`: 651 files LF, only `gradlew.bat` CRLF, 1 binary. Every script and every file compose mounts is LF, so no container failed on CRLF |
| Ports 3000, 3001, 8080, 8081, 8180, 9090 | ✅ Free; no other stack to stop |
| `docker compose up --build -d --wait` | ❌ **Exit 1 after 1,409 s (23.5 min)**: `keycloak` unhealthy, so `app` and `frontend` were not started. Every image built (the two Gradle `bootJar` stages took most of the time) and the other eight services were healthy |
| `docker compose up -d --wait` again, as a reviewer would | ✅ **Exit 0 after 65 s**, all eleven services healthy |
| Peak memory | 2.8 GB summed over the containers (`docker stats`, sampled every 20 s); the WSL VM's resident memory peaked at 2.2 GB on the host |

## Checks (README, curl from Git Bash)

| Check | Result |
|---|---|
| `GET /health` | ✅ 200 `{"status":"UP"}` |
| `GET /api/itineraries?origin=MAD&destination=JFK&limit=5` | ✅ 200, 5 itineraries, cheapest first (€377.43 direct), `nextCursor` given |
| Page 2 with its `nextCursor` | ✅ 200, 5 itineraries, none repeated from page 1 |
| `POST /api/itineraries/{id}/reprice` | ✅ 200 `PRICE_CHANGED`, `previousPrice` €377.43, new €369.01 |
| `origin=MADRID` | ✅ 400 problem+json: "origin must be a three-letter airport code, like MAD" |
| `maxPrice=0` | ✅ 400 problem+json: "maxPrice must be an amount in euros above 0, with at most two decimals, like 150 or 149.99" |
| Keycloak ready (README step 1) | ✅ |
| Token with the README's command (`python3`) | ❌ **`python3` is the Microsoft Store alias** on a stock Windows: it prints "Python was not found…" (translated from the laptop's Spanish locale) and exits 49, so `TOKEN` is empty (finding 3) |
| Token with the README's alternative (`jq -r .access_token`) | ✅ (jq installed by hand; Windows does not ship it either) |
| `GET /api/admin/status` without a token | ✅ 401 with `WWW-Authenticate: Bearer resource_metadata=…` |
| `GET /api/admin/status` with the token | ✅ 200: generation 1, three providers `UP`, breakers `CLOSED`, 3,673 / 3,588 / 3,728 legs, discarded records by reason |
| `POST /api/admin/ingestions` | ✅ 202. That run then failed (lock timeout, below); the next run published generation 2, so the generation rises: 1 → 2 |
| `http://localhost:3000/` | ✅ 200 |
| Grafana `http://localhost:3001` | ✅ 200, no login |

**In a browser** (Playwright driving the installed Google Chrome): a MAD → JFK search shows 20 cards with the filters in the URL; "Load more" goes to 40; opening the first card re-prices it ("Price changed: was €369.01, now €371.51", "Select"); `/admin` goes to Keycloak's login, `admin` / `demo-admin-password` signs in, and the screen shows generation 2, the next ingestion, and Alpha, Beta and Gamma up with their breakers, legs and discarded records.

## Ingestion runs

| Run (UTC) | Result |
|---|---|
| At startup, 12:38 | ❌ `An ingestion run failed` at 12:43:26: `COPY itinerary_b … canceling statement due to statement timeout` at line 891,982 of ~1M (`StatementTimeout.STORE`, 5 min). The host's other workloads were restarting during this run and took memory back |
| Manual (`POST /api/admin/ingestions`), 12:45:24 | ✅ Published generation 1 at 12:51:11, about 5 min 47 s for the whole run |
| Manual, 13:02 | ❌ `An ingestion run failed` at 13:03:53: `SELECT active_slot FROM inventory_publication FOR UPDATE … lock timeout`. A stale-origin refresh, set off by the check's searches, held the publication; that refresh then failed too at 13:08:08, with the same `COPY` statement timeout |
| 13:13:26 | ✅ Published generation 2 at 13:17:19, about 4 min |

Each failure was logged as one `ERROR` line with its cause. Before the first publication the search answered 200 with no itineraries; after it, the failed runs and refresh left search serving the last published generation.

## Logs

- `app`: 440 `WARN Discarded a provider record` (the mocks' malformed records, by design), 2 `ERROR An ingestion run failed` and 1 `ERROR The refresh of a stale origin failed` (above), 1 `WARN Skipped storing a re-price` (a run held the inventory), and springdoc's two startup notices. No other error.
- `postgres`: only the two statement timeouts and the lock timeout above.
- `prometheus`: 10 `Error refreshing DNS targets … could not resolve "app"` before `app` was created; `loki`: 3 "empty ring" / deadline errors at startup; `tempo`: "no jobs found" warnings; `grafana`: `can't read … provisioning/plugins` and `provisioning/alerting` (the folders do not exist) plus SQLite "database is locked, retrying" at startup. All startup noise; none of them stopped a service.
- `valkey`, `keycloak`, `mock-gds`, `frontend`: no error.

## Findings and proposals

1. **Keycloak outruns its healthcheck on a slow machine, so the first `docker compose up --build` fails.** Keycloak 26.8.1 `start-dev` started in 156.7 s of JVM time, about 250 s after its container was created; the healthcheck allows 40 s of `start_period` plus 10 retries every 10 s, about 140 s, and compose then refuses to start `app` and `frontend`. *Proposal:* raise `keycloak`'s `start_period` to 300 s (or its `retries`); a start period ends as soon as the check passes, so a fast machine waits no longer. Consider the same margin for `app`, whose own `start_period` is 40 s.
2. **The ingestion's `COPY` can outrun `StatementTimeout.STORE` (5 min) on a slow host.** The startup run and a refresh both failed at about 890k and 500k rows; a quiet run took 4–6 min end to end where the Mac takes about 30 s. After a failed startup run the dashboard shows no flights until the next run, 30 minutes later, or a manual one. *Proposal:* give the store's bound more margin or make it a setting, and say in the README that on a slow machine the first flights can take several minutes (and that "Run ingestion now" retries).
3. **The README's token command needs `python3`, which a stock Windows does not have** (the name runs the Microsoft Store alias), and the `jq` alternative is not shipped either. *Proposal:* a command with no extra tool, for example `| sed -E 's/.*"access_token":"([^"]+)".*/\1/'`, or a PowerShell variant with `Invoke-RestMethod`.
4. **The README does not say how much memory Docker needs.** Measured here: the containers peak at about 2.8 GB; with the VM capped at 2 GB the first attempt could not even finish pulling on a busy host. *Proposal:* state "Docker needs about 4 GB of memory" in "Run the aggregator", and that the first `--build` takes several minutes (23 here).
5. *Minor:* Grafana logs two errors at startup for the missing `provisioning/plugins` and `provisioning/alerting` folders; empty folders (or not mounting them) would silence them.

## Afterwards

Finding 1 was fixed on 2026-11-16 by the user's decision: Keycloak's health check now has a `start_period` of 300 s (`compose.yaml`, pinned by `ComposeFileTest.waitsFiveMinutesForKeycloakToStartBeforeCallingItUnhealthy`), so one `docker compose up --build` boots the stack on a machine like this one. Findings 2 to 5 are documented in the README's "Requirements" (minimum hardware and what to do on an older machine).
