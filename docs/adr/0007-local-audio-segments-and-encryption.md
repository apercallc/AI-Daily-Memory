# ADR-0007: Local Audio Segment Format and Encryption

- Status: Accepted (codec and segment length to be confirmed by the Phase 0 device benchmark)
- Date: 2026-09-25

## Context

Phase 0 requires crash-resistant, encrypted, segmented local audio. Several obvious
choices fail these requirements in non-obvious ways:

- **MP4/M4A via `MediaMuxer`** writes its index (`moov`) at the end. A process kill before
  `stop()` leaves the segment **unplayable** — silent loss of the whole segment.
- **Jetpack Security (`androidx.security:security-crypto`, `EncryptedFile`)** is
  deprecated. Building on it creates a migration later.
- **Keystore keys with `setUserAuthenticationRequired(true)`** become unusable while the
  phone is locked — i.e. most of a recording day.
- **Credential-encrypted storage** is unavailable after a reboot until first unlock, so
  nothing can be written before the user unlocks.

## Decision

1. **Append-safe container.** Segments use a stream format that stays decodable up to
   the last complete frame: Ogg/Opus (preferred) or AAC in ADTS. Not MP4.
2. **Bounded segments.** Target 30–60 s per segment (final value from the benchmark). The
   worst-case loss from a crash is **one in-progress segment**, and capture health must
   report it as a gap rather than hide it.
3. **Streaming AEAD with Tink** (`AesGcmHkdfStreaming`), keyset wrapped by an Android
   Keystore AES key. The Keystore key does **not** require user authentication; it is
   invalidated only by app uninstall / data clear.
4. **Segment lifecycle** on device: `WRITING → SEALED (closed, fsynced, checksummed) →
   UPLOADED → ACKNOWLEDGED → DELETED`. Only `SEALED` or later segments count as captured
   audio. The SHA-256 is computed over the plaintext audio, because that is what gets
   uploaded (over TLS, into private object storage with server-side encryption) and what
   the server verifies on finalize. Device encryption protects data at rest on the phone.
5. **Direct Boot:** the app does not capture before first unlock after reboot. After
   reboot it posts a "tap to resume" notification (see ADR-0010) and never pretends the
   gap was recorded.
6. Deletion is documented as **logical deletion + key hygiene**, not physical flash
   erasure (TODO.md Local security).

## Consequences

- A truncated streaming-AEAD ciphertext fails authentication for the unfinished tail; this
  is the same bounded loss as (2), and is detectable.
- Adds Tink as a dependency (stable; add to `libs.versions.toml` when implemented).
- Codec choice still depends on measured CPU/battery cost per device; the constraints
  above narrow the benchmark to append-safe candidates only.
