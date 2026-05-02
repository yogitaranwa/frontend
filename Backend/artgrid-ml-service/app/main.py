"""
main.py
Responsibility : FastAPI application factory — lifespan model loading, exception handlers,
                 router registration.
Pattern used   : FastAPI lifespan context manager for startup/shutdown.
Dependencies   : FastAPI, settings, model_loader, infer router
"""
import socket
from contextlib import asynccontextmanager
from typing import AsyncGenerator

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

from app.api.v1.infer.router import router as infer_router
from app.config.settings import get_settings
from app.core.exceptions import AppException
from app.core.logger import configure_logging, get_logger
from app.core.model_loader import load_all_models

log = get_logger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI) -> AsyncGenerator[None, None]:
    """Load all ML models on startup; clean up on shutdown."""
    settings = get_settings()
    configure_logging(settings.log_level)
    log.info("ml_service.starting", demo_mode=settings.demo_mode)

    load_all_models(settings)
    log.info("ml_service.ready", port=settings.port)

    yield

    log.info("ml_service.stopping")


def create_app() -> FastAPI:
    settings = get_settings()
    configure_logging(settings.log_level)

    app = FastAPI(
        title="ArtGrid ML Service",
        version="0.1.0",
        docs_url="/docs" if settings.demo_mode else None,  # hide Swagger in production
        lifespan=lifespan,
    )

    # ── Exception handlers ────────────────────────────────────────────────────

    @app.exception_handler(AppException)
    async def app_exception_handler(request: Request, exc: AppException) -> JSONResponse:
        return JSONResponse(
            status_code=exc.status_code,
            content={"error": exc.message, "message": exc.message},
        )

    @app.exception_handler(Exception)
    async def unhandled_exception_handler(request: Request, exc: Exception) -> JSONResponse:
        # Never expose internal error details to clients.
        log.error("unhandled_exception", error=str(exc), path=request.url.path)
        return JSONResponse(
            status_code=500,
            content={"error": "internal_server_error", "message": "An internal error occurred"},
        )

    # ── Routes ────────────────────────────────────────────────────────────────
    app.include_router(infer_router)

    return app


app = create_app()
