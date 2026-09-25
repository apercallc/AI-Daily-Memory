import os
from datetime import UTC, date, datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

import pytest
from pydantic import ValidationError

from app.reports import schema as report_schema
from app.reports.daily import (
    ActionItem,
    Commitment,
    Coverage,
    DailyReport,
    Decision,
    Evidence,
    Gap,
    Highlight,
    Moment,
    Note,
    SessionSummary,
    escape,
    render_markdown,
)

CHICAGO = ZoneInfo("America/Chicago")
EXAMPLE_PATH = Path(__file__).resolve().parents[2] / "docs" / "product" / "daily-report-example.md"


def at(hour: int, minute: int = 0, zone: ZoneInfo = CHICAGO, day: int = 22) -> datetime:
    return datetime(2026, 9, day, hour, minute, tzinfo=zone)


def ev(hour: int, minute: int = 0, session: str = "s1", quote: str | None = None) -> list[Evidence]:
    return [Evidence(session_id=session, at=at(hour, minute), quote=quote)]


def sample_report() -> DailyReport:
    """A realistic day. It also generates the example in docs/product."""
    return DailyReport(
        memory_day=date(2026, 9, 22),
        timezone="America/Chicago",
        generated_at=at(23, 4),
        status="complete",
        headline="Kafka migration approved; follow-ups for the payment incident are owned.",
        summary=[
            "Standup settled on the new consumer implementation.",
            "Priya needs the incident timeline by Thursday.",
            "An idea for a watch shortcut came up on the drive home.",
        ],
        action_items=[
            ActionItem(
                text="Send the incident timeline to Priya",
                due=date(2026, 9, 24),
                confidence="confirmed",
                evidence=ev(10, 42),
            ),
            ActionItem(
                text="Book a dentist appointment",
                evidence=ev(18, 5, "s3", quote="I really need to call the dentist"),
            ),
        ],
        commitments=[
            Commitment(
                text="review the failed-message dashboard",
                direction="i_owe",
                counterpart="Sam",
                due=date(2026, 9, 23),
                confidence="likely",
                evidence=ev(9, 12),
            ),
            Commitment(
                text="share the vendor quote",
                direction="owed_to_me",
                counterpart="Jordan",
                evidence=ev(14, 30, "s2"),
            ),
        ],
        decisions=[
            Decision(
                text="Use the new consumer implementation for the Kafka migration",
                confidence="confirmed",
                evidence=ev(8, 51),
            ),
        ],
        ideas=[Note(text="Add a watch shortcut for Quick Note", evidence=ev(18, 20, "s3"))],
        open_questions=[
            Note(text="Who owns on-call during the migration week?", evidence=ev(9, 5)),
        ],
        highlights=[
            Highlight(
                session_id="s2", at=at(15, 2), note="Pricing insight", excerpt="annual plans"
            ),
        ],
        people=["Priya", "Sam", "Jordan"],
        topics=["Kafka migration", "Payment incident"],
        sessions=[
            SessionSummary(
                session_id="s1",
                title="Standup and planning",
                start=at(8, 3),
                end=at(11, 58),
                timezone="America/Chicago",
                summary="Daily standup, then incident follow-up with Priya.",
                key_moments=[
                    Moment(at=at(8, 42), text="Daily standup"),
                    Moment(at=at(10, 40), text="Incident review"),
                ],
            ),
            SessionSummary(
                session_id="s2",
                title="Afternoon",
                start=at(13, 0),
                end=at(16, 45),
                timezone="America/Chicago",
            ),
            SessionSummary(
                session_id="s3",
                title="Drive home",
                start=at(17, 50),
                end=at(18, 40),
                timezone="America/Chicago",
            ),
        ],
        coverage=Coverage(
            recorded_minutes=7 * 60 + 12,
            first_start=at(8, 3),
            last_end=at(18, 40),
            gaps=[
                Gap(start=at(12, 0), end=at(13, 0), reason="quiet_time", label="Lunch"),
                Gap(start=at(16, 45), end=at(17, 50), reason="paused"),
            ],
        ),
    )


def test_example_in_docs_is_real_renderer_output() -> None:
    rendered = render_markdown(sample_report())
    if os.environ.get("UPDATE_GOLDEN") == "1":  # pragma: no cover - maintenance path
        EXAMPLE_PATH.parent.mkdir(parents=True, exist_ok=True)
        EXAMPLE_PATH.write_text(rendered, encoding="utf-8")
    assert EXAMPLE_PATH.read_text(encoding="utf-8") == rendered, (
        "docs/product/daily-report-example.md is stale; regenerate with "
        "UPDATE_GOLDEN=1 uv run pytest tests/test_daily_report.py"
    )


def test_published_json_schema_matches_the_models() -> None:
    assert report_schema.SCHEMA_PATH.read_text(encoding="utf-8") == report_schema.render(), (
        "contracts/daily-report.v1.schema.json is stale; run: uv run python -m app.reports.schema"
    )


