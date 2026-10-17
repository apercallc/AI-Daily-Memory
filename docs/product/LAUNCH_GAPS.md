# What is still missing to launch

A working review of the whole product (phone, watch, web, API, database, operations) on
2026-10-13. Items marked **built** were closed in the same pass; the rest need a decision or
an outside account that code alone can't supply.

## Resolved in production: the phone's default server serves the API

`aidailymem.apercallc.com` is the `web` service (static nginx). The API is the `api` service, whose
custom domain `api-aidailymem.apercallc.com` has no DNS record, so it is reachable only at its
Railway address. The shipped phone app (0.3.0) defaults to `https://aidailymem.apercallc.com`, so
sign-up from the app reached the static site, not the API (every path returns 200 HTML there,
which is why a health check on that host looked fine).

**Fix, deployed and verified 2026-10-13:** the web service's nginx proxies `/v1/` to the API, so
the web app and released phone builds can use the same origin without an app update. Live checks:

- `https://aidailymem.apercallc.com/v1/health` returns 200 with the expected API identity.
- `https://aidailymem.apercallc.com/v1/ready` returns 200 with `{"status":"ok","database":"ok"}`.
- The Railway API service domain `/v1/health` also returns 200.
- The web origin sends CSP, HSTS, `X-Content-Type-Options`, and `X-Frame-Options` headers.

The separate `api-aidailymem.apercallc.com` custom domain still has no DNS record. It is optional:
the shipped phone and web app use the working same-origin `/v1/` proxy.

## Built in this pass

| Gap | Now |
| --- | --- |
| No way to see or manage memory outside the phone | Web app at `/app/`: sign up and in, Today, Memory (search, filter, correct, dismiss, evidence), Account |
| No recall | `POST /v1/ask`: retrieval-first, abstains, metered, evidence-only unless the operator enables a model (ADR-0024) |
| No human correction | Your wording stored beside the original; dismiss/restore |
| API keys only via operator CLI | Create, list, revoke in the web app (read-only, capped, shown once) |
| No device/session list or remote revoke | `GET /v1/tokens`, `DELETE /v1/tokens/{id}` |
| No password change | `POST /v1/me/password` |
| No self-service export | `GET /v1/export` (JSON, includes corrections and held transcripts, no secrets) |
| Account deletion from a browser | Needs the password |
| Abuse protection | `usage_counters`, daily Ask limit (availability control, not paid-plan metering) |
| Readiness | `/v1/ready` checks the database |

## Built, needs your accounts and settings to switch on

| Feature | What exists | To turn on |
| --- | --- | --- |
| Password reset, email verification | Single-use hashed links, no account enumeration, web pages, resend | `MAIL_PROVIDER=resend`, `RESEND_API_KEY`, `MAIL_FROM` (a domain with SPF/DKIM), `PUBLIC_BASE_URL` |
| Dormant Stripe billing (ADR-0028) | Legacy checkout, portal, webhook, and plan limits remain in the codebase but must not be enabled; remove them before production | none; never configure Stripe for this product |
| Privacy policy, terms | Plain-language drafts at `/privacy.html` and `/terms.html`, linked from the site and app | A lawyer's review before relying on them |
| CLI | `interfaces/cli/adm.py`: `ask`, `day`, `search`, `decisions`, `tasks`, `commitments`, `--json`, key saved owner-only | Create an API key in Account |
| Dead-job tooling | `python -m app.admin dead-jobs` and `requeue-job` | none |

## Release decisions and remaining operator work

1. **Legacy server-side audio processing.** The current phone app transcribes on device and syncs
   notes only when the user enables sync; it does not upload audio for server processing
   (ADR-0026). The older server transcription and extraction providers remain for legacy upload
   clients. Keep those providers disabled; remove the legacy paths only with a migration and compatibility review.

## Operations gaps

- **Database backups** are not yet configured and verified; backup monitoring and a restore drill are production gates.
- **Uptime and alerting.** ~~Sentry is wired; no uptime monitor or status page.~~ Fixed
  2026-10-16: Sentry was wired in code but had never sent an event — `SENTRY_DSN` was unset on
  the live `api` service, confirmed via the Sentry API (`firstEvent: null`). Set it; the
  service redeployed healthy. Unhandled backend errors now reach Sentry. Still no uptime
  monitor, status page, or alert *rules* on top of it (what should page someone is an operator
  decision, not a code change).
- ~~**Deleted-account purge** retries forever on a permanent error (bounding it needs a migration).~~
  Fixed 2026-10-16: bounded at `MAX_ACCOUNT_PURGE_ATTEMPTS` with an operator-visible dead-letter
  state (migration 0015); see `python -m app.admin dead-account-purges`.
- **Single API replica.** The per-address limiter is in-process; scaling out needs a shared limiter.
- **Cost per user** is unmeasured and needs real provider usage and operating-cost data.

## Product gaps

- **Phone-only accounts have no web copy** until they connect an account and enable optional notes
  sync on the phone. Audio is never synced, and the phone remains the source of truth (ADR-0026).
- **Entities** (people, aliases, merge) and **recall evaluation** (Phase 4 benchmark) are not built;
  Ask is keyword-and-date retrieval, not entity-aware.
- **Not exercised on real hardware:** the phone and watch flows, voice detection on real speech,
  call and other-app mic interruptions, OEM battery behaviour.
- **iOS** is deliberately deferred; there is no committed delivery phase for it.
