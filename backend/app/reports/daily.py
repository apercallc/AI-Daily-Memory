"""Daily report contract (v1) and its Markdown rendering (ADR-0012, ADR-0004).

The report is the product's main output: what happened in a memory day, in the user's time
zone, with every extracted item tied to evidence. It is a versioned contract shared by the app,
exports (Markdown for Google Drive and the share sheet), and future API clients.

Model-generated text is untrusted input (README "Untrusted Content"): the renderer escapes all
of it, so transcript text can't inject headings, links, images, or HTML into an export.
"""

import re
import unicodedata
from datetime import date, datetime
from typing import Annotated, Literal
from zoneinfo import ZoneInfo, ZoneInfoNotFoundError

from pydantic import AfterValidator, AwareDatetime, BaseModel, ConfigDict, Field, model_validator

SCHEMA_VERSION: Literal[1] = 1

# Typography, spelled as escapes so no look-alike character hides in the source.
EN_DASH = "\u2013"
EM_DASH = "\u2014"
MIDDOT = "\u00b7"
OPEN_QUOTE, CLOSE_QUOTE = "\u201c", "\u201d"

Confidence = Literal["confirmed", "likely", "possible"]
"""How sure extraction is. `confirmed` means the user confirmed or corrected it."""


def _valid_zone(value: str) -> str:
    try:
        ZoneInfo(value)
    except (ZoneInfoNotFoundError, ValueError) as e:
        raise ValueError("unknown IANA time zone") from e
    return value


ZoneName = Annotated[str, AfterValidator(_valid_zone)]
ShortText = Annotated[str, Field(min_length=1, max_length=280)]
LongText = Annotated[str, Field(min_length=1, max_length=2_000)]


class _Model(BaseModel):
    model_config = ConfigDict(extra="forbid", frozen=True)


class Evidence(_Model):
    """Where an item came from. Resolves to transcript segments; the quote is optional context."""

    session_id: str = Field(min_length=1, max_length=64)
    at: AwareDatetime
    quote: str | None = Field(default=None, max_length=280)


class _Sourced(_Model):
    text: ShortText
    confidence: Confidence = "possible"
    # ADR-0004: nothing derived exists without evidence.
    evidence: list[Evidence] = Field(min_length=1, max_length=5)


class ActionItem(_Sourced):
    owner: str = Field(default="me", min_length=1, max_length=80)
    due: date | None = None


class Commitment(_Sourced):
    """A promise between people. `i_owe`: the user promised; `owed_to_me`: someone promised them."""

    direction: Literal["i_owe", "owed_to_me"]
    counterpart: str = Field(min_length=1, max_length=80)
    due: date | None = None


class Decision(_Sourced):
    pass


class Note(_Sourced):
    """An idea or an open question."""


class Highlight(_Model):
    """A moment the user marked. User input, so it needs no confidence."""

    session_id: str = Field(min_length=1, max_length=64)
    at: AwareDatetime
    note: str | None = Field(default=None, max_length=280)
    excerpt: str | None = Field(default=None, max_length=280)


class Moment(_Model):
    at: AwareDatetime
    text: ShortText


class SessionSummary(_Model):
    session_id: str = Field(min_length=1, max_length=64)
    title: str = Field(min_length=1, max_length=60)
    start: AwareDatetime
    end: AwareDatetime
    timezone: ZoneName
    summary: LongText | None = None
    key_moments: list[Moment] = Field(default_factory=list, max_length=20)

    @model_validator(mode="after")
    def _ordered(self) -> "SessionSummary":
        if self.end < self.start:
            raise ValueError("session ends before it starts")
        return self


class Gap(_Model):
    """A span inside the day that was not recorded, and why. Shown so absence isn't mistaken
    for silence."""

    start: AwareDatetime
    end: AwareDatetime
    reason: Literal["quiet_time", "paused", "interrupted", "not_captured"]
    label: str | None = Field(default=None, max_length=40)


class Coverage(_Model):
    recorded_minutes: int = Field(ge=0, le=24 * 60)
    first_start: AwareDatetime | None = None
    last_end: AwareDatetime | None = None
    gaps: list[Gap] = Field(default_factory=list, max_length=200)
    #: Audio still on the phone, not yet processed; non-zero makes the report partial.
    pending_minutes: int = Field(default=0, ge=0, le=24 * 60)


