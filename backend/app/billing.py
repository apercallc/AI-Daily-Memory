"""Stripe, called directly over httpx (like the other providers), and webhook verification."""

import hashlib
import hmac
import time
from typing import Any, Protocol

import httpx


class StripeError(Exception):
    """Stripe was unreachable or refused a request we made."""


class Stripe(Protocol):
    async def post(self, path: str, data: dict[str, str]) -> dict[str, Any]: ...


class StripeClient:
    def __init__(
        self,
        secret_key: str,
        *,
        base_url: str = "https://api.stripe.com/v1",
        timeout_seconds: float = 15.0,
        transport: httpx.AsyncBaseTransport | None = None,
    ) -> None:
        self._base_url = base_url
        self._client = httpx.AsyncClient(
            timeout=timeout_seconds, auth=(secret_key, ""), transport=transport
        )

    async def aclose(self) -> None:
        await self._client.aclose()

    async def post(self, path: str, data: dict[str, str]) -> dict[str, Any]:
        try:
            response = await self._client.post(f"{self._base_url}{path}", data=data)
        except httpx.HTTPError as error:
            raise StripeError("unreachable") from error
        if response.status_code >= 300:
            raise StripeError(f"status_{response.status_code}")
        body = response.json()
        if not isinstance(body, dict):
            raise StripeError("bad_shape")
        return body


def verify_signature(
    payload: bytes, header: str, secret: str, *, now: float | None = None, tolerance: int = 300
) -> bool:
    """Stripe's `Stripe-Signature` scheme: HMAC-SHA256 of `{timestamp}.{payload}`, rejected when
    the timestamp is old (replay) or no `v1` signature matches."""
    parts = [p.split("=", 1) for p in header.split(",") if "=" in p]
    timestamp = next((v for k, v in parts if k == "t"), None)
    signatures = [v for k, v in parts if k == "v1"]
    if timestamp is None or not timestamp.isdigit() or not signatures:
        return False
    if abs((now if now is not None else time.time()) - int(timestamp)) > tolerance:
        return False
    expected = hmac.new(
        secret.encode(), f"{timestamp}.".encode() + payload, hashlib.sha256
    ).hexdigest()
    return any(hmac.compare_digest(expected, s) for s in signatures)
