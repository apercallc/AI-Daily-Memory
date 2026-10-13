from datetime import date

import httpx
import pytest

from app.recall import (
    NOTHING_FOUND,
    AnswerUnavailableError,
    ClaudeAnswerProvider,
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


def test_without_a_model_the_answer_lists_the_items_it_found() -> None:
    answer = evidence_only_answer("q", [ITEM], "yesterday")
    assert not answer.abstained
    assert answer.source == "evidence_only"
    assert "2026-10-13 (commitment): Send Sam the deck (you owe Sam) due 2026-10-16" in answer.text
    assert answer.used == ("i1",)


def test_with_nothing_found_it_abstains() -> None:
    answer = evidence_only_answer("q", [], None)
    assert answer.abstained
    assert answer.text == NOTHING_FOUND


def _provider(handler: httpx.MockTransport) -> ClaudeAnswerProvider:
    return ClaudeAnswerProvider("k", transport=handler)


def _claude(text: str, status: int = 200) -> httpx.MockTransport:
    def respond(request: httpx.Request) -> httpx.Response:
        return httpx.Response(status, json={"content": [{"text": text}]})

    return httpx.MockTransport(respond)


@pytest.mark.anyio
async def test_the_model_may_only_cite_items_it_was_given() -> None:
    provider = _provider(_claude('{"answer": "You owe Sam the deck.", "used": [1, 7, "x", true]}'))
    answer = await provider.answer("q", [ITEM])
    assert answer.used == ("i1",)
    assert not answer.abstained
    assert answer.source == "model"


@pytest.mark.anyio
async def test_a_model_that_says_it_cannot_answer_abstains() -> None:
    for text in (
        '{"answer": "unknown", "used": [], "answerable": false}',
        '{"answer": "made up", "used": []}',
    ):
        answer = await _provider(_claude(text)).answer("q", [ITEM])
        assert answer.abstained


@pytest.mark.anyio
@pytest.mark.parametrize("text", ["not json", '{"answer": 3, "used": []}', '{"nope": 1}', "[]"])
async def test_a_malformed_model_reply_is_unavailable_not_a_crash(text: str) -> None:
    with pytest.raises(AnswerUnavailableError):
        await _provider(_claude(text)).answer("q", [ITEM])


@pytest.mark.anyio
async def test_provider_errors_are_unavailable() -> None:
    with pytest.raises(AnswerUnavailableError):
        await _provider(_claude("{}", status=529)).answer("q", [ITEM])

    def boom(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("down")

    with pytest.raises(AnswerUnavailableError):
        await _provider(httpx.MockTransport(boom)).answer("q", [ITEM])
