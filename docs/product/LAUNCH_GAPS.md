# What is still missing to launch

A working review of the whole product (phone, watch, web, API, database, operations) on
2026-10-13. Items marked **built** were closed in the same pass; the rest need a decision or
an outside account that code alone can't supply.

## Blocking: the phone's default server did not serve the API (fixed in code, needs a deploy)

`aidailymem.apercallc.com` is the `web` service (static nginx). The API is the `api` service, whose
custom domain `api-aidailymem.apercallc.com` has no DNS record, so it is reachable only at its
Railway address. The shipped phone app (0.3.0) defaults to `https://aidailymem.apercallc.com`, so
sign-up from the app reached the static site, not the API (every path returns 200 HTML there,
which is why a health check on that host looked fine).

**Fix, built:** the web service's nginx now proxies `/v1/` to the API, so the web app and every
released phone build work with no app update. To turn it on in production:

1. On the `web` service set `API_UPSTREAM=http://api.railway.internal:8080`,
   `API_HOST=api.railway.internal`, `NGINX_RESOLVER=[fd12::10] ipv6=on` (these are the Dockerfile
   defaults, so a plain redeploy is enough).
2. On the `api` service set `TRUST_RAILWAY_X_REAL_IP=true` (the proxy forwards the edge's
   `X-Real-IP`; without this every person shares the proxy's address in rate limits).
3. Redeploy `api` first (new migration `0009`, runs with the usual release step), then `web`.
4. Check `https://aidailymem.apercallc.com/v1/ready` returns `{"status":"ok","database":"ok"}`.
5. Optional: add the DNS record for `api-aidailymem.apercallc.com`; nothing depends on it.

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
| No metering | `usage_counters`, daily Ask limit |
| Readiness | `/v1/ready` checks the database |

## Built, needs your accounts and settings to switch on

| Feature | What exists | To turn on |
| --- | --- | --- |
| Password reset, email verification | Single-use hashed links, no account enumeration, web pages, resend | `MAIL_PROVIDER=resend`, `RESEND_API_KEY`, `MAIL_FROM` (a domain with SPF/DKIM), `PUBLIC_BASE_URL` |
| Subscriptions | Stripe Checkout and portal, signed webhook, plan limits for Ask and uploads, plan card in the web app (ADR-0025) | `STRIPE_SECRET_KEY`, `STRIPE_WEBHOOK_SECRET`, `STRIPE_PRICE_ID`; point a Stripe webhook at `/v1/billing/webhook` for `checkout.session.completed` and `customer.subscription.*` |
| Privacy policy, terms | Plain-language drafts at `/privacy.html` and `/terms.html`, linked from the site and app | A lawyer's review before relying on them |
| CLI | `interfaces/cli/adm.py`: `ask`, `day`, `search`, `decisions`, `tasks`, `commitments`, `--json`, key saved owner-only | Create an API key in Account |
| Dead-job tooling | `python -m app.admin dead-jobs` and `requeue-job` | none |

## Still needs a decision or an account

1. **Turning on server processing.** Transcription (`TRANSCRIPTION_PROVIDER=deepgram`) and
   extraction (`EXTRACTION_PROVIDER=claude`) are off until an operator enables them with keys and
   completes the cost, terms and consent reviews in TODO.md Gate B. Until then a full-cloud account's
   web app stays empty; phone-synced reports still appear.
2. **Ask with a model.** `ASK_PROVIDER=claude` sends memory text to Anthropic; same consent review.
3. **Play Billing** for in-app purchase (ADR-0025) and the Play Store listing.

## Operations gaps

- **Database backups** are intentionally out of scope for now.
- **Uptime and alerting.** Sentry is wired; no uptime monitor or status page.
- **Deleted-account purge** retries forever on a permanent error (bounding it needs a migration).
- **Single API replica.** The per-address limiter is in-process; scaling out needs a shared limiter.
- **Cost per user** is unmeasured (TODO Launch readiness).

## Product gaps

- **Phone-only accounts see an empty web app** by design; the web empty state explains how to
  connect, but the app could prompt users to pick a mode that fits wanting web access.
- **Mode switch does not move data** (cloud to phone is not implemented; TODO Storage modes).
- **Entities** (people, aliases, merge) and **recall evaluation** (Phase 4 benchmark) are not built;
  Ask is keyword-and-date retrieval, not entity-aware.
- **Not exercised on real hardware:** the phone and watch flows, voice detection on real speech,
  call and other-app mic interruptions, OEM battery behaviour.
- **iOS** is deliberately later (Phase 9).
