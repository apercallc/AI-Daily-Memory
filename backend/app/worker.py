"""Processing worker: claims jobs, runs their handler, records the outcome.

    python -m app.worker

Handlers are registered only for stages that are enabled. With transcription disabled
(Gate B) the worker claims no transcribe jobs; they stay queued, visible, and unprocessed.
"""

import asyncio
import logging
import os
import socket
from collections.abc import Awaitable, Callable, Mapping
from datetime import UTC, datetime

from app.config import Settings, get_settings
from app.db import Database
from app.jobs import ClaimedJob, claim, complete, fail
from app.observability import configure_logging
from app.transcription import JobError

logger = logging.getLogger("app.worker")

Handler = Callable[[ClaimedJob], Awaitable[None]]


class Worker:
    def __init__(
        self,
        database: Database,
        handlers: Mapping[str, Handler],
        *,
        worker_id: str | None = None,
        now: Callable[[], datetime] = lambda: datetime.now(UTC),
    ) -> None:
        self.database = database
        self.handlers = dict(handlers)
        self.worker_id = worker_id or f"{socket.gethostname()}:{os.getpid()}"
        self.now = now

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
        try:
            await self.handlers[job.kind](job)
        except JobError as e:
            await self._fail(job, e.code, retryable=e.retryable)
        except Exception:
            # Unknown failures retry (bounded) and are logged by type and location only.
            logger.exception("job_handler_crashed", extra={"job_id": str(job.id)})
            await self._fail(job, "unexpected_error", retryable=True)
        else:
            async with self.database.sessions() as session:
                if not await complete(session, job):
                    logger.warning("job_lease_lost", extra={"job_id": str(job.id)})
        return True

    async def _fail(self, job: ClaimedJob, code: str, *, retryable: bool) -> None:
        async with self.database.sessions() as session:
            state = await fail(session, job, error_code=code, retryable=retryable, now=self.now())
        logger.warning(
            "job_failed",
            extra={"job_id": str(job.id), "error_code": code, "next_state": state},
        )

    async def run_forever(self, stop: asyncio.Event, *, idle_seconds: float = 2.0) -> None:
        while not stop.is_set():
            worked = await self.run_once()
            if not worked:
                try:
                    await asyncio.wait_for(stop.wait(), timeout=idle_seconds)
                except TimeoutError:
                    continue


def handlers_for(settings: Settings) -> dict[str, Handler]:
    """Enabled stages only. Transcription stays off until a provider passes Gate B."""
    return {}


async def _main(settings: Settings) -> None:  # pragma: no cover - process entry point
    if settings.database_url is None:
        raise SystemExit("DATABASE_URL is not set")
    database = Database(settings.database_url.get_secret_value())
    handlers = handlers_for(settings)
    if not handlers:
        logger.warning("worker_no_stages_enabled")
    stop = asyncio.Event()
    try:
        await Worker(database, handlers).run_forever(stop)
    finally:
        await database.dispose()


if __name__ == "__main__":  # pragma: no cover
    configure_logging(get_settings().log_level)
    asyncio.run(_main(get_settings()))
