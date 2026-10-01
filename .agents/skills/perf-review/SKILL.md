---
name: perf-review
description: Performance review against this repo's budgets — battery, memory, jank, and foreground-service reliability on Android; query and latency efficiency on the backend — plus the evidence rule for perf claims. Use when the user mentions performance, battery, memory, jank, slow queries, or optimization, and proactively for anything on the recording hot path or backend retrieval.
---

# Performance review

Recording runs as a long-lived foreground service, so battery, memory, and jank are
correctness concerns. Backend retrieval has documented efficiency rules. Perf claims
need measurements.

## Android checklist

- **Main thread:** zero blocking I/O; no heavy allocations in composition paths.
- **Lifecycle hygiene:** coroutines, listeners, receivers, wake locks all released
  on session end — in a 24h service, a leak is a battery bug.
- **Recomposition:** stable/immutable parameters, no unstable lambdas in hot paths,
  `remember`/`derivedStateOf` scoped correctly; verify with recomposition counts for
  screens that update per tick (session timer, status).
- **Service reliability:** no restart storms, bounded work, no polling where events
  work.
- **Targets:** internal targets materially better than Play bad-behavior thresholds
  (docs/quality/QUALITY_GATES.md); Macrobenchmark for startup/critical flows before
  release; Baseline Profiles for release builds.

## Backend checklist (from README "Retrieval Efficiency Rules")

- Never send full history by default; filter before vector search.
- Embed useful units (events, memory items) — not thousands of tiny transcript chunks.
- Rerank narrowly; fetch evidence last; cache stable navigation views.
- Keep evidence authoritative.
- Queries align with the documented DB indexing; flag N+1s, unbounded result sets,
  retry storms, unbounded queues.

## Evidence rule

Any claim of improvement (faster, lighter, less jank, fewer queries) requires a
measurement recorded per docs/performance/. If numbers are missing, ask for them —
a perf change without before/after data isn't done.

## Report format

```
Perf review: <surface>
Findings: [BLOCKER|MAJOR|MINOR] file:line — what, why, fix
Evidence: measurements present/requested for each claim
```