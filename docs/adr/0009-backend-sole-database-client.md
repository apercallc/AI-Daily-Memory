# ADR-0009: Backend Is the Only Database Client; User Scoping From the First Migration

- Status: Accepted
- Date: 2026-09-25

> **Superseded in part by [ADR-0021](0021-public-rest-api-only.md):** references to MCP below are historical. The public REST API is the only external integration surface; there is no MCP server.

## Context

README proposes Supabase (managed PostgreSQL + Auth) and a single Memory Query Service
(ADR-0005). Supabase also exposes an auto-generated REST/GraphQL Data API over the
`public` schema. If that API stays reachable with the anon key and any table lacks a
Row-Level Security policy, recorded data is readable without going through our
authorization, ranking, or evidence rules. Separately, "auth later" (README) can tempt a
schema without `user_id`, which would make multi-user authorization, export, and account
deletion (AGENTS.md rules 3–5) a painful retrofit.

## Decision

- **Only the FastAPI backend and its worker connect to PostgreSQL.** Android, Wear, MCP,
  CLI, and web never hold database credentials or use the Supabase Data API.
- The Supabase Data API is **disabled** (or all app tables live outside exposed schemas).
  RLS is enabled with deny-all as a defense-in-depth backstop, not as the primary authz.
- Authorization lives in the application layer, inside the Memory Query Service, and every
  query is parameterized by the authenticated `user_id`.
- **Every user-owned table carries `user_id` from its first migration**, even while the
  only user is the developer. Foreign keys to user-owned parents use `ON DELETE CASCADE`
  so account deletion is one well-tested operation.
- Object storage keys are prefixed `users/{user_id}/...` so isolation and deletion work by
  prefix, and signed URLs are scoped to a single object.
- Separate database roles: migration owner, application (DML only), read-only analytics.

## Consequences

- One authorization boundary, testable with cross-user access tests.
- Supabase is used as managed Postgres + Auth only; moving off it later is straightforward.
- Supabase Realtime/Storage client features are not used by apps.
