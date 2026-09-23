# ADR-0005: Single Memory Query Service

- Status: Accepted
- Date: 2026-09-22

## Context

Android, MCP, CLI, API, and future web clients all need memory retrieval. Duplicating retrieval logic would cause inconsistent authorization, ranking, privacy rules, and results.

## Decision

All clients use one Memory Query Service/API. MCP and CLI do not access the database directly.

## Consequences

- one authorization boundary
- one retrieval/ranking implementation
- consistent evidence behavior
- easier future schema changes
