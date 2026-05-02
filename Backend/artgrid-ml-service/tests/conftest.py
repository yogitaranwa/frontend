"""
conftest.py
Shared pytest fixtures for artgrid-ml-service tests.
"""
import os

import pytest


def pytest_configure(config):
    """Set required environment variables before any test runs."""
    os.environ.setdefault("JWT_SECRET", "test-secret-for-pytest-only-not-real")
    os.environ.setdefault("DEMO_MODE", "true")
