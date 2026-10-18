# Quality Gates

These gates define the minimum engineering quality expected before production.

## Testing

### Unit coverage

- Core deterministic business logic: >=90% line coverage.
- Safety-critical state machines, idempotency, retry, and authorization logic: >=90% branch coverage.
- Backend application code: >=90% line coverage.

Coverage is not a substitute for integration, UI, endurance, fault, or security testing.

### Required test layers

- Pure unit tests
- API/storage integration tests
- Compose/instrumentation tests for critical flows
- Physical-device endurance tests
- Fault injection
- AI extraction evaluation
- Recall benchmark

## Android quality

Release candidates must monitor:

- crash rate
- ANR rate
- battery behavior
- memory
- startup
- rendering/jank
- foreground-service reliability

Do not use Google Play bad-behavior thresholds as the target. Internal targets should be materially better.

Before public release:

- Add Macrobenchmark for startup and critical flows.
- Generate Baseline Profiles for release builds.
- Run physical-device battery/endurance tests.

## Performance budgets

Exact values are set from measured Phase 0 baselines.

At minimum define budgets for:

- cold/warm startup
- recording CPU utilization
- recording memory footprint
- battery drain per hour
- local bytes per recorded hour
- upload throughput
- backend p50/p95 latency
- Ask My Memory p50/p95 latency
- processing backlog age

Performance regressions outside agreed tolerance block release.

## Security

Release blockers:

- exposed secret
- auth bypass
- cross-user data access
- public audio bucket/object
- plaintext sensitive storage where encryption is required
- unbounded upload or request body
- high/critical known vulnerability without documented acceptance
- sensitive content in telemetry

## Privacy

Before public beta:

- threat model
- data-flow diagram
- retention/deletion matrix
- account export
- account deletion
- third-party processor inventory
- recording/consent UX review

## Reliability

Critical invariants:

1. Persisted local audio is not deleted before the server acknowledgement policy permits it.
2. Duplicate retries do not create duplicate evidence or memory.
3. Capture failure is visible.
4. Processing failures have bounded retry and terminal state.
5. Derived AI output never replaces source evidence.

## API

- Versioned public contract
- OpenAPI generated/validated in CI
- Input validation
- authn/authz at every private endpoint
- bounded pagination/result sizes
- explicit timeouts
- idempotency for retryable writes

## Definition of Done

A feature is not done until:

- acceptance criteria pass
- tests exist at appropriate layers
- coverage remains above gate
- security/privacy reviewed
- failure handling exists
- observability exists where operationally useful
- performance impact measured for hot paths
- docs/ADR updated when required
- CI is green

## Known gaps (need product or device work)

Recorded so a green `qualityCheck` is not read as release readiness. Device and operator acceptance gates live here and in `docs/performance/`; `TODO.md` tracks implementation and test deliverables.

- Critical phone and Wear OS flows still need instrumentation and physical-device verification, including same-key pairing, TalkBack order, 200% font scaling, low-storage behavior, watch battery, and workday endurance.
- Macrobenchmark results, Baseline Profiles, and measured performance budgets are not available yet.
- Public API launch work remains open: generated OpenAPI validation in CI, API contract/versioning, abuse monitoring, third-party-client terms, and verification that no paid entitlements or operator-funded model calls remain.
- Production operations still need verified backend-only database access, least-privilege roles, production secrets and key-rotation procedures, backup encryption/monitoring, incident response procedures, late-upload cleanup verification, and configured/verified error reporting with IP-address storage disabled. A real restore drill ran 2026-10-18 (see `docs/product/LAUNCH_GAPS.md`); treat that as a one-time proof, not a recurring drill cadence — repeat it after any backup-script or Postgres-major-version change.
- Recording-consent legal review and production object-storage lifecycle/cost review remain required before enabling cloud audio for non-developer users. Define a minor-user policy and review covert-recording abuse scenarios before broad release. Any future owner-voice feature also needs a separate biometric-consent and privacy review before implementation.
- GitHub CodeQL runs via `.github/workflows/codeql.yml`'s workflow-based (advanced) setup —
  confirmed 2026-10-18 via `code-scanning/analyses`, which shows real SARIF results uploaded on
  every push (default setup correctly reports `not-configured`, as expected when a workflow
  supplies advanced setup instead). Dependabot security updates are enabled and 0 alerts are
  open. One open CodeQL finding (`py/polynomial-redos` in `credentials.py`) was fixed
  2026-10-18; re-run analyses to confirm it stops recurring.

Implemented account foundations include password reset and email verification flows, device-token listing/expiry/revocation, API-key create/list/revoke, and a self-service export endpoint. These do not close the separate public-launch gates above.
