# ADR-0005: Single Memory Query Service

- Status: Accepted
- Date: 2026-09-22

> **Superseded in part by [ADR-0021](0021-public-rest-api-only.md):** references to MCP below are historical. The public REST API is the only external integration surface; there is no MCP server.

## Context

Android, MCP, CLI, API, and future web clients all need memory retrieval. Duplicating retrieval logic would cause inconsistent authorization, ranking, privacy rules, and results.

## Decision

All clients use one Memory Query Service/API. MCP and CLI do not access the database directly.

## Consequences

- one authorization boundary
- one retrieval/ranking implementation
- consistent evidence behavior
- easier future schema changes
