"""
rate_limiter.py
Responsibility : In-memory per-device hourly rate limiting for all three inference endpoints.
Pattern used   : Token bucket (fixed window per hour, keyed by device_id + endpoint).
Dependencies   : none (stdlib only)

FL-02 acknowledgement: counters reset on process restart. Acceptable for demo.
For deployed, replace with a Redis TTL counter.
"""
import threading
import time
from collections import defaultdict
from dataclasses import dataclass, field


@dataclass
class _WindowCounter:
    count: int = 0
    window_start: float = field(default_factory=time.time)


class RateLimiter:
    """
    Thread-safe, in-memory rate limiter.
    Window is one hour from the first request in that window.
    After the window expires the counter resets automatically.
    """

    def __init__(self) -> None:
        self._lock = threading.Lock()
        # Key: (device_id, endpoint) → _WindowCounter
        self._counters: dict[tuple[str, str], _WindowCounter] = defaultdict(_WindowCounter)

    def is_allowed(self, device_id: str, endpoint: str, limit: int) -> bool:
        """
        Returns True if the device may make another request to this endpoint.
        Increments the counter on every call — call only once per request.
        """
        key = (device_id, endpoint)
        now = time.time()

        with self._lock:
            counter = self._counters[key]
            if now - counter.window_start >= 3600:
                # Window has elapsed — reset.
                counter.count = 0
                counter.window_start = now

            if counter.count >= limit:
                return False

            counter.count += 1
            return True


# Module-level singleton — shared across all requests in the process.
rate_limiter = RateLimiter()
