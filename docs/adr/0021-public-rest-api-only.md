# ADR-0021: Public REST API Is the Only External Integration Surface

- Status: Accepted
- Date: 2026-10-12
- Supersedes: MCP-specific transport provisions in ADR-0005, ADR-0012, ADR-0014, and ADR-0020; the public REST API, shared query logic, and storage decisions remain in force.

## Context

The project needs one supported way for user-owned clients and self-hosted deployments to access synced memory. A separate MCP server duplicates an integration surface and its operational, security, dependency, and testing burden. No dedicated client integration is required; API access is sufficient.

## Decision

- Keep the versioned FastAPI REST API as the only supported external integration surface.
- Remove the MCP server, its dependencies, tests, CI job, setup instructions, and product-facing references.
- Do not add a client-specific integration. External clients integrate directly through the documented API and its user-scoped authentication, scopes, and rate limits.
- Keep optional sync and data-local-by-default behavior as specified by ADR-0020. API access does not enable sync or transcript access without the user's explicit choices and authorization.
- Keep the CLI roadmap separate; it is not an alternate server protocol and must use the public API.

## Consequences

- The repository has one integration contract to secure, document, version, and test.
- MCP-specific roadmap and CI work is removed. Existing historical ADRs remain as records; this decision supersedes their MCP transport plans.
- API authentication, scope enforcement, rate limiting, revocation, transcript consent, and account deletion remain production requirements.
- Any future non-REST integration requires a new ADR and a demonstrated user need.

## Verification

The MCP package and CI job are removed; current documentation and roadmap describe REST API access only. Backend API contract tests remain the verification path.
