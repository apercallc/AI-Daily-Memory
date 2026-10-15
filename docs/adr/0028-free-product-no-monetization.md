# ADR-0028: Free Product, No Monetization

- Status: Accepted
- Date: 2026-10-14
- Amends: ADR-0024 (operator-configured model for web Ask), ADR-0025 (Stripe subscriptions), and ADR-0026 (free service direction).

## Context

The repository contains conflicting launch guidance. ADR-0026 and the product README say the service is free, while AGENTS.md and Phase 10 of TODO.md require subscriptions, paid tiers, and server-authoritative entitlements. The app already supports on-device processing and optional user-supplied provider keys, but the backend also contains dormant Stripe subscription code and an operator-configured model path for web Ask.

The product will not sell a product or service. Users may use the free on-device model or configure their own existing paid provider account. The project must not incur model-provider charges on users' behalf.

## Decision

1. The product is free. There are no subscriptions, paid plans, purchases, billing entitlements, or paid feature gates. Do not configure or expose Stripe billing or Play Billing.
2. Free on-device models remain the default where available. If a user chooses a paid provider, the user supplies their own credentials and accepts that provider's terms and charges. Calls go directly from the user's device to the chosen provider; the project does not proxy or pay for model inference.
3. The server does not make model-provider calls using operator-owned keys for user requests. Disable and remove the operator-configured model path for web Ask before production release. Web Ask remains retrieval-based and evidence-backed without a model unless a future ADR defines a user-owned credential flow.
4. Abuse controls, authentication, and bounded usage limits may remain, but they protect service availability and are not plan metering or entitlements.
5. API access, export, account deletion, privacy rights, and core recording remain available without charge. API keys stay user-owned, hashed, scoped, revocable, and rate-limited.
6. Remove dormant Stripe subscription implementation and associated plan entitlement code through a database-safe migration. Preserve unrelated transactional email and account-recovery behavior from ADR-0025.

## Consequences

- Phase 10 must not contain subscription, purchase, billing, cancellation, or paid-entitlement deliverables.
- Before release, remove Stripe billing routes, configuration, web UI, dependencies, plan-specific limits, and schema fields without breaking existing accounts or migrations.
- Remove or disable the operator-funded web Ask model provider and revise its documentation and tests.
- Update the launch gaps, quality gates, and repository agent instructions to match this decision.
- Users who configure a third-party provider may incur charges from that provider. The app must explain this before the first network call and must never imply the provider is free.
- Model availability and quality remain explicit product limitations; the free on-device path must not silently fall back to a paid service.
