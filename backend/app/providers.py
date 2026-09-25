"""Real transcription providers behind `transcription.TranscriptionProvider` (Gate B).

Wiring one in here is a technical decision only. Turning it on for real users additionally
needs the two Gate B reviews TODO.md lists (per-user-day cost model, provider terms: no
training on customer data, bounded retention, a DPA, region) and the all-party-consent legal
review — none of which this module can complete. `Settings.transcription_provider` defaults to
`disabled` and stays that way until an operator sets it deliberately (see ADR-0018).
"""

import logging

import httpx

from app.transcription import JobError, TranscribedSpan, TranscriptResult

logger = logging.getLogger("app.providers")

# aac-lc-adts is the only capture format the phone produces today (ADR-0007).
_CONTENT_TYPES = {"aac-lc-adts": "audio/aac"}

_RETRYABLE_STATUS = frozenset({408, 429, 500, 502, 503, 504})


class DeepgramProvider:
    """Deepgram's prerecorded REST endpoint (nova-2): diarization, word timestamps, and
    per-word confidence in one response, with a stable, well-documented HTTP contract — so this
    talks to it directly over `httpx` rather than pulling in a provider SDK (AGENTS.md: prefer
    stable, production-ready dependencies; ADR-0018).
    """

    name = "deepgram"

    def __init__(
        self,
        api_key: str,
        *,
        model: str = "nova-2",
        base_url: str = "https://api.deepgram.com/v1/listen",
        timeout_seconds: float = 60.0,
        # Test-only seam: a MockTransport instead of a real socket. Never set in production.
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self.model = model
        self._api_key = api_key
        self._base_url = base_url
        self._client = httpx.AsyncClient(timeout=timeout_seconds, transport=transport)

    async def aclose(self) -> None:
        await self._client.aclose()

    async def transcribe(
        self,
        audio: bytes,
        *,
        codec: str,
        sample_rate_hz: int,
    ) -> TranscriptResult:
        content_type = _CONTENT_TYPES.get(codec)
        if content_type is None:
            raise JobError("unsupported_codec", retryable=False)

        params = {
            "model": self.model,
            "diarize": "true",
            "punctuate": "true",
            "smart_format": "true",
            "sample_rate": str(sample_rate_hz),
            "encoding": "aac",
        }
        headers = {
            "Authorization": f"Token {self._api_key}",
            "Content-Type": content_type,
        }

        try:
            response = await self._client.post(
                self._base_url, params=params, headers=headers, content=audio
            )
        except httpx.TimeoutException as e:
            raise JobError("provider_timeout", retryable=True) from e
        except httpx.HTTPError as e:
            raise JobError("provider_unreachable", retryable=True) from e

        if response.status_code != httpx.codes.OK:
            retryable = response.status_code in _RETRYABLE_STATUS
            logger.warning(
                "provider_error_status",
                extra={"provider": self.name, "status": response.status_code},
            )
            code = "provider_rate_limited" if response.status_code == 429 else "provider_error"
            raise JobError(code, retryable=retryable)

        try:
            body = response.json()
        except ValueError as e:
            raise JobError("invalid_provider_result", retryable=False) from e

        return _parse(body)


def _parse(body: object) -> TranscriptResult:
    try:
        results = _require_dict(body)["results"]
        channels = _require_dict(results)["channels"]
        channel = _require_dict(_first(channels))
        alt = _require_dict(_first(channel["alternatives"]))
        language = channel.get("detected_language")
        words = alt.get("words", [])
        if not isinstance(words, list):
            raise TypeError("words must be a list")
    except (KeyError, IndexError, TypeError) as e:
        raise JobError("invalid_provider_result", retryable=False) from e

    spans = [_span_from_word(w) for w in words if isinstance(w, dict)]
    return TranscriptResult(language=language if isinstance(language, str) else None, spans=spans)


def _span_from_word(word: dict[str, object]) -> TranscribedSpan:
    try:
        start_ms = int(_require_float(word["start"]) * 1000)
        end_ms = int(_require_float(word["end"]) * 1000)
        text = str(word["punctuated_word"] if "punctuated_word" in word else word["word"])
    except (KeyError, TypeError, ValueError) as e:
        raise JobError("invalid_provider_result", retryable=False) from e
    confidence_raw = word.get("confidence")
    confidence = float(confidence_raw) if isinstance(confidence_raw, (int, float)) else None
    speaker_raw = word.get("speaker")
    speaker = str(speaker_raw) if speaker_raw is not None else None
    return TranscribedSpan(
        start_ms=start_ms, end_ms=end_ms, text=text, confidence=confidence, speaker=speaker
    )


def _require_dict(value: object) -> dict[str, object]:
    if not isinstance(value, dict):
        raise TypeError("expected a JSON object")
    return value


def _first(value: object) -> object:
    if not isinstance(value, list) or not value:
        raise IndexError("expected a non-empty JSON array")
    return value[0]


def _require_float(value: object) -> float:
    if not isinstance(value, int | float):
        raise TypeError("expected a number")
    return float(value)
