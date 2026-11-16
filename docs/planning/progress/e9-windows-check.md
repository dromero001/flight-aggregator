# E9 — Clean-clone check on Windows (plan 7, extra E9)

**Result: incomplete.** The clone and its line endings were checked and are correct. The stack was not started: the run was aborted during the image pulls because the Windows host ran out of free memory, and the host also runs other workloads that had to be protected. No defect of the project was found; the README states no memory requirement for Docker (proposal 2 below).

## Environment

| Item | Value |
|---|---|
| Date and time | 2026-11-16, 13:02 CET (12:02 UTC) |
| Commit | `ce0d93c` Write SOLUTION.md: architecture, modelling, pagination at 1M, assumptions, limitations and AI usage |
| Windows | Windows 11 Home 10.0.26200 (build 26200), 6.9 GB of RAM, AMD Ryzen 5 3500U (8 logical CPUs) |
| Docker Desktop | 4.99.0 (Engine 29.9.3, Compose v5.7.0), WSL 2 backend (WSL 3.0.5) |
| Docker memory | 2 GB (`MemTotal 1997185024`): the WSL 2 VM is capped by the user's `.wslconfig` (`memory=2GB`, `swap=2GB`) because the PC runs other workloads |
| git | 2.56.0.windows.4 (Git Bash) |
| `core.autocrlf` | `true`, from Git for Windows' system config (`C:/Program Files/Git/etc/gitconfig`), left unchanged as a reviewer would have it |

## Checks

| # | Check | Result |
|---|---|---|
| 1 | `git clone` into an empty folder with the machine's default git config | ✅ Cloned `ce0d93c` |
| 1b | Line endings of the checkout (`git ls-files --eol`) with `core.autocrlf=true` | ✅ `.gitattributes` wins: 651 files LF in the working tree, only `gradlew.bat` CRLF (as intended), 1 binary (`gradle-wrapper.jar`). Every script (`gradlew`, `frontend/nginx/*.sh`, `.claude/hooks/*.sh`) and every file compose mounts (`deploy/**`, the Keycloak realm) is LF, so the CRLF failure mode is ruled out on this checkout |
| 2 | Docker Desktop running; ports 3000, 3001, 8080, 8081, 8180 and 9090 free | ✅ Engine ready; no container and no listener on those ports, nothing to stop |
| 3 | `docker compose up --build -d --wait` and the time to healthy | ❌ **Aborted after about 1.5 min, during the image pulls** (only `otel/opentelemetry-collector` had been pulled; no image built, no container created). Cause below. Time to healthy: not measured |
| 4 | `/health`, search and page 2, re-price, the 400s, the admin token, `/api/admin/status` and its 401, `POST /api/admin/ingestions` and the generation, the dashboard on 3000, Grafana on 3001, the browser flow | ⏭ Not run: the stack never started |
| 5 | `docker compose logs app`, logs of failed containers | ⏭ Not run: no container was created |
| 6 | `docker compose down -v` | ✅ Run; Docker Desktop stopped and `wsl --shutdown` |

## Why it was aborted

The host's free memory was sampled every 30 s while compose pulled the images:

| Time (CET) | Free RAM | Free commit (virtual memory) |
|---|---|---|
| 13:02:37 | 0.20 GB | 0.63 GB |
| 13:03:08 | 0.10 GB | 0.47 GB |
| 13:03:39 | 0.05 GB | 0.30 GB |
| 13:04:09 (after the abort) | 0.68 GB | 2.13 GB |

The WSL 2 VM already held 1.6 GB of committed memory before any build started. The build stages (Gradle's `bootJar` for `app` and `mock-gds`, `npm ci` and `npm run build` for the frontend) and then eleven containers (two JVMs, `app` at `-Xmx512m`, plus Keycloak, PostgreSQL with ~1M itineraries, Valkey, nginx, the Collector, Prometheus, Tempo, Loki and Grafana) would have needed more than the 2 GB cap and more than the host could commit without risking the other workloads. The run was stopped on purpose; it did not fail on its own.

## Proposals

1. **Rerun E9 on a Windows machine with enough free memory for Docker**, or on this PC with its other workloads paused and the `.wslconfig` cap raised for the duration of the check. Steps 3 to 5 are the ones still to prove.
2. **State the memory the stack needs in the README** (section "Run the aggregator"), for example "Docker needs about N GB of memory for `docker compose up --build`". Measure N on the Mac with `docker stats` at the peak (during the build, then during the first ingestion run), and add a margin. A reviewer whose Docker Desktop is set to less memory would otherwise see the stack fail or stall with no hint of the cause.
