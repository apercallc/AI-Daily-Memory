# MCP Interface

MCP is a read-only access layer over the shared Memory Query API.

Initial tools:

- ask_memory
- search_memory
- get_day
- get_timeline
- get_decisions
- get_action_items
- get_commitments
- get_highlights

Rules:

- Do not connect directly to PostgreSQL.
- Enforce user-scoped authorization through the backend.
- Return bounded structured results.
- Return evidence references.
- Do not expose bulk raw transcripts by default.
