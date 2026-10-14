# syntax=docker/dockerfile:1
# Backend service image (backend/ is the Railway service root directory).
# Runs migrations, then serves the API. The processing worker reuses this image
# with a different start command (`python -m app.worker`).

FROM ghcr.io/astral-sh/uv:python3.14-bookworm-slim@sha256:7cf77f594be8042dab6daa9fe326f90962252268b4f120a7f5dccce4d947e6c1 AS build
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

FROM python:3.14-slim-bookworm@sha256:48b13b003dda20b16f9442b8475aa05fe21bf6579a8c881db92ffb4d8fd20f83 AS runtime
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
