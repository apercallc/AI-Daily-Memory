# ADR-0029: Durable Security Audit Trail

- Status: Accepted
- Date: 2026-10-27
- Relates to: ADR-0009 (backend is the sole database client), ADR-0014 (where memory lives), SECURITY.md

## Context

Structured logs (`app.observability`) are redacted and carry request IDs, but they go to the
platform's stdout and rotate on the platform's schedule. Nothing durable answers "who signed
in to this account, from which kind of client, and when was it locked or deleted?" after an
incident, a support request, or a compliance question months later.

Two product rules constrain the answer. Users own their data and can delete the account
completely, and logs must never carry transcripts, audio, credentials, or other content.

## Decision

1. Authentication and account-lifecycle decisions write one row to `audit_events`, in the same
   transaction as the change they describe: sign-up, login (success, failure, and lockout),
   password change, session and API-key revocation, API-key creation, and account deletion.
2. A row holds an event name, an outcome, a timestamp, the request ID that joins it to the
   structured log, a small non-identifying `detail` object (failure code, client kind, keyed
   source hash), and `actor_ref`: an HMAC-SHA256 of the user ID under the server key. It
   holds no email, no address, no token, no content.
3. The table has no foreign key to `users`, so it survives account deletion. That is
   compatible with the right to deletion because `actor_ref` is not reversible and the
   user's credential, tokens, and content are all removed; the remaining row says only that
   an unidentified account performed an action at a time.
4. The table is append-only. A database trigger rejects `UPDATE` and `DELETE`. Retention is
   indefinite by default: rows are small, and there is no purge job. Any future retention
   limit needs its own ADR and a deliberate, reviewed migration.
5. `actor_ref` uses `LOGIN_SOURCE_KEY`. Rotating that key severs the link between old and new
   rows for the same account. That is acceptable for pseudonyms but should be a deliberate
   act, noted in the incident log.

## Consequences

- Handlers that decide an authentication outcome call `audit.record(...)` before committing.
  A rolled-back change leaves no audit row, and a committed one cannot lack it.
- Operators can reconstruct an account's history by computing its `actor_ref` and querying by
  time. `docs/operations/DEPLOYMENT.md` describes the query.
- Database roles (a remaining production gate) should grant the application role `INSERT` and
  `SELECT` only on `audit_events`, so the trigger is a second control rather than the only one.
