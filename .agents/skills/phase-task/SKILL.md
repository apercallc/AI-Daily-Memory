---
name: phase-task
description: Work AI Daily Memory's TODO.md delivery plan under phase discipline — pick the next unchecked task in the current phase, respect exit gates and architecture gates, and write an ADR when reversing a recorded decision. Use when the user says "continue", "what's next", "pick a task", "work on the roadmap", or asks to start a new feature.
---

# Phase-task workflow

TODO.md is the executable delivery plan. Work flows through it in order — phases
have explicit exit criteria, and later-phase infrastructure is never built early
(that's the whole point of the gates).

## Steps

1. **Read TODO.md and find the current phase** — the phase with unchecked items that
   all earlier phases have exited, or is explicitly in progress. Note the phase's
   exit gate at the end.
2. **Confirm the phase's unchecked work is compatible with the architecture gates
   (README "Architecture Decision Gates"):** continuous capture, cloud processing,
   structured memory, recall, semantic search, approximate vector indexes, and
   infrastructure each sit behind a gate. If the task would cross one, stop and
   say so instead of building it.
3. **Pick the highest unchecked item** the user asked for (or the first one), and
   restate it in one line before implementing — including which exit-gate checkbox
   it advances.
4. **Implement within the rules** (AGENTS.md): state machine stays pure; offline-first
   capture; no infrastructure without measured need; stable dependencies only
   (alpha requires an ADR); tests + lint + coverage gates.
5. **ADR check:** if the work reverses any recorded decision (ADR 0001–0005:
   Android-first, offline-first capture, temporary raw audio, evidence-backed memory,
   single memory query service), write a new ADR first — don't silently diverge.
6. **When the item is done:** run the quality gates, tick the checkbox in TODO.md,
   commit with a Conventional Commit prefix, and report which exit-gate items
   remain before the phase closes.
7. **Phase complete?** Verify the exit gate criteria are truly met (don't stretch),
   then say the next phase is unlocked before starting it.

## Rules of engagement

- Small PR-sized units of work — resist batching a whole phase into one change.
- Uncheck nothing; if an item turned out wrong, fix the item, don't hide the state.
- If two items conflict, surface the conflict rather than picking silently.
- Update TODO.md checkboxes in the same commit as the work they describe.