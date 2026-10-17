# Recall benchmark (TODO.md Phase 4 "Recall evaluation")

What exists today, what is deliberately still open, and why.

## What's built

- `app/recall_evaluation.py` — a pure scorer: `recall_at_k`, `precision_at_k`,
  `mean_recall_at_k`, `abstention_accuracy`. Takes an already-retrieved list of item ids and a
  known-correct set; has no database or HTTP dependency, so the same functions score both the
  integration suite below and any future ad-hoc report.
- `tests/test_recall_benchmark.py` — a known-day benchmark: seeds a realistic multi-day,
  multi-kind memory corpus (decisions, actions, commitments, ideas, questions, a dismissed item,
  a corrected item, cross-account data) with ids known in advance, asks the real, live
  `POST /v1/ask` endpoint eleven questions about it, and scores the aggregate with
  `recall_evaluation`. It asserts `mean_recall_at_k == 1.0` and `abstention_accuracy == 1.0`
  today, plus a dedicated ranking check (the item sharing every query term must rank first, not
  merely appear somewhere in the results).

## Why this one is different from the Android extraction gold dataset

Phase 2's `ExtractionGoldDataset` needs a human because the hard part is subjective: what should
a person reasonably expect extracted from ambiguous spoken language? There is no such ambiguity
here. Every item in `test_recall_benchmark.py` is seeded directly by the test itself — the
"correct answer" to each question is simply what was seeded, not a judgment call an agent
session would be grading itself on. That is why this suite is written as asserting tests, not as
a `reviewed = false` dataset waiting on a person, and it is why TODO.md's "Recall evaluation" can
move further without a human reviewer than Phase 2's "AI evaluation" can.

## What's still genuinely open

**Calendar-boundary cases are deliberately excluded from the live-clock suite.** "Last week" and
"last month" depend on which day of the week or month the suite happens to run; re-testing them
against `datetime.now()` would make the suite's pass/fail depend on the clock, which is its own
kind of unreliable benchmark. `tests/test_recall.py` already covers that date math with fixed
calendar dates (`resolve_range` is a pure function — no benchmark needed, just unit tests), and
this suite deliberately leans on "today" / "yesterday" / "last N days" phrasing instead, which is
robust regardless of what day it runs.

**A human-curated set of realistically-worded, ambiguous real-world questions is still not
built**, and arguably never fully "closable" by a mechanical pass/fail suite: real people ask
vague, multi-part, or oddly-phrased questions, and judging whether a given ranking is "good
enough" for one of those is a human preference call, not a deterministic check. If and when real
usage data (or a human sitting down to write adversarial questions) exists, add it as its own
suite alongside this one — it should supplement, not replace, the deterministic cases here.

## How to extend this

- Add a new `RecallCase` (plus the `add_item` seed data it needs) to
  `test_known_day_recall_benchmark` for any new retrieval behavior, the same way a new unit test
  is added for any other code path.
- If a real recall regression is ever confirmed in production (the live `/v1/ask` returned the
  wrong thing, or missed something it should have found), add it here as a new case with a
  comment explaining what went wrong — the equivalent of `ExtractionGoldDataset`'s
  confirmed-bug convention on the Android side.
- Tick the corresponding TODO.md checkboxes only once a case search like the above genuinely
  demonstrates the properties TODO.md asks for across a real spread of questions — not when the
  tooling that makes that measurement possible merely exists.

## An unrelated bug found while building this

`tests/test_web_api.py::test_export_is_idempotent_within_the_ttl_and_regenerates_after_it` and
`tests/test_notes_api.py::test_a_session_listed_without_words_still_gets_a_note_with_its_start_time`
fail when the host's local timezone is not UTC (reproduced on this machine, which runs
America/Chicago) — some response field is built from a naive/local `datetime` instead of a UTC
one, so the ISO string carries a `-05:00`/`-06:00` offset instead of `Z`. CI runners default to
UTC, which is almost certainly why this hasn't been caught before. Not fixed here — it's
unrelated to recall evaluation and deserves its own, focused fix — but worth a ticket: a
production host ever misconfigured with a non-UTC system timezone would export silently wrong
API response timestamps.
