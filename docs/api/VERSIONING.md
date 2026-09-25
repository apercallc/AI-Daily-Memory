# API versioning policy

All client routes live under a major version prefix: `/v1/...`. Clients (Android, Wear via the
phone, CLI, MCP, third-party API keys) pin the major version.

## What may change inside `/v1`

Additive, backward-compatible changes only:

- new routes;
- new optional request fields (with server defaults);
- new response fields (clients must ignore unknown fields);
- new values in an open enum that the docs mark "may grow" (clients handle unknown values);
- new error `code`s for new failure modes (clients treat unknown codes by HTTP status).

## What requires `/v2`

- removing or renaming a route, field, or error `code`;
- making an optional request field required, or tightening its validation for existing
  valid inputs;
- changing a field's type, units, or meaning (for example, ms to seconds);
- changing idempotency or authorization semantics.

A new major version runs alongside the old one for at least 6 months after the last supported
app release that uses it. Deprecation is announced in the response header
`Deprecation: true` plus `Sunset: <date>`.

## Contracts outside the HTTP routes

- The daily report is versioned by its own contract (`contracts/daily-report.v1.schema.json`);
  additive fields make a minor version (v1.1), breaking changes a new file (v2).
- Error envelopes (`app/errors.py`) are part of every version's contract: clients branch on
  `error.code`, which never changes meaning once published.

## Enforcement

- `tests/test_app.py` checks the OpenAPI document is generated; CI will diff it against the
  last released document and fail on breaking changes (TODO Phase 1: "Validate breaking API
  changes").
