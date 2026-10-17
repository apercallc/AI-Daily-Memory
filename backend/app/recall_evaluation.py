"""Quantifies a known-day recall benchmark (TODO.md Phase 4 "Recall evaluation"): Recall@K,
precision@K, and whether abstention happened exactly when it should have.

Pure: it scores an already-retrieved list of item ids against a known-correct set. It never
touches the database or the HTTP layer itself, so the same scorer works for the integration
suite in `tests/test_recall_benchmark.py` and for any future ad-hoc report. See
docs/product/RECALL_BENCHMARK.md for what this is (and is not) a substitute for.
"""

from dataclasses import dataclass
from datetime import date

DEFAULT_KS: tuple[int, ...] = (1, 3, 5)


@dataclass(frozen=True)
class RecallCase:
    """One known-day question. `expected_item_ids` are the item ids that should come back, in
    no required order (ranking is measured separately via precision@K). `expected_abstain=True`
    marks a case that should return nothing at all — a benchmark of positive cases alone could
    never catch an retrieval path that over-matches, the same gap `ExtractionGoldDataset`'s
    negative cases close on the Android side.
    """

    id: str
    question: str
    today: date
    expected_item_ids: tuple[str, ...] = ()
    expected_abstain: bool = False


@dataclass(frozen=True)
class CaseResult:
    case_id: str
    retrieved_ids: tuple[str, ...]
    recall_at_k: dict[int, float]
    precision_at_k: dict[int, float]
    abstain_correct: bool


def recall_at_k(expected: tuple[str, ...], retrieved: tuple[str, ...], k: int) -> float:
    """1.0 when nothing was expected: there is nothing this case could miss."""
    if not expected:
        return 1.0
    top_k = set(retrieved[:k])
    return sum(1 for e in expected if e in top_k) / len(expected)


def precision_at_k(expected: tuple[str, ...], retrieved: tuple[str, ...], k: int) -> float:
    """1.0 for an empty top-K that correctly expected nothing; 0.0 for an empty top-K that
    missed something real."""
    top_k = retrieved[:k]
    if not top_k:
        return 1.0 if not expected else 0.0
    return sum(1 for r in top_k if r in expected) / len(top_k)


def score_case(
    case: RecallCase,
    retrieved_ids: list[str] | tuple[str, ...],
    ks: tuple[int, ...] = DEFAULT_KS,
) -> CaseResult:
    retrieved = tuple(retrieved_ids)
    return CaseResult(
        case_id=case.id,
        retrieved_ids=retrieved,
        recall_at_k={k: recall_at_k(case.expected_item_ids, retrieved, k) for k in ks},
        precision_at_k={k: precision_at_k(case.expected_item_ids, retrieved, k) for k in ks},
        abstain_correct=(len(retrieved) == 0) == case.expected_abstain,
    )


def mean_recall_at_k(results: list[CaseResult], k: int) -> float:
    """The single number TODO.md's "Measure Recall@K" asks for; per-case detail stays
    available in `results` for diagnosing which case regressed."""
    if not results:
        return 1.0
    return sum(r.recall_at_k[k] for r in results) / len(results)


def abstention_accuracy(results: list[CaseResult]) -> float:
    if not results:
        return 1.0
    return sum(1 for r in results if r.abstain_correct) / len(results)
