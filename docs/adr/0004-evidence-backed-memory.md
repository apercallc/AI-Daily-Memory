# ADR-0004: Evidence-Backed Memory

- Status: Accepted
- Date: 2026-09-22

## Context

Generated memories can be wrong. Users need to understand where decisions, actions, commitments, and facts came from.

## Decision

Derived memory items always retain references to supporting transcript evidence. Corrections never rewrite the original evidence layer.

## Consequences

- answers can cite source timestamps
- extraction can be reprocessed as models improve
- correction and supersession must be modeled explicitly

## Amendment (2026-09-25): immutable is not undeletable

"Immutable" means evidence is never **edited or rewritten**. It does not mean evidence is
never **deleted**. Users can delete sessions, time ranges, or their whole account, and
retention jobs delete raw audio (ADR-0003). Clarifications:

- **Deletion cascades to derived data.** Deleting evidence deletes memory items, event
  windows, summaries, embeddings, and cached answers that depend on it. A memory item left
  with no supporting evidence is deleted, not kept as an unsupported claim.
- **Evidence links are many-to-many.** A memory item can cite several transcript segments
  (a `memory_item_evidence` join table), not a single `source_transcript_id`.
- **Re-transcription creates a new transcript revision** (new rows tagged with
  `transcription_version`) while raw audio is still retained; exactly one revision per
  audio segment is marked active. Earlier revisions are never modified in place.
- **Corrections are separate rows** (`memory_item_corrections`) that reference the item
  and the user action; the displayed item is the latest correction applied over the
  extracted original.
