# API versioning policy

All client routes live under a major version prefix: `/v1/...`. Clients (Android, Wear via the
phone, CLI, and third-party API clients) pin the major version.

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

- `app.openapi_artifact` generates the committed `docs/api/openapi.v1.json` snapshot. CI fails
  when the generated contract differs from that file.
- On pull requests and pushes, CI compares the candidate against the base-branch snapshot with
  `app.openapi_compat`. Removed routes, operations, parameters, response fields, type changes,
  newly required request data, narrowed accepted input, and authorization-contract changes fail
  unless the API is versioned appropriately. Optional additive fields and routes remain allowed.
- The first committed snapshot establishes the v1 comparison baseline; subsequent changes are
  checked against the snapshot on the base branch.
