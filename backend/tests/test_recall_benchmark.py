"""Known-day recall benchmark (TODO.md Phase 4 "Recall evaluation"): seeds a realistic,
multi-day, multi-kind memory corpus with *known* correct answers, asks the real `/v1/ask`
endpoint a battery of questions about it, and scores the results with
`app.recall_evaluation`. Unlike the Android extraction gold dataset, there is no subjective
"what would a human extract from ambiguous speech" judgment here: every item below is
seeded directly, so the correct answer to each question is simply what was seeded — the
same reason `tests/test_web_api.py`'s individual ask tests already assert pass/fail per
question. What this file adds on top of that is an aggregate, quantified measurement
(Recall@K, precision@K, abstention accuracy) across many cases at once, which is what
TODO.md's "Measure Recall@K, ranking, temporal accuracy, evidence-source accuracy, and
insufficient-evidence abstention" actually asks for.

Deliberately out of scope here (see docs/product/RECALL_BENCHMARK.md): calendar-week/month
boundary cases ("last week", "last month") are not re-tested against a live clock, because
their correctness would then depend on which day of the week this suite happens to run —
`tests/test_recall.py` already covers that math with fixed dates. Also out of scope: a
human-curated set of realistically-worded, ambiguous real-world questions and a judgment
call on "good enough" ranking for those — this suite's questions are direct and unambiguous
by construction, which is exactly what makes them gradable without a human in the loop.
"""

from collections.abc import Iterator
from datetime import UTC, datetime, timedelta

import pytest
from fastapi.testclient import TestClient
from sqlalchemy import Engine

from app.main import create_app
from app.recall_evaluation import RecallCase, abstention_accuracy, mean_recall_at_k, score_case
from tests.conftest import make_account, settings_for
from tests.test_web_api import add_item, user_of, web_session

pytestmark = pytest.mark.anyio


@pytest.fixture
def client(db: Engine, database_url: str) -> Iterator[TestClient]:
    # Generous limit: one test asks the whole benchmark's worth of questions in one run.
    with TestClient(create_app(settings_for(database_url, daily_ask_limit=100))) as c:
        yield c


def _ask(client: TestClient, headers: dict[str, str], question: str) -> list[str]:
    response = client.post("/v1/ask", json={"question": question}, headers=headers)
    assert response.status_code == 200, response.text
    return [item["id"] for item in response.json()["items"]]


def test_known_day_recall_benchmark(client: TestClient, db: Engine) -> None:
    headers = web_session(client)
    user = user_of(db, "me@example.com")
    now = datetime.now(UTC)

    def day(delta: int) -> datetime:
        return now - timedelta(days=delta)

    decision_today = add_item(
        db, user, "Decided to migrate the database", kind="decision", day=day(0)
    )
    add_item(db, user, "A totally unrelated idea about gardening", kind="idea", day=day(0))

    decision_yesterday = add_item(
        db, user, "Chose Postgres for the new service", kind="decision", day=day(1)
    )
    idea_yesterday = add_item(
        db, user, "Cache the daily report instead of rebuilding it", kind="idea", day=day(1)
    )

    action_recent = add_item(
        db, user, "Draft the launch checklist", kind="action", owner="Marcus", day=day(2)
    )
    add_item(db, user, "Draft an old onboarding doc", kind="action", owner="Marcus", day=day(10))

    commitment_item = add_item(
        db,
        user,
        "Send Sam the pricing deck",
        kind="commitment",
        counterpart="Sam",
        direction="i_owe",
        day=day(1),
    )

    question_recent = add_item(
        db, user, "Do we need the watch app at launch", kind="question", day=day(4)
    )
    add_item(db, user, "Should we ever revisit the old logo", kind="question", day=day(20))

    dismissed_item = add_item(
        db, user, "Secret dismissed decision about layoffs", kind="decision", day=day(1)
    )
    client.patch(f"/v1/memory/items/{dismissed_item}", json={"dismissed": True}, headers=headers)

    corrected_item = add_item(db, user, "Vague thing", kind="idea", day=day(2))
    client.patch(
        f"/v1/memory/items/{corrected_item}",
        json={"text": "Clear thing about the launch"},
        headers=headers,
    )

    ranking_best = add_item(
        db, user, "Send the quarterly budget report to finance", kind="idea", day=day(1)
    )
    ranking_decoy = add_item(db, user, "File the monthly report", kind="idea", day=day(1))

    other_account = make_account(db)
    add_item(db, other_account.user_id, "Secret merger plan with a rival company", kind="decision")

    cases = [
        RecallCase("same_day_kind", "What did I decide today?", now.date(), (str(decision_today),)),
        RecallCase(
            "yesterday_keyword",
            "What about caching the daily report?",
            now.date(),
            (str(idea_yesterday),),
        ),
        RecallCase(
            "yesterday_window_kind",
            "What did I decide yesterday?",
            now.date(),
            (str(decision_yesterday),),
        ),
        RecallCase(
            "last_3_days_owner",
            "What tasks does Marcus have in the last 3 days?",
            now.date(),
            (str(action_recent),),
        ),
        RecallCase(
            "commitment_direction", "What did I promise Sam?", now.date(), (str(commitment_item),)
        ),
        RecallCase(
            "question_kind_recent",
            "What open questions do I have from the last 5 days?",
            now.date(),
            (str(question_recent),),
        ),
        RecallCase(
            "abstain_no_match",
            "Tell me about quantum computing",
            now.date(),
            expected_abstain=True,
        ),
        RecallCase(
            "dismissed_excluded", "secret dismissed layoffs", now.date(), expected_abstain=True
        ),
        RecallCase(
            "corrected_wording",
            "what's clear about the launch?",
            now.date(),
            (str(corrected_item),),
        ),
        RecallCase(
            "ranking_top1",
            "What about the quarterly budget report?",
            now.date(),
            (str(ranking_best),),
        ),
        RecallCase(
            "cross_account_isolation",
            "merger plan with a rival company",
            now.date(),
            expected_abstain=True,
        ),
    ]

    results = [score_case(case, _ask(client, headers, case.question)) for case in cases]

    failures = {
        r.case_id: r
        for r in results
        if r.recall_at_k[1] < 1.0 or r.precision_at_k[1] < 1.0 or not r.abstain_correct
    }
    assert not failures, failures

    assert mean_recall_at_k(results, k=1) == 1.0
    assert abstention_accuracy(results) == 1.0

    # The ranking case specifically: both "report" items match, but the one sharing every
    # term with the question must be the top-ranked hit, not merely present somewhere.
    ranking_result = next(r for r in results if r.case_id == "ranking_top1")
    assert ranking_result.retrieved_ids[0] == str(ranking_best)
    assert str(ranking_decoy) not in ranking_result.retrieved_ids[:1]
