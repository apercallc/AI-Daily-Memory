import json
from datetime import date

import httpx
import pytest

from app.extraction import Numbered
from app.providers import ClaudeExtractionProvider, DeepgramProvider
from app.transcription import JobError, TranscribedSpan

pytestmark = pytest.mark.anyio


def _deepgram_body(
    *,
    language: str | None = "en",
    words: list[dict[str, object]] | None = None,
) -> dict[str, object]:
    if words is None:
        words = [
            {
                "word": "hello",
                "punctuated_word": "Hello,",
                "start": 0.0,
                "end": 0.5,
                "confidence": 0.98,
                "speaker": 0,
            },
            {
                "word": "world",
                "punctuated_word": "world.",
                "start": 0.6,
                "end": 1.1,
                "confidence": 0.91,
                "speaker": 0,
            },
        ]
    return {
        "results": {
            "channels": [
                {
                    "detected_language": language,
                    "alternatives": [{"words": words}],
                }
            ]
        }
    }


def _provider(handler: object) -> DeepgramProvider:
    return DeepgramProvider("dg-test-key", transport=httpx.MockTransport(handler))  # type: ignore[arg-type]


async def test_a_diarized_response_becomes_spans_with_speaker_and_confidence() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.headers["authorization"] == "Token dg-test-key"
        assert request.headers["content-type"] == "audio/aac"
        assert request.url.params["model"] == "nova-2"
        assert request.url.params["diarize"] == "true"
        assert request.content == b"the-audio-bytes"
        return httpx.Response(200, json=_deepgram_body())

    result = await _provider(handler).transcribe(
        b"the-audio-bytes", codec="aac-lc-adts", sample_rate_hz=16_000
    )

    assert result.language == "en"
    assert result.spans == [
        TranscribedSpan(0, 500, "Hello,", 0.98, "0"),
        TranscribedSpan(600, 1_100, "world.", 0.91, "0"),
    ]


async def test_an_unsupported_codec_is_never_sent_to_the_provider() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise AssertionError("must not call the provider for an unsupported codec")

    with pytest.raises(JobError) as caught:
        await _provider(handler).transcribe(b"x", codec="opus", sample_rate_hz=16_000)
    assert caught.value.code == "unsupported_codec"
    assert not caught.value.retryable


@pytest.mark.parametrize("status", [500, 502, 503, 504, 408])
async def test_server_errors_are_retryable(status: int) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(status, json={"err": "boom"})

    with pytest.raises(JobError) as caught:
        await _provider(handler).transcribe(b"x", codec="aac-lc-adts", sample_rate_hz=16_000)
    assert caught.value.retryable


async def test_rate_limiting_is_retryable_and_named() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(429, json={"err": "slow down"})

    with pytest.raises(JobError) as caught:
        await _provider(handler).transcribe(b"x", codec="aac-lc-adts", sample_rate_hz=16_000)
    assert caught.value.code == "provider_rate_limited"
    assert caught.value.retryable


async def test_unauthorized_is_not_retryable() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(401, json={"err": "bad key"})

    with pytest.raises(JobError) as caught:
        await _provider(handler).transcribe(b"x", codec="aac-lc-adts", sample_rate_hz=16_000)
    assert caught.value.code == "provider_auth_failed"
    assert not caught.value.retryable


async def test_a_timeout_is_retryable() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("slow", request=request)

    with pytest.raises(JobError) as caught:
        await _provider(handler).transcribe(b"x", codec="aac-lc-adts", sample_rate_hz=16_000)
    assert caught.value.code == "provider_timeout"
    assert caught.value.retryable


async def test_a_connection_failure_is_retryable() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("dns", request=request)

    with pytest.raises(JobError) as caught:
        await _provider(handler).transcribe(b"x", codec="aac-lc-adts", sample_rate_hz=16_000)
    assert caught.value.code == "provider_unreachable"
    assert caught.value.retryable


@pytest.mark.parametrize(
    "body",
    [
        "not json at all",
        {"results": {}},
        {"results": {"channels": []}},
        {"results": {"channels": [{"alternatives": []}]}},
        {"results": {"channels": [{"alternatives": [{"words": "not-a-list"}]}]}},
        {"results": {"channels": [{"alternatives": [{"words": [{"word": "x"}]}]}]}},
    ],
)
async def test_malformed_or_incomplete_responses_never_crash_the_worker(body: object) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        if isinstance(body, str):
            return httpx.Response(200, content=body.encode())
        return httpx.Response(200, json=body)

    with pytest.raises(JobError) as caught:
        await _provider(handler).transcribe(b"x", codec="aac-lc-adts", sample_rate_hz=16_000)
    assert caught.value.code == "invalid_provider_result"
    assert not caught.value.retryable


