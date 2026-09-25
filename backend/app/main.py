from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

from fastapi import FastAPI

from app.api.health import router as health_router
from app.api.uploads import router as uploads_router
from app.config import Settings, get_settings
from app.db import Database
from app.errors import ErrorResponse, install_error_handlers
from app.middleware import RequestContextMiddleware
from app.observability import configure_logging
from app.storage import ObjectStore, S3ObjectStore


def object_store_from(settings: Settings) -> ObjectStore | None:
    """The configured store, or None when storage isn't set up (uploads then return 503)."""
    if not (settings.s3_bucket and settings.s3_access_key_id and settings.s3_secret_access_key):
        return None
    return S3ObjectStore(
        endpoint_url=settings.s3_endpoint_url,
        region=settings.s3_region,
        bucket=settings.s3_bucket,
        access_key_id=settings.s3_access_key_id.get_secret_value(),
        secret_access_key=settings.s3_secret_access_key.get_secret_value(),
    )


def create_app(
    settings: Settings | None = None,
    *,
    database: Database | None = None,
    object_store: ObjectStore | None = None,
) -> FastAPI:
    settings = settings or get_settings()
    configure_logging(settings.log_level)

    @asynccontextmanager
    async def lifespan(application: FastAPI) -> AsyncIterator[None]:
        owned = None
        db = database
        if db is None and settings.database_url is not None:
            db = owned = Database(settings.database_url.get_secret_value())
        application.state.database = db
        application.state.object_store = object_store or object_store_from(settings)
        try:
            yield
        finally:
            if owned is not None:
                await owned.dispose()

    application = FastAPI(
        title="AI Daily Memory API",
        version="0.1.0",
        docs_url="/docs" if settings.expose_api_docs else None,
        redoc_url=None,
        openapi_url="/openapi.json" if settings.expose_api_docs else None,
        lifespan=lifespan,
        # Documents the shared error envelope for every route in the OpenAPI contract.
        responses={
            422: {"model": ErrorResponse, "description": "Validation error"},
            500: {"model": ErrorResponse, "description": "Internal error"},
        },
    )
    application.state.settings = settings
    install_error_handlers(application)
    application.add_middleware(
        RequestContextMiddleware,
        max_body_bytes=settings.max_request_body_bytes,
        timeout_seconds=settings.request_timeout_seconds,
    )
    application.include_router(health_router, prefix="/v1")
    application.include_router(uploads_router, prefix="/v1")
    return application


app = create_app()
