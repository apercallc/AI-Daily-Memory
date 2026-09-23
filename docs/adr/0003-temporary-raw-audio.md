# ADR-0003: Temporary Raw Audio by Default

- Status: Accepted
- Date: 2026-09-22

## Context

Long-term raw audio materially increases privacy exposure and storage requirements. The product value is primarily transcript evidence and structured memory.

## Decision

Keep raw audio only long enough to support reliable transcription, verification, and a short recovery window. Long-term retention is not an MVP default.

## Consequences

- lower storage cost and privacy exposure
- re-transcription from original audio may not be possible after retention expires
- deletion must occur only after processing acknowledgement
