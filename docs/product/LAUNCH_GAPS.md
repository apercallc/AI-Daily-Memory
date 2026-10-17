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
  Checked 2026-10-17: Railway's managed Postgres backup/restore settings are a dashboard-only
  feature on this plan — there is no CLI (`railway` CLI has no `backup`/`snapshot` subcommand)
  or public API surface an agent session can read or configure. An operator needs to open the
  Postgres service's dashboard, confirm what backup retention is actually enabled, and run a
  real restore drill; this cannot be verified or closed from here.
- **Uptime and alerting.** ~~Sentry is wired; no uptime monitor or status page.~~ Fixed
  2026-10-16: Sentry was wired in code but had never sent an event — `SENTRY_DSN` was unset on
  the live `api` service, confirmed via the Sentry API (`firstEvent: null`). Set it; the
  service redeployed healthy. Unhandled backend errors now reach Sentry. Still no uptime
  monitor, status page, or alert *rules* on top of it (what should page someone is an operator
  decision, not a code change).
  Re-verified 2026-10-17: `SENTRY_DSN` is genuinely set on the live `api` service and points at
  the correct project (`ai-daily-memory-backend`, confirmed by decoding the DSN's project id,
  not just its org id, against the Sentry API's project list — an earlier read of this session
  briefly mis-flagged it as wrong before re-checking). `firstEvent` is still `null`, but that is
  explained, not a bug: recent Railway logs show a clean run with no 5xx responses or exceptions
  logged, so there is simply nothing to have reported yet — confirmed `initialize_sentry()` and
  `capture_unhandled_error()` are correctly wired in both `main.py` (app + middleware boundary)
  and `worker.py`.
  Alert *rules* still do not exist and still need a human. Attempted to configure them via the
  Sentry API this pass (token has full `alerts:write`/`org:admin` scope) — every issue-alert-rule
  endpoint tried (`/projects/{org}/{project}/rules/`, `/organizations/{org}/alert-rules/`, and
  their region-host (`us.sentry.io`) equivalents) returned 404 even though project-detail and
  project-list calls against the exact same org/project succeed, so this is not a permissions
  problem — it looks like this Sentry plan restricts issue-alert-rule CRUD to the web UI only.
  Recommended defaults to enter manually (Settings → Alerts → Create Alert Rule, project
  `ai-daily-memory-backend`): (1) "An issue is first seen" → notify immediately (catches a new
  class of error on a new deploy); (2) "An issue is seen more than 10 times in 1 hour" → notify
  (catches a spike/outage without paging on a single one-off); (3) no uptime/synthetic monitor
  existed at all until this pass. Closed that part: `ai-daily-memory-health-watchdog` is a
  Hermes cron job (every 10 minutes, script-only, no LLM call) that pings `/v1/health` and
  `/v1/ready` and stays completely silent when both return 200 — only a real failure (wrong
  status code) sends a Telegram message, and recovery sends one "healthy again" message so the
  all-clear is visible too. Verified both branches for real (a manual run against the live,
  healthy backend produced no output; a simulated 404 against a real non-existent path produced
  the expected alert text) before scheduling it. This covers uptime; it is not a substitute for
  Sentry's own alert rules above, which still need the manual UI step. Lives outside this repo,
  as a Hermes-session-local resource, not a deployable artifact: the script is
  `~/.hermes/scripts/aidm-health-watchdog.sh` on the operator's machine and the schedule is
  registered in that Hermes instance's own cron store — it is not committed here and would need
  recreating (`cronjob_manage`) on a different Hermes instance or operator machine.
- ~~**Deleted-account purge** retries forever on a permanent error (bounding it needs a migration).~~
  Fixed 2026-10-16: bounded at `MAX_ACCOUNT_PURGE_ATTEMPTS` with an operator-visible dead-letter
  state (migration 0015); see `python -m app.admin dead-account-purges`.
- **Single API replica.** The per-address limiter is in-process; scaling out needs a shared limiter.
- **Cost per user** is unmeasured and needs real provider usage and operating-cost data.
- **Load/chaos tests for worker retry behavior.** The legacy upload-processing worker is not
  deployed (Gate B providers disabled, ADR-0026), so there is no live worker to load-test in
  production. What *is* agent-doable and now done: `app/jobs.py` and `app/retention.py` already
  had extensive fault-injection coverage (dead-lettering, lease-expiry races, fenced stale
  owners, bounded retry, mid-sweep race conditions) — the one genuine gap was that every
  concurrency test simulated a single competing claimant via a held raw-SQL lock, never real
  concurrent workers. Added `test_concurrent_workers_never_double_claim_or_lose_a_job`
  (`tests/test_jobs.py`): 6 real concurrent async workers draining a shared pool of 12 jobs via
  `asyncio.gather`, asserting every job is claimed by exactly one worker and none are lost —
  verified stable across 5 repeated runs.

## Product gaps

- **Phone-only accounts have no web copy** until they connect an account and enable optional notes
  sync on the phone. Audio is never synced, and the phone remains the source of truth (ADR-0026).
- **Entities** (people, aliases, merge) and **recall evaluation** (Phase 4 benchmark) are not built;
  Ask is keyword-and-date retrieval, not entity-aware.
- **Not exercised on real hardware:** the phone and watch flows, voice detection on real speech,
  call and other-app mic interruptions, OEM battery behaviour.
- **iOS** is deliberately deferred; there is no committed delivery phase for it.
