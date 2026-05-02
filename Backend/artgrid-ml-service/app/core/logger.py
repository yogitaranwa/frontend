"""
logger.py
Responsibility : Configures structlog for JSON output across the ML service.
Pattern used   : Singleton logger configured once at import time.
Dependencies   : structlog
"""
import logging
import sys
import structlog


def configure_logging(log_level: str = "info") -> None:
    """Call once at application startup to configure structlog JSON output."""
    level = getattr(logging, log_level.upper(), logging.INFO)
    logging.basicConfig(
        format="%(message)s",
        stream=sys.stdout,
        level=level,
    )
    structlog.configure(
        processors=[
            structlog.contextvars.merge_contextvars,
            structlog.stdlib.add_log_level,
            structlog.stdlib.add_logger_name,
            structlog.processors.TimeStamper(fmt="iso"),
            structlog.processors.JSONRenderer(),
        ],
        wrapper_class=structlog.BoundLogger,
        context_class=dict,
        logger_factory=structlog.PrintLoggerFactory(),
    )


def get_logger(name: str) -> structlog.BoundLogger:
    """Return a bound logger pre-tagged with the service name and module."""
    return structlog.get_logger(name).bind(service="artgrid-ml")
