# ADR-0002: Offline-First Capture

- Status: Accepted
- Date: 2026-09-22

## Context

Network availability cannot be required for dependable daily capture.

## Decision

Audio and authoritative recording state are persisted locally first. Cloud synchronization is asynchronous and retryable.

## Consequences

- recording survives backend/network outages
- local recovery and retention logic become critical
- synchronization must be idempotent
- storage-pressure handling is required
