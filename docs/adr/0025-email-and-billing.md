# ADR-0025: Email Recovery and Web Subscriptions

- Status: Superseded for billing by ADR-0028; transactional email decision remains accepted
- Date: 2026-10-13
- Builds on: ADR-0024. Billing details below are historical and must not be implemented; see ADR-0028.

## Decision

1. **Email is optional and transactional only.** `MAIL_PROVIDER=resend` (+ `RESEND_API_KEY`,
   `MAIL_FROM`, `PUBLIC_BASE_URL`) enables password reset and address verification. Links carry a
   single-use token stored as a SHA-256 hash: reset lasts 1 hour, verification 24; a newer request
   retires older ones. `POST /v1/password/forgot` always answers 202 and sends mail after the response,
   so it never reveals which addresses have accounts. A reset ends browser sessions and API keys but
   not phones (revoking a phone deletes its sealed-delivery key; that stays a deliberate, listed
   action). With no provider the flows answer 503 and the web app hides them.
2. **Stripe Checkout for web subscriptions.** One recurring price (`STRIPE_PRICE_ID`) maps to the
   `pro` plan. Only Stripe's signed webhook (`STRIPE_WEBHOOK_SECRET`, 5-minute tolerance) changes a
   plan, so entitlements are server-authoritative. Events are applied as "set the plan from the
   subscription's current state", which is idempotent and order-tolerant. `past_due` keeps `pro`
   while Stripe retries the card; `canceled`/`unpaid` return to `free`. Cancelling is one click in the
   Stripe portal and takes effect at the period end.
3. **Plans are limits, enforced on the server.** `free` keeps `DAILY_ASK_LIMIT` and the upload
   quota; `pro` gets `PRO_DAILY_ASK_LIMIT` and the upload quota times `PRO_UPLOAD_QUOTA_MULTIPLIER`.
   Nothing recording-related is ever gated: capture, pause, stop, export and deletion are rights.
4. **Android.** Google Play policy requires Play Billing for digital subscriptions sold inside an
   Android app. The app therefore does not sell anything; it only reflects the plan the server reports.
   A Play Billing path is future work and would feed the same server-side plan.

## Consequences

- Billing and mail are off until configured; nothing is charged or sent by default.
- The prices, limits and legal pages are placeholders for the operator to set and review.
