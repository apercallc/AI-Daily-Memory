# ADR 0001 — Android-first client and modular monolith backend

Status: Accepted

## Context

AI Daily Memory needs to prove reliable all-day capture and useful recall before investing in platform breadth or distributed infrastructure.

## Decision

- Build Android first.
- Keep Wear OS as a phone controller initially.
- Keep deterministic recording-domain logic in a pure Kotlin module.
- Use a single FastAPI backend with internal modules for API, processing, memory, and retrieval.
- Use PostgreSQL as the primary durable data store.
- Add infrastructure such as Redis, dedicated search, Kafka, or microservices only when measured bottlenecks justify it.
- Use one Memory Query API for Android, MCP, CLI, web, and future clients.

## Consequences

Benefits:

- lower operational complexity
- simpler testing and local development
- easier evidence/authorization consistency
- lower cost during product validation

Tradeoffs:

- some components may need extraction later
- scaling is initially vertical / worker-based rather than service-based

## Revisit when

- one workload needs materially different scaling characteristics
- deployments interfere with unrelated workloads
- PostgreSQL or the worker model becomes a measured bottleneck
- organizational ownership requires service separation