class DailyReport(_Model):
    schema_version: Literal[1] = SCHEMA_VERSION
    memory_day: date
    timezone: ZoneName
    generated_at: AwareDatetime
    status: Literal["complete", "partial"]
    headline: ShortText | None = None
    summary: list[ShortText] = Field(default_factory=list, max_length=6)
    action_items: list[ActionItem] = Field(default_factory=list, max_length=50)
    commitments: list[Commitment] = Field(default_factory=list, max_length=50)
    decisions: list[Decision] = Field(default_factory=list, max_length=50)
    ideas: list[Note] = Field(default_factory=list, max_length=50)
    open_questions: list[Note] = Field(default_factory=list, max_length=50)
    highlights: list[Highlight] = Field(default_factory=list, max_length=100)
    people: list[Annotated[str, Field(min_length=1, max_length=80)]] = Field(
        default_factory=list, max_length=50
    )
    topics: list[Annotated[str, Field(min_length=1, max_length=80)]] = Field(
        default_factory=list, max_length=50
    )
    sessions: list[SessionSummary] = Field(default_factory=list, max_length=50)
    coverage: Coverage

    @model_validator(mode="after")
    def _partial_is_honest(self) -> "DailyReport":
        if (self.coverage.pending_minutes > 0) != (self.status == "partial"):
            raise ValueError("status must be 'partial' exactly when audio is still pending")
        return self


# --- Markdown rendering ---------------------------------------------------------------------

_MD_SPECIAL = re.compile(r"([\\`*_{}\[\]<>()#+!|~&=-])")
_WHITESPACE = re.compile(r"\s+")
# Anything a renderer could turn into a clickable link: scheme URLs and bare www. hosts.
_LINKISH = re.compile(r"(?i)\b(?:[a-z][a-z0-9+.-]*://|www\.)\S+")
# "1." or "1)" at the start of a line would become an ordered list.
_LEADING_ORDINAL = re.compile(r"^(\d+)([.)])")
# Control, format (bidi overrides, zero-width), and line/paragraph separators.
_INVISIBLE_CATEGORIES = frozenset({"Cc", "Cf", "Zl", "Zp"})
_BLANK_RUNS = re.compile(r"\n{3,}")


def escape(text: str) -> str:
    """Inline-safe Markdown for untrusted text: one visible line that renders as plain text.

    - whitespace (including newlines) collapses to single spaces, then invisible and bidi
      control characters are removed;
    - URLs become inline code, which no renderer turns into a link;
    - every other structural character is backslash-escaped, including a leading ordinal.
    """
    flat = _WHITESPACE.sub(" ", text).strip()
    visible = "".join(c for c in flat if unicodedata.category(c) not in _INVISIBLE_CATEGORIES)
    parts: list[str] = []
    position = 0
    for match in _LINKISH.finditer(visible):
        parts.append(_escape_plain(visible[position : match.start()], at_start=position == 0))
        parts.append(f"`{match.group(0).replace('`', '')}`")
        position = match.end()
    parts.append(_escape_plain(visible[position:], at_start=position == 0))
    return "".join(parts)


def _escape_plain(text: str, *, at_start: bool) -> str:
    escaped = _MD_SPECIAL.sub(r"\\\1", text)
    return _LEADING_ORDINAL.sub(r"\1\\\2", escaped) if at_start else escaped


def _clock(moment: datetime, zone: ZoneInfo) -> str:
    local = moment.astimezone(zone)
    hour = local.hour % 12 or 12
    return f"{hour}:{local.minute:02d} {'AM' if local.hour < 12 else 'PM'}"


def _duration(minutes: int) -> str:
    hours, rest = divmod(minutes, 60)
    if hours == 0:
        return f"{rest} min"
    return f"{hours} h {rest} min" if rest else f"{hours} h"


def _day(value: date) -> str:
    return f"{value:%A, %B} {value.day}, {value.year}"


def _short_day(value: date) -> str:
    return f"{value:%a, %b} {value.day}"


