# Ingestion

The ingestion downloads the three providers, keeps each provider's offers whole, builds every itinerary and publishes them into PostgreSQL in one atomic switch. It runs in the `bootstrap` application (`IngestionJob`, `IngestInventoryService`) and is measured by the run described at the end. The reasoning behind each choice is in the [design spec](../planning/design-spec.md) (§4.3, §6.1 to §6.5, §6.10).

```mermaid
flowchart LR
    job[IngestionJob<br/>ShedLock] --> svc[IngestInventoryService]
    svc -->|3 downloads in parallel| prov[Alpha, Beta, Gamma<br/>retry + breaker + 2 time bounds]
    svc -->|replace per provider| offers[(provider_offer)]
    offers --> dedup[LegDeduplicator<br/>ItineraryBuilder stream]
    dedup -->|forEach into COPY| slot[(inactive slot)]
    slot -->|one transaction: switch| pub[(inventory_publication)]
```

## The run

1. **Downloads, in parallel.** One virtual thread per provider. A provider is called page by page (Alpha by cursor, Beta by page number, Gamma by offset, 1000 records per page, at most 200 pages and 8 MiB per body). Two time bounds apply: a request is cut at 2.5 s (`providers.request-timeout`) and the whole download of one provider at 60 s (`providers.download-timeout`).
2. **Retry and breaker.** A transient failure (a 5xx, a timeout, an I/O error) is retried up to 3 attempts in all, waiting 200 ms and then 400 ms. Each attempt goes through the provider's circuit breaker: over its last 10 calls it opens at 50% failures, stays open 60 s, then lets 3 trial calls through. A refused key (`401`), another client error, a redirect or an oversized body is not transient: it is not retried and the breaker ignores it.
3. **Whole or nothing, per provider.** A provider's offers replace its stored ones (`provider_offer`) only when its whole download succeeded, in one transaction. If any page fails after the retries, none of that run's pages is stored and the previous offers stay (status `KEPT`, with the `FailureReason`: `HTTP_ERROR`, `TIMEOUT`, `UNAUTHORIZED`, `CIRCUIT_OPEN`, `MALFORMED_PAGE`, `PAGINATION_LIMIT` or `UNEXPECTED`). A malformed record is not a failed download: it is discarded with its reason (`DiscardReason`), logged and counted, and the rest of the page is used.
4. **Itineraries.** The stored offers of all three providers are deduplicated into one leg per flight, and `ItineraryBuilder` returns a lazy stream of every itinerary of one to three legs. The stream is consumed only with `forEach`, straight into `COPY` (push mode, R4), so the roughly one million itineraries are never held in memory.

## The slots

Search will read `itinerary_a` or `itinerary_b`, whichever `inventory_publication.active_slot` names (double buffering, §6.5). A run writes the other one, in a single transaction (`JdbcItineraryRepository.replaceInventory`):

1. `SET LOCAL lock_timeout = '5s'`, so a run fails rather than waiting for ever on a lock.
2. `TRUNCATE` the inactive slot, then drop its day partitions before today.
3. Create the day partitions from today to today plus 37 days (`CREATE TABLE IF NOT EXISTS ... PARTITION OF`). A slot is `PARTITION BY RANGE (departure)` with one partition per UTC day, defined once in `PartitionCalendar`, and a `DEFAULT` partition for any departure beyond them (§4.3). The truncate comes first because PostgreSQL refuses to create a partition while the default one holds rows in its range.
4. `COPY` the itineraries in, as they are built.
5. `UPDATE inventory_publication` to the new slot, with `generation + 1`.

Search keeps reading the old slot until the transaction commits, then reads the new one. Any failure rolls the whole transaction back, so the published slot is never touched by a failed run.

Two outcomes publish nothing and leave the previous generation active:

- **`NOTHING_TO_PUBLISH`**: no provider has stored offers, or no itinerary can be built from them (an empty generation is never published).
- **`CEILING_EXCEEDED`**: the run would build more than `ingestion.max-itineraries` (2,000,000), so the writer throws and the transaction rolls back (R7).

## Schedule and lock

`IngestionJob` runs at startup and then 30 minutes after each run ends (`ingestion.interval`, fixed delay). It takes the ShedLock row `ingestion` (table `shedlock`, at most 29 minutes, at least 1 minute) so that one instance at a time runs; an instance that does not get it returns at once. Any exception of a run, taking the lock included, is logged at `ERROR`, counted as outcome `FAILED` and never escapes, so the next run still comes.

## When providers are down

A failed provider never empties the inventory: its last good offers are used again, and the run still publishes.

| Down | Result |
|---|---|
| 1 | `PUBLISHED`. The failed provider is `KEPT` with its reason and one `WARN` line; the other two are `FRESH`. |
| 2 | `PUBLISHED` from one fresh provider and two kept ones. |
| 3 | `PUBLISHED` from the three kept sets, if they hold offers; `NOTHING_TO_PUBLISH` if nothing was ever stored (for example the first run after a fresh install). Itineraries get no newer, but the quotes' age shows in the metrics. |

## Observability

Each run logs one `INFO` line, `Ingestion run ended`, as ECS JSON: `outcome`, `generation`, `itineraries`, `duration` and, per provider, `status`, `failure`, `fetched`, `discarded` (by reason), `used` and `lostToDeduplication`. Metrics use low-cardinality tags only (`provider`, `reason`, `outcome`):

| Meter | Meaning |
|---|---|
| `ingestion.duration{outcome}` | Timer of the runs that ended with a report |
| `ingestion.runs{outcome}` | Runs by outcome, `FAILED` for an exception |
| `ingestion.consecutive.failures` | Runs in a row that did not publish |
| `ingestion.provider.downloads{provider,status}` | Downloads, `FRESH` or `KEPT` |
| `ingestion.provider.last.fresh.seconds{provider}` | Age of the newest quote among the provider's stored offers |
| `ingestion.itineraries` | Itineraries in the last publication |
| `ingestion.partitions.default.rows` | Rows in the `DEFAULT` partition (should stay 0) |
| `ingestion.partitions.days.ahead` | Days from today to the last partition (37 after a run) |

## Running it

`docker compose up --build` starts the mock providers, PostgreSQL and the aggregator with `-Xmx512m` (`compose.yaml`, `bootstrap/Dockerfile`). The measured run, with its numbers, is in the [progress report](../planning/progress/2026-11-15.md#task-11--evidence). The application needs a web server (`spring-boot-starter-webmvc`) to stay alive between runs and to serve `/actuator/health`: without one the JVM exits after the first run.
