import pytest
from fastapi import FastAPI
from starlette.requests import Request

from app.auth_rate_limit import AuthRateLimiter, auth_rate_limit
from app.config import Settings


@pytest.mark.anyio
async def test_allows_up_to_limit_then_returns_retry_after() -> None:
    now = 100.0
    limiter = AuthRateLimiter(clock=lambda: now)

    assert await limiter.retry_after("login:client", limit=2, window_seconds=60) is None
    assert await limiter.retry_after("login:client", limit=2, window_seconds=60) is None
    assert await limiter.retry_after("login:client", limit=2, window_seconds=60) == 60


@pytest.mark.anyio
async def test_expired_attempts_do_not_consume_the_next_window() -> None:
    now = 100.0
    limiter = AuthRateLimiter(clock=lambda: now)
    assert await limiter.retry_after("signup:client", limit=1, window_seconds=10) is None

    now = 110.0
    assert await limiter.retry_after("signup:client", limit=1, window_seconds=10) is None


@pytest.mark.anyio
async def test_rate_limit_buckets_are_isolated_and_bounded() -> None:
    now = 100.0
    limiter = AuthRateLimiter(clock=lambda: now, max_buckets=2)
    assert await limiter.retry_after("login:one", limit=1, window_seconds=60) is None
    assert await limiter.retry_after("login:two", limit=1, window_seconds=60) is None
    assert await limiter.retry_after("login:three", limit=1, window_seconds=60) is None
    assert len(limiter._buckets) == 2
    assert await limiter.retry_after("login:three", limit=1, window_seconds=60) == 60


def _request_with_real_ip_headers(*values: bytes) -> tuple[Request, AuthRateLimiter]:
    app = FastAPI()
    app.state.settings = Settings(app_env="test", trust_railway_x_real_ip=True)
    limiter = AuthRateLimiter()
    app.state.auth_rate_limiter = limiter
    request = Request(
        {
            "type": "http",
            "method": "POST",
            "path": "/v1/login",
            "headers": [(b"x-real-ip", value) for value in values],
            "query_string": b"",
            "client": ("10.0.0.5", 1234),
            "server": ("api.internal", 8000),
            "scheme": "http",
            "http_version": "1.1",
            "app": app,
        }
    )
    return request, limiter


@pytest.mark.anyio
async def test_railway_client_ip_header_is_used_only_when_single_valid_address() -> None:
    request, limiter = _request_with_real_ip_headers(b"198.51.100.42")
    await auth_rate_limit("login", limit=1, window_seconds=60)(request)
    assert "login:198.51.100.42" in limiter._buckets

    duplicate_request, duplicate_limiter = _request_with_real_ip_headers(
        b"198.51.100.42",
        b"198.51.100.43",
    )
    await auth_rate_limit("login", limit=1, window_seconds=60)(duplicate_request)
    assert "login:10.0.0.5" in duplicate_limiter._buckets

    malformed_request, malformed_limiter = _request_with_real_ip_headers(b"not-an-ip")
    await auth_rate_limit("login", limit=1, window_seconds=60)(malformed_request)
    assert "login:10.0.0.5" in malformed_limiter._buckets


@pytest.mark.anyio
async def test_forged_railway_client_ip_is_ignored_when_trust_is_disabled() -> None:
    request, limiter = _request_with_real_ip_headers(b"198.51.100.42")
    request.app.state.settings = Settings(app_env="test", trust_railway_x_real_ip=False)

    await auth_rate_limit("login", limit=1, window_seconds=60)(request)

    assert "login:10.0.0.5" in limiter._buckets