class _Renderer:
    def __init__(self, report: DailyReport) -> None:
        self.report = report
        self.zone = ZoneInfo(report.timezone)
        self.titles = {s.session_id: s.title for s in report.sessions}
        self.lines: list[str] = []

    def time(self, moment: datetime, session_zone: str | None = None) -> str:
        text = _clock(moment, self.zone)
        # A session recorded while travelling also shows the time where it happened.
        if session_zone and session_zone != self.report.timezone:
            there = ZoneInfo(session_zone)
            text += f" ({_clock(moment, there)} {moment.astimezone(there).tzname()})"
        return text

    def source(self, evidence: list[Evidence]) -> str:
        first = evidence[0]
        where = self.titles.get(first.session_id)
        label = f"{self.time(first.at)}, {escape(where)}" if where else self.time(first.at)
        return f"_{label}_"

    def add(self, *lines: str) -> None:
        self.lines.extend(lines)

    def section(self, title: str) -> None:
        self.add("", f"## {title}", "")

    def render(self) -> str:
        r = self.report
        tz_abbrev = (
            datetime.now(self.zone).tzname()
            if r.coverage.first_start is None
            else (r.coverage.first_start.astimezone(self.zone).tzname())
        )
        self.add(f"# {_day(r.memory_day)}", "")
        self.add(f"_Your day in memory {MIDDOT} times in {r.timezone} ({tz_abbrev})_", "")
        self.add(self.coverage_line())
        if r.status == "partial":
            self.add(
                "",
                f"> **Partial report:** {_duration(r.coverage.pending_minutes)} of audio is still "
                "waiting to be processed. This report updates when it is.",
            )

        if r.headline or r.summary:
            self.section("At a glance")
            if r.headline:
                self.add(escape(r.headline), "")
            self.add(*(f"- {escape(point)}" for point in r.summary))

        self.checklist("Action items", r.action_items)
        self.commitments()
        self.items("Decisions", r.decisions)
        self.items("Ideas", r.ideas)
        self.items("Open questions", r.open_questions)
        self.highlights()
        self.people_and_topics()
        self.timeline()
        self.gaps()

        self.add(
            "",
            "---",
            f"_Generated {self.time(r.generated_at)} {MIDDOT} report v{r.schema_version}. "
            "Summaries help you navigate; the transcript is the record. "
            "Items marked possible were inferred and may be wrong._",
        )
        # Sections end with their own spacing; never leave more than one blank line.
        return _BLANK_RUNS.sub("\n\n", "\n".join(self.lines)).strip() + "\n"

    def coverage_line(self) -> str:
        c = self.report.coverage
        parts = []
        if c.first_start and c.last_end:
            parts.append(f"Recorded {self.time(c.first_start)} {EN_DASH} {self.time(c.last_end)}")
        parts.append(f"{_duration(c.recorded_minutes)} captured")
        count = len(self.report.sessions)
        parts.append(f"{count} session{'s' if count != 1 else ''}")
        return "**" + f" {MIDDOT} ".join(parts) + "**"

    def qualifier(self, confidence: Confidence) -> str:
        return "" if confidence == "confirmed" else f" ({confidence})"

    def due(self, value: date | None) -> str:
        return f" {EM_DASH} due {_short_day(value)}" if value else ""

    def checklist(self, title: str, items: list[ActionItem]) -> None:
        if not items:
            return
        self.section(title)
        for item in items:
            owner = "" if item.owner == "me" else f" {EM_DASH} {escape(item.owner)}"
            self.add(
                f"- [ ] {escape(item.text)}{owner}{self.due(item.due)} {MIDDOT} "
                f"{self.source(item.evidence)}{self.qualifier(item.confidence)}"
            )

    def commitments(self) -> None:
        mine = [c for c in self.report.commitments if c.direction == "i_owe"]
        theirs = [c for c in self.report.commitments if c.direction == "owed_to_me"]
        if not mine and not theirs:
            return
        self.section("Commitments")
        for label, group in (("You promised", mine), ("Promised to you", theirs)):
            if not group:
                continue
            self.add(f"**{label}**", "")
            for c in group:
                self.add(
                    f"- {escape(c.counterpart)}: {escape(c.text)}{self.due(c.due)} {MIDDOT} "
                    f"{self.source(c.evidence)}{self.qualifier(c.confidence)}"
                )
            self.add("")

    def items(self, title: str, items: list[Decision] | list[Note]) -> None:
        if not items:
            return
        self.section(title)
        for item in items:
            self.add(
                f"- {escape(item.text)} {MIDDOT} {self.source(item.evidence)}"
                f"{self.qualifier(item.confidence)}"
            )

    def highlights(self) -> None:
        if not self.report.highlights:
            return
        self.section("Highlights")
        for h in self.report.highlights:
            line = f"- **{self.time(h.at)}**"
            if h.note:
                line += f" {escape(h.note)}"
            if h.excerpt:
                line += f" {EM_DASH} {OPEN_QUOTE}{escape(h.excerpt)}{CLOSE_QUOTE}"
            self.add(line)

    def people_and_topics(self) -> None:
        r = self.report
        if not r.people and not r.topics:
            return
        self.section("People and topics")
        if r.people:
            self.add(f"- **People:** {', '.join(escape(p) for p in r.people)}")
        if r.topics:
            self.add(f"- **Topics:** {', '.join(escape(t) for t in r.topics)}")

    def timeline(self) -> None:
        if not self.report.sessions:
            return
        self.section("Timeline")
        for s in sorted(self.report.sessions, key=lambda s: s.start):
            start = self.time(s.start, s.timezone)
            end = self.time(s.end, s.timezone)
            self.add(f"### {start} {EN_DASH} {end} {MIDDOT} {escape(s.title)}", "")
            if s.summary:
                self.add(escape(s.summary), "")
            self.add(*(f"- {self.time(m.at)} {EM_DASH} {escape(m.text)}" for m in s.key_moments))
            if s.key_moments:
                self.add("")

    def gaps(self) -> None:
        gaps = self.report.coverage.gaps
        if not gaps:
            return
        self.section("Not recorded")
        reasons = {
            "quiet_time": "quiet time",
            "paused": "paused",
            "interrupted": "recording was interrupted",
            "not_captured": "audio wasn't captured",
        }
        for g in sorted(gaps, key=lambda g: g.start):
            label = f" ({escape(g.label)})" if g.label else ""
            span = f"{self.time(g.start)} {EN_DASH} {self.time(g.end)}"
            self.add(f"- {span}: {reasons[g.reason]}{label}")


def render_markdown(report: DailyReport) -> str:
    """The report as Markdown, for Google Drive, the share sheet, and plain-text clients."""
    return _Renderer(report).render()
