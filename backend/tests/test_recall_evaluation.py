"""Pure tests for app/recall_evaluation.py — proves the scorer's math before it is ever pointed
at a real benchmark."""

from datetime import date

from app.recall_evaluation import (
    CaseResult,
    RecallCase,
    abstention_accuracy,
    mean_recall_at_k,
    precision_at_k,
    recall_at_k,
    score_case,
)

TODAY = date(2026, 10, 14)


def test_recall_at_k_counts_expected_ids_found_in_the_top_k() -> None:
    assert recall_at_k(("a", "b"), ("a", "x", "b"), k=3) == 1.0
    assert recall_at_k(("a", "b"), ("a", "x"), k=2) == 0.5
    assert recall_at_k(("a", "b"), ("x", "y"), k=2) == 0.0


def test_recall_at_k_is_vacuously_perfect_when_nothing_was_expected() -> None:
    assert recall_at_k((), ("a", "b"), k=3) == 1.0
    assert recall_at_k((), (), k=3) == 1.0


def test_recall_at_k_only_looks_at_the_top_k() -> None:
    assert recall_at_k(("a",), ("x", "y", "a"), k=2) == 0.0
    assert recall_at_k(("a",), ("x", "y", "a"), k=3) == 1.0


def test_precision_at_k_penalizes_irrelevant_results() -> None:
    assert precision_at_k(("a",), ("a", "b", "c"), k=3) == 1 / 3
    assert precision_at_k(("a", "b", "c"), ("a", "b", "c"), k=3) == 1.0


def test_precision_at_k_empty_retrieval_matches_expectation() -> None:
    assert precision_at_k((), (), k=3) == 1.0
    assert precision_at_k(("a",), (), k=3) == 0.0


def test_score_case_reports_both_metrics_and_abstention() -> None:
    case = RecallCase(id="c1", question="q", today=TODAY, expected_item_ids=("a", "b"))

    result = score_case(case, ["a", "x", "b"], ks=(1, 3))

    assert result.case_id == "c1"
    assert result.recall_at_k[1] == 0.5
    assert result.recall_at_k[3] == 1.0
    assert result.precision_at_k[1] == 1.0
    assert result.abstain_correct is True  # not expected to abstain, and it didn't


def test_score_case_flags_a_wrong_abstention() -> None:
    case = RecallCase(id="c2", question="q", today=TODAY, expected_item_ids=("a",))

    result = score_case(case, [])

    assert result.abstain_correct is False  # should have found "a" but returned nothing


def test_score_case_flags_a_false_positive_when_abstention_was_expected() -> None:
    case = RecallCase(id="c3", question="q", today=TODAY, expected_abstain=True)

    result = score_case(case, ["unexpected"])

    assert result.abstain_correct is False


def test_mean_recall_at_k_averages_across_cases() -> None:
    results = [
        CaseResult("c1", ("a",), {1: 1.0}, {1: 1.0}, True),
        CaseResult("c2", (), {1: 0.0}, {1: 1.0}, False),
    ]

    assert mean_recall_at_k(results, k=1) == 0.5


def test_mean_recall_at_k_of_no_cases_is_vacuously_perfect() -> None:
    assert mean_recall_at_k([], k=1) == 1.0


def test_abstention_accuracy_across_cases() -> None:
    results = [
        CaseResult("c1", (), {}, {}, True),
        CaseResult("c2", ("a",), {}, {}, False),
        CaseResult("c3", (), {}, {}, True),
    ]

    assert abstention_accuracy(results) == 2 / 3


def test_abstention_accuracy_of_no_cases_is_vacuously_perfect() -> None:
    assert abstention_accuracy([]) == 1.0