async def test_a_word_with_no_speaker_label_is_left_unattributed() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json=_deepgram_body(
                words=[{"word": "hi", "start": 0.0, "end": 0.2, "confidence": 0.5}]
            ),
        )

    result = await _provider(handler).transcribe(b"x", codec="aac-lc-adts", sample_rate_hz=16_000)
    assert result.spans[0].speaker is None


async def test_aclose_releases_the_underlying_client() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=_deepgram_body())

    provider = _provider(handler)
    await provider.transcribe(b"x", codec="aac-lc-adts", sample_rate_hz=16_000)
    await provider.aclose()
    assert provider._client.is_closed


# --- ClaudeExtractionProvider ------------------------------------------------------------------


def _claude_body(items: list[dict[str, object]]) -> dict[str, object]:
    return {"content": [{"type": "text", "text": json.dumps(items)}]}


def _extractor(handler: object) -> ClaudeExtractionProvider:
    return ClaudeExtractionProvider("claude-test-key", transport=httpx.MockTransport(handler))  # type: ignore[arg-type]


_LINES = (Numbered(index=0, speaker="A", text="Let's ship Friday."),)


async def test_extraction_sends_the_transcript_and_parses_items() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        assert request.headers["x-api-key"] == "claude-test-key"
        assert request.headers["anthropic-version"]
        body = json.loads(request.content)
        assert "Let's ship Friday." in body["messages"][0]["content"]
        return httpx.Response(
            200,
            json=_claude_body(
                [
                    {
                        "kind": "action",
                        "text": "Ship on Friday",
                        "confidence": "likely",
                        "evidence_segment_indices": [0],
                        "owner": "me",
                        "due": "2026-09-26",
                    },
                ],
            ),
        )

    result = await _extractor(handler).extract(_LINES)

    assert len(result.items) == 1
    item = result.items[0]
    assert item.kind == "action"
    assert item.owner == "me"
    assert item.due == date(2026, 9, 26)


async def test_an_empty_array_is_a_valid_result() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=_claude_body([]))

    result = await _extractor(handler).extract(_LINES)
    assert result.items == ()


async def test_a_commitment_parses_direction_and_counterpart() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(
            200,
            json=_claude_body(
                [
                    {
                        "kind": "commitment",
                        "text": "Send the deck",
                        "confidence": "confirmed",
                        "evidence_segment_indices": [0],
                        "direction": "i_owe",
                        "counterpart": "Sam",
                        "quote": "I'll send it",
                    },
                ],
            ),
        )

    result = await _extractor(handler).extract(_LINES)
    item = result.items[0]
    assert item.direction == "i_owe"
    assert item.counterpart == "Sam"
    assert item.quote == "I'll send it"


@pytest.mark.parametrize("status", [500, 502, 503, 504, 408])
async def test_extraction_server_errors_are_retryable(status: int) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(status, json={"err": "boom"})

    with pytest.raises(JobError) as caught:
        await _extractor(handler).extract(_LINES)
    assert caught.value.retryable


async def test_extraction_rate_limiting_is_retryable_and_named() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(429, json={"err": "slow down"})

    with pytest.raises(JobError) as caught:
        await _extractor(handler).extract(_LINES)
    assert caught.value.code == "provider_rate_limited"
    assert caught.value.retryable


async def test_extraction_unauthorized_is_not_retryable() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(401, json={"err": "bad key"})

    with pytest.raises(JobError) as caught:
        await _extractor(handler).extract(_LINES)
    assert caught.value.code == "provider_auth_failed"
    assert not caught.value.retryable


async def test_extraction_timeout_is_retryable() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ReadTimeout("slow", request=request)

    with pytest.raises(JobError) as caught:
        await _extractor(handler).extract(_LINES)
    assert caught.value.code == "provider_timeout"
    assert caught.value.retryable


async def test_extraction_connection_failure_is_retryable() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("dns", request=request)

    with pytest.raises(JobError) as caught:
        await _extractor(handler).extract(_LINES)
    assert caught.value.code == "provider_unreachable"
    assert caught.value.retryable


@pytest.mark.parametrize(
    "body",
    [
        {"content": []},
        {"content": [{"type": "text", "text": "not json"}]},
        {"content": [{"type": "text", "text": "{}"}]},
        {"content": [{"type": "text", "text": "[1, 2]"}]},
        {"content": [{"type": "text", "text": json.dumps([{"kind": "action"}])}]},
    ],
)
async def test_malformed_claude_responses_never_crash_the_worker(body: dict[str, object]) -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=body)

    with pytest.raises(JobError) as caught:
        await _extractor(handler).extract(_LINES)
    assert caught.value.code == "invalid_extraction_result"
    assert not caught.value.retryable


async def test_extraction_aclose_releases_the_underlying_client() -> None:
    def handler(request: httpx.Request) -> httpx.Response:
        return httpx.Response(200, json=_claude_body([]))

    provider = _extractor(handler)
    await provider.extract(_LINES)
    await provider.aclose()
    assert provider._client.is_closed