def test_report_sections_appear_in_reading_order() -> None:
    md = render_markdown(sample_report())
    order = [
        "# Tuesday, September 22, 2026",
        "## At a glance",
        "## Action items",
        "## Commitments",
        "## Decisions",
        "## Ideas",
        "## Open questions",
        "## Highlights",
        "## People and topics",
        "## Timeline",
        "## Not recorded",
    ]
    positions = [md.index(heading) for heading in order]
    assert positions == sorted(positions)


def test_times_are_in_the_report_zone_and_cite_their_session() -> None:
    md = render_markdown(sample_report())

    assert "Send the incident timeline to Priya \u2014 due Thu, Sep 24" in md
    assert "_10:42 AM, Standup and planning_" in md
    # Unconfirmed items say so; confirmed ones don't.
    assert "Book a dentist appointment \u00b7 _6:05 PM, Drive home_ (possible)" in md
    assert "Standup and planning_ (confirmed)" not in md
    assert "**You promised**" in md
    assert "**Promised to you**" in md
    assert "- 12:00 PM \u2013 1:00 PM: quiet time (Lunch)" in md


def test_a_session_recorded_while_travelling_shows_both_times() -> None:
    new_york = ZoneInfo("America/New_York")
    report = DailyReport(
        memory_day=date(2026, 9, 22),
        timezone="America/Chicago",
        generated_at=at(23),
        status="complete",
        sessions=[
            SessionSummary(
                session_id="s",
                title="Client visit",
                start=datetime(2026, 9, 22, 9, 0, tzinfo=new_york),
                end=datetime(2026, 9, 22, 10, 0, tzinfo=new_york),
                timezone="America/New_York",
            ),
        ],
        coverage=Coverage(recorded_minutes=60),
    )

    assert "### 8:00 AM (9:00 AM EDT) \u2013 9:00 AM (10:00 AM EDT) \u00b7 Client visit" in (
        render_markdown(report)
    )


def test_model_text_cannot_inject_markdown_structure() -> None:
    hostile = (
        "# Pwned [click](https://evil.example) ![x](https://evil.example/p.png) <b>hi</b>\n- fake"
    )
    report = sample_report().model_copy(update={"headline": hostile})

    md = render_markdown(report)

    assert "\\# Pwned \\[click\\]\\(https://evil.example\\)" in md
    assert "\\!\\[x\\]" in md
    assert "\\<b\\>hi\\</b\\>" in md
    assert "\n- fake" not in md
    assert escape("  a\n\tb  ") == "a b"


def test_an_empty_day_renders_only_what_is_true() -> None:
    report = DailyReport(
        memory_day=date(2026, 9, 22),
        timezone="UTC",
        generated_at=datetime(2026, 9, 22, 23, tzinfo=UTC),
        status="partial",
        coverage=Coverage(recorded_minutes=0, pending_minutes=45),
    )

    md = render_markdown(report)

    assert "**0 min captured \u00b7 0 sessions**" in md
    assert "**Partial report:** 45 min of audio is still waiting" in md
    for heading in ("## At a glance", "## Action items", "## Timeline", "## Not recorded"):
        assert heading not in md


@pytest.mark.parametrize(
    ("minutes", "text"),
    [(0, "0 min"), (59, "59 min"), (60, "1 h"), (61, "1 h 1 min"), (432, "7 h 12 min")],
)
def test_durations_read_naturally(minutes: int, text: str) -> None:
    report = DailyReport(
        memory_day=date(2026, 9, 22),
        timezone="UTC",
        generated_at=datetime(2026, 9, 22, 23, tzinfo=UTC),
        status="complete",
        coverage=Coverage(recorded_minutes=minutes),
    )
    assert f"**{text} captured" in render_markdown(report)


def test_every_extracted_item_needs_evidence() -> None:
    with pytest.raises(ValidationError):
        ActionItem(text="Call Mike", evidence=[])


def test_status_must_match_pending_audio() -> None:
    base = sample_report()
    with pytest.raises(ValidationError):
        DailyReport.model_validate({**base.model_dump(), "status": "partial"})
    with pytest.raises(ValidationError):
        DailyReport.model_validate(
            {**base.model_dump(), "coverage": {"recorded_minutes": 1, "pending_minutes": 5}}
        )


@pytest.mark.parametrize(
    "update",
    [
        {"timezone": "Mars/Olympus_Mons"},
        {"timezone": "../../etc/passwd"},
        {"generated_at": datetime(2026, 9, 22, 23)},  # naive
        {"schema_version": 2},
        {"unexpected": "field"},
    ],
)
def test_invalid_reports_are_rejected(update: dict[str, object]) -> None:
    with pytest.raises(ValidationError):
        DailyReport.model_validate({**sample_report().model_dump(), **update})


def test_sessions_cannot_end_before_they_start() -> None:
    with pytest.raises(ValidationError):
        SessionSummary(
            session_id="s",
            title="Backwards",
            start=at(10),
            end=at(10) - timedelta(minutes=1),
            timezone="America/Chicago",
        )
