from fastapi import FastAPI

from app.api.health import router as health_router
from app.config import Settings, get_settings
from app.errors import ErrorResponse, install_error_handlers
from app.middleware import RequestContextMiddleware
from app.observability import configure_logging


def create_app(settings: Settings | None = None) -> FastAPI:
    settings = settings or get_settings()
    configure_logging(settings.log_level)
    application = FastAPI(
        title="AI Daily Memory API",
        version="0.1.0",
        docs_url="/docs" if settings.expose_api_docs else None,
        redoc_url=None,
        openapi_url="/openapi.json" if settings.expose_api_docs else None,
        # Documents the shared error envelope for every route in the OpenAPI contract.
        responses={
            422: {"model": ErrorResponse, "description": "Validation error"},
            500: {"model": ErrorResponse, "description": "Internal error"},
        },
    )
    install_error_handlers(application)
    application.add_middleware(
        RequestContextMiddleware,
        max_body_bytes=settings.max_request_body_bytes,
    )
    application.include_router(health_router, prefix="/v1")
    return application


app = create_app()
