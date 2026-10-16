"""Recall (TODO.md Phase 4): turning a question into a bounded search over the person's own
memory, and an answer that is only ever built from what that search returned.

Retrieval is structured SQL first and PostgreSQL full-text search second (no vectors, no
speculative infrastructure). This module holds the pure parts: which dates a question means,
which words matter, which item kinds it hints at, and the evidence-only answer. The database
queries live in `app/api/ask.py`.

Ask is retrieval-only (ADR-0028): the server never calls a model provider on a user's behalf,
so an answer is always just the matching memory items read back plainly, and it abstains
outright when nothing matches.
"""

import logging
import re
from dataclasses import dataclass
from datetime import date, timedelta

logger = logging.getLogger("app.recall")

_STOPWORDS = frozenset(
    [
        "a",
        "an",
        "and",
        "are",
        "as",
        "at",
        "be",
        "been",
        "but",
        "by",
        "can",
        "could",
        "did",
        "do",
        "does",
        "for",
        "from",
        "had",
        "has",
        "have",
        "how",
        "i",
        "in",
        "is",
        "it",
        "its",
        "me",
        "my",
        "of",
        "on",
        "or",
        "our",
        "said",
        "say",
        "so",
        "that",
        "the",
        "their",
        "them",
        "then",
        "there",
        "they",
        "this",
        "to",
        "was",
        "we",
        "were",
        "what",
        "when",
        "where",
        "which",
        "who",
        "whom",
        "why",
        "will",
        "with",
        "would",
        "you",
        "your",
        "about",
        "any",
        "anything",
        "tell",
        "show",
        "give",
        "find",
        "ask",
        "remind",
        "remember",
        "today",
        "yesterday",
        "week",
        "month",
        "last",
        "this",
        "ago",
    ]
)
_KIND_HINTS: tuple[tuple[re.Pattern[str], str], ...] = (
    (re.compile(r"\b(decid|decision|chose|choose|agreed on)\w*"), "decision"),
    (re.compile(r"\b(promis|commit|owe|follow[- ]?up|owed)\w*"), "commitment"),
    (re.compile(r"\b(todo|to-do|task|action item|need to do|have to do)\w*"), "action"),
    (re.compile(r"\b(idea|brainstorm)\w*"), "idea"),
    (re.compile(r"\b(question|wonder|open question|unresolved)\w*"), "question"),
)
_LAST_N_DAYS = re.compile(r"\b(?:last|past)\s+(\d{1,3})\s+days?\b")


@dataclass(frozen=True)
class DateRange:
    start: date
    end: date
    label: str


def resolve_range(question: str, today: date) -> DateRange | None:
    """The calendar span a question points at, or None when it names no time.

    Weeks run Monday to Sunday. "Last week" is the previous full week, not the last seven days.
    """
    text = question.lower()
    if "yesterday" in text:
        day = today - timedelta(days=1)
        return DateRange(day, day, "yesterday")
    if re.search(r"\btoday\b|\bthis morning\b|\bthis afternoon\b|\btonight\b", text):
        return DateRange(today, today, "today")
    match = _LAST_N_DAYS.search(text)
    if match:
        days = max(1, min(int(match.group(1)), 365))
        return DateRange(today - timedelta(days=days - 1), today, f"the last {days} days")
    monday = today - timedelta(days=today.weekday())
    if "last week" in text:
        return DateRange(monday - timedelta(days=7), monday - timedelta(days=1), "last week")
    if "this week" in text:
        return DateRange(monday, today, "this week")
    first = today.replace(day=1)
    if "last month" in text:
        end = first - timedelta(days=1)
        return DateRange(end.replace(day=1), end, "last month")
    if "this month" in text:
        return DateRange(first, today, "this month")
    return None


def kind_hint(question: str) -> str | None:
    """The item kind a question is about ("what did I decide…"), or None."""
    text = question.lower()
    for pattern, kind in _KIND_HINTS:
        if pattern.search(text):
            return kind
    return None


def search_terms(question: str) -> list[str]:
    """The words that carry the question, in order, without filler. Bounded: long pasted text
    must not become a huge query."""
    words = re.findall(r"[a-z0-9][a-z0-9'-]{1,}", question.lower())
    terms: list[str] = []
    for word in words:
        if word not in _STOPWORDS and word not in terms:
            terms.append(word)
    return terms[:12]


@dataclass(frozen=True)
class Evidence:
    """One retrieved memory item, as the answer may cite it."""

    item_id: str
    day: date
    kind: str
    text: str
    owner: str | None = None
    counterpart: str | None = None
    due: date | None = None
    direction: str | None = None


@dataclass(frozen=True)
class Answer:
    text: str
    used: tuple[str, ...]
    abstained: bool
    source: str  # "evidence_only" — Ask has no model path (ADR-0028)


NOTHING_FOUND = "I don't have anything in your memory about that."


def describe(item: Evidence) -> str:
    """One readable line for an item, used for the evidence-only answer."""
    parts = [item.text]
    if item.kind == "commitment" and item.counterpart:
        who = "you owe" if item.direction == "i_owe" else "owed to you by"
        parts.append(f"({who} {item.counterpart})")
    elif item.owner and item.owner != "me":
        parts.append(f"(owner: {item.owner})")
    if item.due:
        parts.append(f"due {item.due.isoformat()}")
    return " ".join(parts)


def evidence_only_answer(question: str, items: list[Evidence], label: str | None) -> Answer:
    """List what was found. This is the only answer Ask ever gives (ADR-0028: no model
    provider acts on a user's behalf), so it must stand on its own, not as a fallback."""
    if not items:
        return Answer(NOTHING_FOUND, (), abstained=True, source="evidence_only")
    when = f" for {label}" if label else ""
    lines = [f"- {i.day.isoformat()} ({i.kind}): {describe(i)}" for i in items[:8]]
    text = f"Here is what your memory has{when}:\n" + "\n".join(lines)
    return Answer(
        text, tuple(i.item_id for i in items[:8]), abstained=False, source="evidence_only"
    )
