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

## Local audio at rest (Android)

- Segments are encrypted with Tink streaming AEAD (AES-256-GCM-HKDF, 4 KB segments). The
  segment id is the associated data, so a file cannot be moved onto another segment's record.
- The keyset is wrapped by an Android Keystore key that does not require user
  authentication (capture must work while the phone is locked). If the Keystore is unusable,
  recording refuses to start (`ENCRYPTION_UNAVAILABLE`); Tink's cleartext-keyset fallback is
  rejected explicitly.
- Keys are not rotated automatically: HKDF derives a fresh key per segment from a random
  salt, so there is no nonce-reuse budget to rotate for. If the Keystore key is lost or
  invalidated (e.g. data cleared, Keystore reset), unacknowledged local segments become
  unreadable. The app never silently generates a new key over them.
- Segments live in `noBackupFilesDir`; backup and device transfer exclude all app data.
- The local capture database (`capture.db`) holds no audio. Its metadata (session and
  segment ids, timestamps, checksums, lifecycle states, marker windows, speech regions,
  clip upload state, transcript line timings) relies on Android file-based encryption
  (credential-encrypted storage). Delivered transcript content is sealed at rest with
  app-level encryption: every line's words and speaker label are encrypted by
  `TranscriptCipher` (AES-256-GCM under a Keystore-wrapped key separate from the audio
  and link keys). The associated data binds each line to its upload id and line index,
  so lines cannot be swapped between clips or reordered. Only timing is stored in the
  clear. A line that fails to decrypt (lost key, tampering) is left out of the
  transcript and logged as `transcript_line_unreadable`; it is never shown as garbled
  text.
- Logs name events and exception types only, never exception messages: platform messages
  carry file paths and segment ids, and UUIDv7 ids encode recording times.
- **Deletion is logical deletion plus key hygiene, not physical erasure.** Deleting a
  segment unlinks its ciphertext file; flash storage may keep stale blocks until the
  controller reclaims them. Because only ciphertext is ever written, those blocks are not
  readable without the key. We do not promise physical flash erasure.

## Recording privacy

The application must not be designed to hide recording state. Recording indicators and pause/stop controls are product requirements.
