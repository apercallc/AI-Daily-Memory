# Security Policy

AI Daily Memory may process highly sensitive audio and transcript data. Treat all captured content as confidential.

## Reporting

Do not open a public issue for a suspected vulnerability involving private data, authentication, authorization, cryptography, secrets, or data exposure.

Report security issues privately to the repository owner/organization maintainers.

## Security requirements

- Never commit credentials, API keys, private certificates, signing keys, recordings, or transcripts.
- Never put raw audio or full transcript content in ordinary logs, crash reports, analytics, or traces.
- All network communication must use TLS in production.
- Mobile storage containing audio must be encrypted.
- Production secrets must come from a secret manager/environment injection, never source control.
- Object storage must remain private and use short-lived scoped URLs.
- Every user-owned resource must be authorization checked server-side.
- Retryable writes must be idempotent.
- Account deletion must include derived memories, embeddings, queued jobs, and retained temporary objects according to the retention policy.

## Dependency policy

- Prefer stable maintained dependencies.
- Dependabot should remain enabled.
- High/critical known vulnerabilities block production release unless a documented risk acceptance exists.
- Release builds should produce an SBOM before broad production rollout.

## Recording privacy

The application must not be designed to hide recording state. Recording indicators and pause/stop controls are product requirements.
