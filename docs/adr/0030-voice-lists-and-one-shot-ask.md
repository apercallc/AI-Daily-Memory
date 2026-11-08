# ADR-0030: Voice Lists and One-Shot Ask on the Phone

- Status: Accepted
- Date: 2026-11-08
- Relates to: ADR-0004 (evidence immutable), ADR-0011 (quick note is a marker), ADR-0020 and
  ADR-0026 (on-phone processing, notes-only sync), ADR-0024 (Ask), ADR-0028 (free product)

## Context

Wearable note takers such as the Pebble Index ring let a person say "add milk and eggs to the
shopping list" and find the list built for them, and let them ask one question and get one
answer. Our app records the whole day and extracts decisions, tasks, and commitments, but it
has no lists and no way to ask a question on the phone: Ask exists only on the web, against
synced notes.

## Decision

1. **Voice lists are explicit commands, found by rule, on the phone.** A pure parser in
   `core/recording` reads each transcript line and recognizes only explicit phrasing, such as
   "add X to my shopping list" or "put X on the grocery list". It never guesses from casual
   speech, so "we need milk" or "I need to get this done" create nothing. A false list item is
   a defect, the same as a false task. No model, provider, or network call is involved.
2. **Every list item cites its evidence.** An item stores the session, transcript line, and the
   sentence it came from. The transcript is never edited. Checking an item off or dismissing it
   is separate state, so a list is always correctable and never a source of truth about speech.
3. **List items are derived data.** Deleting a session deletes its list items. Processing the
   same transcript twice yields the same items (idempotent by line and item).
4. **Lists stay on the phone for now.** They are not added to the sync payload or export until
   a separate decision covers the server schema and the data inventory.
5. **One-shot Ask on the phone is retrieval only.** One question gives one answer built from the
   person's own undismissed memory items, with citations, and abstains when nothing matches.
   There is no conversation, no model, and no operator-funded inference (ADR-0028). It mirrors
   the server's Ask rules (ADR-0024) so a phone-only account gets the same behavior.
6. **A one-tap capture button is not part of this decision.** Starting capture stays an explicit
   "Start" (ADR-0011). A dedicated hardware button or ring is a later ADR with its own consent
   affordance.

## Consequences

- Phone-only accounts get lists and Ask without a server or an account.
- Rule-based parsing misses unusual phrasing. That is the accepted trade for zero false
  positives and zero cost. Widening the phrase set needs a test case per phrase.
- Lists need a new Room table and migration, and a row in `docs/product/DATA_INVENTORY.md`.
