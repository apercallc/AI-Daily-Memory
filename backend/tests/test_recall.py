from datetime import date

from app.recall import (
    NOTHING_FOUND,
    Evidence,
    evidence_only_answer,
    kind_hint,
    resolve_range,
    search_terms,
)

TODAY = date(2026, 10, 14)  # a Wednesday


def test_dates_in_a_question_resolve_to_the_persons_own_calendar() -> None:
    cases = {
        "what did I decide yesterday": (date(2026, 10, 13), date(2026, 10, 13)),
        "anything from today?": (TODAY, TODAY),
        "tasks from last week": (date(2026, 10, 5), date(2026, 10, 11)),
        "what happened this week": (date(2026, 10, 12), TODAY),
        "decisions last month": (date(2026, 9, 1), date(2026, 9, 30)),
        "ideas this month": (date(2026, 10, 1), TODAY),
        "what did I say in the last 3 days": (date(2026, 10, 12), TODAY),
    }
    for question, (start, end) in cases.items():
        window = resolve_range(question, TODAY)
        assert window is not None, question
        assert (window.start, window.end) == (start, end), question


def test_a_question_with_no_time_has_no_window_and_long_spans_are_capped() -> None:
    assert resolve_range("what did Sam promise", TODAY) is None
    window = resolve_range("last 999 days", TODAY)
    assert window is not None
    assert (TODAY - window.start).days == 364


def test_january_looks_back_into_december() -> None:
    window = resolve_range("last month", date(2026, 1, 15))
    assert window is not None
    assert (window.start, window.end) == (date(2025, 12, 1), date(2025, 12, 31))


def test_kind_hints() -> None:
    assert kind_hint("what did I decide about pricing") == "decision"
    assert kind_hint("what did I promise Sam") == "commitment"
    assert kind_hint("what are my to-do items") == "action"
    assert kind_hint("any ideas for the launch") == "idea"
    assert kind_hint("what open questions do I have") == "question"
    assert kind_hint("tell me about Sam") is None


def test_search_terms_drop_filler_dedupe_and_stay_bounded() -> None:
    assert search_terms("What did I tell Sam about the pricing deck?") == ["sam", "pricing", "deck"]
    assert search_terms("Sam sam SAM") == ["sam"]
    assert len(search_terms(" ".join(f"word{i}" for i in range(50)))) == 12
    assert search_terms("what did I say") == []


ITEM = Evidence(
    item_id="i1",
    day=date(2026, 10, 13),
    kind="commitment",
    text="Send Sam the deck",
    counterpart="Sam",
    direction="i_owe",
    due=date(2026, 10, 16),
)


def test_the_evidence_only_answer_lists_the_items_it_found() -> None:
    answer = evidence_only_answer("q", [ITEM], "yesterday")
    assert not answer.abstained
    assert answer.source == "evidence_only"
    assert "2026-10-13 (commitment): Send Sam the deck (you owe Sam) due 2026-10-16" in answer.text
    assert answer.used == ("i1",)


def test_with_nothing_found_it_abstains() -> None:
    answer = evidence_only_answer("q", [], None)
    assert answer.abstained
    assert answer.text == NOTHING_FOUND
