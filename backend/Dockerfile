# syntax=docker/dockerfile:1
# Backend service image (backend/ is the Railway service root directory).
# Runs migrations, then serves the API. The processing worker reuses this image
# with a different start command (`python -m app.worker`).

FROM ghcr.io/astral-sh/uv:python3.13-bookworm-slim@sha256:531f855bda2c73cd6ef67d56b733b357cea384185b3022bd09f05e002cd144ca AS build
ENV UV_COMPILE_BYTECODE=1 \
    UV_LINK_MODE=copy
WORKDIR /app
# Dependency layer first so source edits do not invalidate it.
# (No BuildKit cache mounts: Railway's builder rejects ids without its cacheKey prefix.)
COPY pyproject.toml uv.lock ./
RUN uv sync --locked --no-dev --no-install-project --no-editable
COPY alembic.ini ./
COPY app ./app
COPY migrations ./migrations
RUN uv sync --locked --no-dev --no-editable

FROM python:3.13-slim-bookworm@sha256:a1165e272e578941b84abc79e4ab38a0305cd12803a5c4247979ac7655f4d641 AS runtime
ENV PATH="/app/.venv/bin:$PATH" \
    PYTHONUNBUFFERED=1 \
    PYTHONDONTWRITEBYTECODE=1
RUN groupadd --system app && useradd --system --gid app --home /app app
WORKDIR /app
COPY --from=build --chown=app:app /app /app
USER app
EXPOSE 8000
# Managed platforms inject PORT; 8000 is the local default. Migrations fail the
# container rather than serving a schema older than the code expects.
CMD ["sh", "-c", "alembic upgrade head && exec uvicorn app.main:app --host 0.0.0.0 --port ${PORT:-8000}"]
