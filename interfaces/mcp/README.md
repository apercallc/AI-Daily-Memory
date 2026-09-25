# MCP Interface

A read-only MCP server over the backend's Memory Query API (`GET /v1/reports/{memory_day}`,
`app/api/reports.py` in `backend/`). Never connects to PostgreSQL directly.

```bash
uv sync --extra dev
ADM_BASE_URL=https://api.example ADM_API_KEY=adm_your_own_token uv run python -m app.server
```

One process per account, authenticated as that account's **own** API key — never a shared
service credential (TODO.md "MCP transport auth"). A key defaults to the `memory:read` scope
(`backend/app/auth.py`) and this server never writes.

## Tools

| Tool | Returns |
| --- | --- |
| `get_daily_report(memory_day)` | The full report for that day. |
| `get_day(memory_day)` | Alias for `get_daily_report`. |
| `get_timeline(memory_day)` | The day's sessions, in order. |
| `get_decisions(memory_day)` | Decisions extracted that day, each with confidence and evidence. |
| `get_action_items(memory_day)` | Action items, each with an owner and optional due date. |
| `get_commitments(memory_day)` | Commitments, each with a direction and counterpart. |

Every tool reads one already-bounded daily report (each list capped at 50 items server-side,
`backend/app/reports/daily.py`); none returns raw transcript text or a bulk export.

## Entitlements

Reports only ever exist for a `full_cloud` account (ADR-0014, ADR-0019 — the only mode where the
backend ever holds a plaintext transcript to extract from). A call for any other account gets a
clear tool error naming that, not a silently empty result.

## Not implemented

- **`ask_memory` / `search_memory`** — need semantic recall, which crosses an architecture gate
  (README Gate F) this repo hasn't crossed. Implementing them here would be exactly the kind of
  speculative gate-crossing AGENTS.md rules out.
- **`get_highlights`** — no highlight data exists yet (TODO.md Phase 2's Highlights are unchecked).
- **Rate limiting**, **key rotation/revocation UI**, and **per-key audit logging** — all still
  open TODO.md items shared with the API-key surface generally, not specific to MCP.
