---
name: privacy-security-review
description: Project-specific privacy and security review for this conversation-recording app — recording-state visibility, audio data handling, permissions, secrets, authorization, and threat coverage. Use when the user asks "is this secure/privacy-safe", before merging capture, permission, storage, upload, auth, or API/MCP changes, and before any release. (Distinct from the generic security-review skill: this encodes this repo's product-level privacy rules.)
---

# Privacy & security review

This app records real conversations. Privacy failures here are product failures —
a user who doesn't know they're being recorded, or a leaked transcript, is the
worst-case outcome of this product. Review the changed surface; report ranked by
severity.

## Product-level rules (any violation = BLOCKER)

1. **Recording state is never hidden.** Indicators and pause/stop controls are
   product requirements. Anything weakening notification behavior, indicator
   visibility, or stop control is a blocker.
2. **Evidence is immutable.** Transcripts/segments are never mutated or silently
   reprocessed; corrections store separately from evidence.
3. **No secrets, no recorded product data in the repo** — including tests, docs,
   and examples. `.env` files are never committed (`.env.example` is the pattern).

## Review by surface

**Android capture (apps/, core/recording):**
- Permissions justified in the manifest; foreground-service type correct for capture.
- Captured audio stays in private app storage; never public directories.
- `allowBackup=false` / `usesCleartextTraffic=false` never regressed.
- Raw-audio retention honors ADR 0003 (temporary raw audio) — deletion is real.

**Backend:**
- AuthN/AuthZ on every endpoint; every user-owned resource access is scoped.
- Signed URLs scoped + TTL'd; object-storage paths isolated per user.
- Pydantic validation at every boundary; upload size/type limits.
- Rate/abuse limits on client-reachable endpoints; secrets only via env settings.
- Never log transcripts, keys, or tokens.

**MCP/CLI interfaces:**
- Tools call the Memory Query Service with the same authorization — never the DB
  directly; structured results with evidence references, no transcript dumps.
- Credentials in secure storage (Android Keystore / OS keychain), never plaintext.

**Dependencies:** `uv run pip-audit` clean; Dependabot findings triaged.

## Disclosure

Suspected vulnerabilities involving private data, auth, crypto, secrets, or data
exposure are reported privately per SECURITY.md — never as public issues.

## Report format

```
Security & privacy review: <surface>
Threat coverage: what the change is exposed to / what mitigates it
Findings: [BLOCKER|MAJOR|MINOR] file:line — what, why, fix
Gates run: pip-audit / lint results
```