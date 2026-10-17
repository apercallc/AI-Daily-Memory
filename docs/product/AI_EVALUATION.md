# AI evaluation (TODO.md Phase 2 "AI evaluation")

What exists today, what is deliberately still open, and how to close it.

## What's built

The measurement machinery is in `core/recording` (pure Kotlin, unit tested, no Android or
network dependency, so it runs the same in CI as everywhere else):

- `ExtractionEvaluator` — scores a session's predicted items against gold items. Matching is
  structural (same `kind`, overlapping evidence lines), not exact-text, because an LLM will
  never reproduce a human's exact wording. Greedy one-to-one matching, so duplicate predictions
  cannot inflate recall. Reports precision/recall/F1 per kind and an overall rollup; `aggregate`
  combines scores across a whole dataset.
- `ExtractionGoldDataset` — the dataset shape (`GoldCase`: id, transcript, expected items,
  `reviewed` flag, optional notes) plus a seed set of cases covering every `MemoryItem` kind and,
  just as importantly, negative cases (a transcript that should extract *nothing* of a kind).
  TODO.md calls a false-positive task or commitment a high-severity failure, so the dataset has
  to be able to catch over-extraction, not only under-extraction.
- `PriorItemsContext` — formats a bounded list of currently-open actions/commitments as prompt
  context (TODO.md "evaluate prior open action items as extraction context"). Wired into
  `ApiKeyExtractionProvider` as an optional constructor parameter, `null` by default: passing it
  changes nothing about today's prompt.

## What's still genuinely open, and why an agent session can't close it alone

**The gold dataset needs a human.** Every case in `ExtractionGoldDataset` has `reviewed = false`.
They were drafted by an agent session as a plausible starting shape — they are not ground truth.
An agent session should not author the "correct" answers it would then grade its own extraction
against; that would just measure whether the extractor agrees with another model's guess, not
whether it's right. Until a person has read each transcript, independently decided what a human
would expect extracted (including confirming or rewriting the negative cases), and flipped
`reviewed` to `true`, no precision/recall number from this dataset means anything.

**The measurement itself needs a real provider run.** `ExtractionEvaluator` can score any
`(predicted, gold)` pair; it has no opinion about where `predicted` comes from. Running it against
real output means calling a real extraction provider (the user's own configured API key, per
ADR-0020 — never an operator-funded model) against the gold transcripts, which is a deliberate,
costed action a person should trigger, not something that should happen silently in CI.

**"Evaluate prior open action items as extraction context" needs the same real run, twice.**
TODO.md's own instruction is to "keep it only if it improves follow-up accuracy without more
false positives" — that's an A/B comparison against the measurement above, not a unilateral
decision. `PriorItemsContext` exists and is tested in isolation; it stays unwired from the default
path until that comparison has real numbers behind it.

## How to actually close this

1. Have a human reviewer open `ExtractionGoldDataset.kt`, read each `transcript`, and either
   confirm or correct `expected` to match what they would genuinely expect extracted. Flip
   `reviewed = true` and note who/when in `notes`. Add more cases — especially negative ones —
   as real sessions surface edge cases.
2. Run a configured `ApiKeyExtractionProvider` against each reviewed case's transcript, collect
   its `ExtractedItem`s, and call `ExtractionEvaluator.score(predicted, case.expected)` per case,
   then `ExtractionEvaluator.aggregate(...)` across the reviewed subset. Record the resulting
   precision/recall/F1 per kind somewhere durable (this file, or a dated report next to it).
3. Only once that baseline exists: re-run the same cases with `priorItemsContext` populated from
   each case's own prior open items, score again, and compare. Wire `PriorItemsContext` into
   `ProcessingPipeline`/`AppContainer` by default only if the comparison shows a real improvement
   with no new false positives.
4. Tick the four Phase 2 "AI evaluation" checkboxes in `TODO.md` only once steps 1–3 have
   actually happened — not when the tooling that makes them possible exists.

## Regression tests for confirmed extraction bugs

TODO.md also asks for "regression tests for every confirmed extraction bug." As of this writing
there is no confirmed extraction bug on record to write one for — the fix commits in `git log`
touching `extraction`/`ApiKeyExtractionProvider` (timezone handling, JSON escaping, chunking) each
already carry their own regression test next to the fix, which is the existing convention and
should continue. When a real extraction bug is confirmed in the future (a case where a deployed
provider produced wrong or unsafe output that validation didn't catch), add it as a new
`ExtractionGoldDataset.GoldCase` with `notes` explaining what went wrong, so it becomes a
permanent part of the dataset rather than a one-off fix, and so `ExtractionGoldDatasetTest`
keeps it from silently rotting (out-of-bounds evidence, wrong segment count, etc.).
