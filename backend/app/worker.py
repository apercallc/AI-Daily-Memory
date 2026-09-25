"""Processing worker: claims jobs, runs their handler, records the outcome, and sweeps
temporary audio past its retention.

    python -m app.worker

Handlers are registered only for stages that are enabled. With transcription disabled
(Gate B) the worker claims no transcribe jobs; the API also refuses new uploads then.
"""

import asyncio
import logging
import os
import signal
import socket
import time
from collections.abc import Awaitable, Callable, Mapping
from datetime import datetime
from typing import Protocol

from sqlalchemy.ext.asyncio import AsyncSession, async_sessionmaker

from app.config import Settings, get_settings
from app.db import Database
from app.jobs import ClaimedJob, claim, complete, fail
from app.observability import configure_logging
from app.providers import DeepgramProvider
from app.retention import sweep
from app.storage import ObjectStore, object_store_from
from app.transcription import JobError, TranscribeHandler, TranscriptionProvider

logger = logging.getLogger("app.worker")


class Handler(Protocol):
    async def __call__(self, job: ClaimedJob) -> None: ...


Sweeper = Callable[[], Awaitable[object]]


class Worker:
    def __init__(
        self,
        database: Database,
        handlers: Mapping[str, Handler],
        *,
        worker_id: str | None = None,
        now: Callable[[], datetime | None] = lambda: None,
        sweeper: Sweeper | None = None,
        sweep_every_seconds: float = 300.0,
    ) -> None:
        self.database = database
        self.handlers = dict(handlers)
        self.worker_id = worker_id or f"{socket.gethostname()}:{os.getpid()}"
        # None means "database clock" (app.jobs); tests pin a time.
        self.now = now
        self.sweeper = sweeper
        self.sweep_every_seconds = sweep_every_seconds
        self._last_sweep: float | None = None

    async def run_once(self) -> bool:
        """Processes at most one job. Returns False when nothing was runnable."""
        if not self.handlers:
            return False
        async with self.database.sessions() as session:
            job = await claim(
                session,
                kinds=tuple(self.handlers),
                worker=self.worker_id,
                now=self.now(),
            )
        if job is None:
            return False
        handler = self.handlers[job.kind]
        try:
            await handler(job)
        except JobError as e:
            await self._fail(handler, job, e.code, retryable=e.retryable)
        except Exception:
            # Unknown failures retry (bounded) and are logged by type and location only.
            logger.exception("job_handler_crashed", extra={"job_id": str(job.id)})
            await self._fail(handler, job, "unexpected_error", retryable=True)
        else:
            async with self.database.sessions() as session:
                if not await complete(session, job):
                    logger.warning("job_lease_lost", extra={"job_id": str(job.id)})
        return True

    async def _fail(self, handler: Handler, job: ClaimedJob, code: str, *, retryable: bool) -> None:
        async with self.database.sessions() as session:
            state = await fail(session, job, error_code=code, retryable=retryable, now=self.now())
        logger.warning(
            "job_failed",
            extra={"job_id": str(job.id), "error_code": code, "next_state": state},
        )
        on_dead = getattr(handler, "on_dead", None)
        if state == "dead" and on_dead is not None:
            try:
                await on_dead(job, code)
            except Exception:
                # The retention sweep finishes the cleanup; never lose the failure itself.
                logger.exception("job_dead_hook_failed", extra={"job_id": str(job.id)})

    async def _maybe_sweep(self) -> None:
        if self.sweeper is None:
            return
        now = time.monotonic()
        if self._last_sweep is not None and now - self._last_sweep < self.sweep_every_seconds:
            return
        self._last_sweep = now
        await self.sweeper()

    async def run_forever(self, stop: asyncio.Event, *, idle_seconds: float = 2.0) -> None:
        while not stop.is_set():
            try:
                await self._maybe_sweep()
                worked = await self.run_once()
            except Exception:
                # A database blip must not kill the process; back off and try again.
                logger.exception("worker_iteration_failed")
                worked = False
            if not worked:
                try:
                    await asyncio.wait_for(stop.wait(), timeout=idle_seconds)
                except TimeoutError:
                    continue


def provider_for(settings: Settings) -> TranscriptionProvider | None:
    """None while disabled (Gate B, ADR-0018): a setting alone never turns real audio into a
    provider call — see the module docstrings in `providers.py` and `transcription.py`.
    """
    if settings.transcription_provider == "disabled":
        return None
    if settings.deepgram_api_key is None:
        raise SystemExit("DEEPGRAM_API_KEY is required when transcription_provider=deepgram")
    return DeepgramProvider(
        settings.deepgram_api_key.get_secret_value(), model=settings.deepgram_model
    )


def handlers_for(
    settings: Settings,
    sessions: async_sessionmaker[AsyncSession] | None = None,
    store: ObjectStore | None = None,
) -> dict[str, Handler]:
    """Enabled stages only. Transcription stays off until a provider passes Gate B."""
    provider = provider_for(settings)
    if provider is None:
        return {}
    if sessions is None or store is None:
        raise SystemExit("a database and object store are required to run transcription")
    return {"transcribe": TranscribeHandler(sessions, store, provider)}


async def _main(settings: Settings) -> None:  # pragma: no cover - process entry point
    if settings.database_url is None:
        raise SystemExit("DATABASE_URL is not set")
    database = Database(settings.database_url.get_secret_value())
    store = object_store_from(settings)
    handlers = handlers_for(settings, database.sessions, store)
    if not handlers:
        logger.warning("worker_no_stages_enabled")

    async def sweeper() -> object:
        if store is None:
            return None
        return await sweep(
            database.sessions, store, url_ttl_seconds=settings.upload_url_ttl_seconds
        )

    stop = asyncio.Event()
    loop = asyncio.get_running_loop()
    for sig in (signal.SIGTERM, signal.SIGINT):
        loop.add_signal_handler(sig, stop.set)
    try:
        await Worker(database, handlers, sweeper=sweeper).run_forever(stop)
    finally:
        await database.dispose()


if __name__ == "__main__":  # pragma: no cover
    configure_logging(get_settings().log_level)
    asyncio.run(_main(get_settings()))
