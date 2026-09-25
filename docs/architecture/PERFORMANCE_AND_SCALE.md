# Performance and Scale Plan

How AI Daily Memory stays fast on the phone and scales to many users on the backend without
adding infrastructure before it's needed (README Gate G). Numbers marked *provisional* are
replaced by measurements in `docs/performance/`.

## Load model (what "a user" costs)

| Quantity per active user-day | Estimate | Driver |
| --- | --- | --- |
| Recorded audio | 8–12 h | "Start my day" |
| Local bytes | ~90–130 MiB at ~24 kbit/s Opus (*provisional*) | codec (ADR-0007) |
| Speech-active share | unknown (measure in Phase 0) | sets transcription cost |
| Segments | ~500–1,500 at 30–60 s | segment length |
| Transcript rows | ~2–10k | speech share × segmentation |
| Memory items | tens | extraction |
| API requests | hundreds (uploads + sync + reads) | segment count |

**Implication:** writes are per-segment and roughly uniform; reads are small and bursty
(timeline, ask). The first bottleneck is cost (ASR/LLM), then worker throughput. The API
tier comes much later.

## Phone (the hot path)

| Concern | Design | Guard |
| --- | --- | --- |
| Audio thread | Allocation-free per-buffer work (silence detector scans backwards and exits on the first non-zero); no logging per buffer | `DigitalSilenceDetector` |
| Encoding | Hardware or low-complexity Opus; segment rotation off the audio thread | Phase 0 benchmark |
| Disk I/O | One sequential append stream; fsync only at seal, not per buffer | ADR-0007 |
| Database | Room writes per segment/transition (~1/min), batched in one transaction | Phase 0 |
| Wake locks | Mic FGS keeps capture alive; no extra wake lock unless measurements prove a need | ADR-0010 |
| Uploads | WorkManager with network + battery-not-low constraints; batched; never on the audio thread | Phase 1 |
| UI | Stateless composables with stable, immutable models (`CaptureStatusModel`); state hoisted | `CaptureScreen` |
| Startup | Nothing heavy in `Application.onCreate`; recovery decision is pure and O(1) | `StartupRecovery` |

Budgets to set from Phase 0 evidence (QUALITY_GATES.md): battery %/h while recording,
CPU %, RSS MiB, bytes/h, cold start ms, jank %.

## Backend: stages of scale

| Users (active) | Shape | Changes when |
| --- | --- | --- |
| 1–100 | 1 API + 1 worker, managed Postgres, R2 | — |
| 100–5k | N stateless API replicas behind LB; M workers; PgBouncer (transaction mode) | CPU > 60% p95, or connections > 70% of the limit |
| 5k–50k | Workers split by stage (ASR vs. LLM); read replica for timeline/search | job age SLO missed; primary CPU from reads |
| 50k+ | Partition `transcript_segments` / `audio_segments` by time; managed queue if the Postgres job table is the bottleneck | measured table/index bloat; job-claim latency |

Each step is triggered by a metric, not a guess (Gate G).

### Rules that keep each stage cheap

- **Stateless API.** No in-process session state, so any replica can serve any request.
  Request context lives in `contextvars` (implemented).
- **Bodies are small.** Audio never passes through the API. Clients upload directly to
  object storage via signed URLs; the API body limit (1 MiB by default) enforces this.
- **Every query is user-scoped and indexed** by `(user_id, …)`; no cross-user scans.
- **Keyset pagination only** (`WHERE (occurred_at, id) < (:t, :id) ORDER BY … LIMIT n`),
  never `OFFSET`; hard maximum page size.
- **Job table claims with `SELECT … FOR UPDATE SKIP LOCKED`** + lease expiry. This scales
  to thousands of jobs/s on Postgres before a broker is justified.
- **Idempotency keys are unique indexes**, so retries are cheap no-ops, not read-then-write races.
- **Bounded fan-out.** Retrieval returns a bounded candidate set; evidence expansion is a
  bounded window; LLM context is capped (README "Retrieval Efficiency Rules").
- **Timeouts everywhere.** Uvicorn/proxy request timeout, DB `statement_timeout` per role,
  provider calls with explicit connect/read timeouts, and a total deadline per job attempt.
- **Backpressure, not buffering.** Per-user and per-key rate limits (429 with
  `Retry-After`). Workers pull; queues don't grow in memory.
- **Cache only derived, stable views** (finished-day timeline, summaries), keyed by
  `(user_id, local_date, extraction_version)`. Evidence is never cached as the source of truth.

### Hot-spot risks and mitigations

| Risk | Mitigation |
| --- | --- |
| End-of-day spike: many phones upload at once on Wi-Fi at night | Clients add jitter to scheduled uploads; workers autoscale on job age, not count |
| One heavy user hogs workers | Fair scheduling: claim jobs round-robin by user (`DISTINCT ON user_id` batch) |
| Provider outage | Jobs back off and wait; capture is unaffected (offline-first); alert on job age |
| Large accounts slow down the timeline | Per-day materialized views/caches; partition pruning by time |
| Full-text index bloat | `tsvector` generated column + GIN; monitor bloat; `fillfactor` tuning if needed |
| Connection storms after a deploy | PgBouncer; pool sizes per role; readiness checks before traffic |

## Observability that scale depends on

Implemented now: request ID on every request and log line; JSON logs; access log with
method, path, status, and duration (no query strings); unhandled errors logged by type and
frames only.

Next (Phase 1): p50/p95 per route, job age per stage, provider latency/error rate,
cost counters per user (`usage_counters`), and Sentry with PII scrubbing.

## Verification

- Phone: Macrobenchmark + endurance runs (1/4/8/12 h), results in `docs/performance/`.
- Backend: load test (k6 or Locust) against the upload-finalize and timeline endpoints at
  10× the expected peak before public launch. `EXPLAIN (ANALYZE, BUFFERS)` for every new
  query in PR review.
