from fastapi import FastAPI

from app.api.health import router as health_router

app = FastAPI(
    title="AI Daily Memory API",
    version="0.1.0",
    docs_url="/docs",
    redoc_url=None,
)

app.include_router(health_router, prefix="/v1")
