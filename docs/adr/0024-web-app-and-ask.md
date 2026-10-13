# ADR-0024: Web App, Browser Sessions, and Ask

- Status: Accepted
- Date: 2026-10-13
- Builds on: ADR-0004 (evidence immutable), ADR-0014 (storage modes), ADR-0019 (extraction),
  ADR-0021 (REST-only integration). Amends none.

## Context

People could create an account but only see their memory on the phone. The product promise
includes asking questions and managing what was remembered; Phase 10 requires self-service
export, key management and deletion. The marketing site was static and the API sat on a
different host than the one the phone app defaults to.

## Decision

1. **A browser is a client of the public API, nothing more.** The web app (`website/app`, plain
   ES modules, no build step, no third-party scripts) calls `/v1/*` with a bearer token. No cookies,
   so no CSRF surface. The CSP forbids inline script and style.
2. **Browser sessions have their own scopes and expiry.** `client: "web"` at sign-up or sign-in
   issues `memory:read`, `memory:write`, `transcript:read`, `account:manage` and a 30-day expiry.
   They can never upload audio. Deleting the account from a browser needs the password again.
3. **Same origin.** The site's nginx proxies `/v1/` to the API service, so the web app has no CORS
   and released phone builds (default `aidailymem.apercallc.com`) reach the API without an update.
   The platform edge's `X-Real-IP` is forwarded so per-address limits still see the person.
4. **Corrections sit beside evidence.** A person's wording is a row in `memory_item_corrections`;
   the extracted text and the evidence are never edited. Dismissing hides an item and deletes
   nothing.
5. **Ask is retrieval first, and abstains.** Structured SQL (dates, kind) plus PostgreSQL
   full-text search over the person's own undismissed items, at most 20 candidates, no vectors.
   The answer is built only from those items. By default it lists them (no third party sees
   memory). `ASK_PROVIDER=claude` lets a model phrase the answer from those items only, with
   items delimited as untrusted data, no tools, and citations restricted to items it was given.
   Turning it on is an operator decision because it sends memory text to the provider.
6. **Metering is server-side.** `usage_counters` holds per-person per-day counts; Ask is limited
   per day. This is the first piece of Phase 10 metering, not entitlements.
7. **Export and keys are self-service.** `GET /v1/export` streams everything held (including
   transcripts and your corrections; never secrets). API keys stay read-only, hashed, capped at 10,
   and cannot manage keys or read transcripts.

## Consequences

- A leaked browser token can read and correct memory until it expires or is revoked from the device
  list; it cannot upload, and cannot delete the account without the password.
- Evidence-only Ask is useful but terse; a model improves phrasing, not coverage.
- Phone-only accounts have nothing server-side to browse or ask about unless they sync reports.
- The proxy makes the web service a dependency of the phone's sign-in path; the API's own public
  address still works.
