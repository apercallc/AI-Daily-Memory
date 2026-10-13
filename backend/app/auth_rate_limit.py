"""Bounded process-local sliding-window limits for public authentication routes."""

import logging
import math
import os
import time
from collections import OrderedDict, deque
from collections.abc import Awaitable, Callable, Mapping
from contextlib import suppress
from ipaddress import ip_address

from fastapi import HTTPException, Request

from app.config import Settings

logger = logging.getLogger("app.auth_rate_limit")


def warn_if_client_ips_collapse(
    settings: Settings, environ: Mapping[str, str] | None = None
) -> bool:
    """Logs an error at startup when every client would share one rate-limit bucket.

    Behind a managed platform's proxy (it injects `PORT`) the socket peer is the proxy, so
    with `TRUST_RAILWAY_X_REAL_IP` off all signups and logins count against one address: one
    abusive client locks everyone out of the public auth routes. Failing the boot would break
    deploys, so it is a loud log line instead; returns whether the warning fired."""
    env = os.environ if environ is None else environ
    if (
        env.get("PORT")
        and not settings.trust_railway_x_real_ip
        and settings.app_env in ("staging", "production")
    ):
        logger.error(
            "auth_rate_limit_shared_proxy_bucket",
            extra={"fix": "set TRUST_RAILWAY_X_REAL_IP=true behind Railway's public edge"},
        )
        return True
    return False


class AuthRateLimiter:
    """Limit short bursts without persisting client addresses or adding infrastructure."""

    def __init__(
        self,
        *,
        clock: Callable[[], float] = time.monotonic,
        max_buckets: int = 4_096,
    ) -> None:
        if max_buckets < 1:
            raise ValueError("max_buckets must be positive")
        self._clock = clock
        self._max_buckets = max_buckets
        self._buckets: OrderedDict[str, deque[float]] = OrderedDict()

    async def retry_after(self, key: str, *, limit: int, window_seconds: float) -> float | None:
        """Return remaining wait seconds when the window is full; otherwise record an attempt."""
        if limit < 1 or window_seconds <= 0:
            raise ValueError("limit and window_seconds must be positive")

        now = self._clock()
        attempts = self._buckets.get(key)
        if attempts is None:
            attempts = deque()
            self._buckets[key] = attempts
        else:
            while attempts and attempts[0] <= now - window_seconds:
                attempts.popleft()
            self._buckets.move_to_end(key)

        if len(attempts) >= limit:
            return max(0.0, attempts[0] + window_seconds - now)

        attempts.append(now)
        if len(self._buckets) > self._max_buckets:
            self._buckets.popitem(last=False)
        return None


def client_address(request: Request) -> str:
    """The client address the rate limiter keys on (the proxy's X-Real-IP only when trusted)."""
    client = request.client
    address = client.host if client is not None else "unknown"
    settings: Settings = request.app.state.settings
    if settings.trust_railway_x_real_ip:
        real_ip_headers = request.headers.getlist("x-real-ip")
        if len(real_ip_headers) == 1:
            with suppress(ValueError):
                address = str(ip_address(real_ip_headers[0]))
    return address


def auth_rate_limit(
    action: str,
    *,
    limit: int,
    window_seconds: float,
) -> Callable[[Request], Awaitable[None]]:
    """FastAPI dependency for a public authentication route."""

    async def enforce(request: Request) -> None:
        address = client_address(request)
        limiter: AuthRateLimiter = request.app.state.auth_rate_limiter
        retry_after = await limiter.retry_after(
            f"{action}:{address}",
            limit=limit,
            window_seconds=window_seconds,
        )
        if retry_after is not None:
            raise HTTPException(
                status_code=429,
                detail="Too many attempts. Try again later.",
                headers={"Retry-After": str(math.ceil(retry_after))},
            )

    return enforce
