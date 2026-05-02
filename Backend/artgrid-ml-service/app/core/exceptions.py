"""
exceptions.py
Responsibility : Typed application exception hierarchy — all errors flow through these.
Pattern used   : Exception hierarchy with HTTP status codes.
Dependencies   : none
"""


class AppException(Exception):
    def __init__(self, status_code: int, message: str) -> None:
        self.status_code = status_code
        self.message = message
        super().__init__(message)


class ValidationException(AppException):
    def __init__(self, message: str) -> None:
        super().__init__(400, message)


class UnauthorizedException(AppException):
    def __init__(self, message: str = "unauthorized") -> None:
        super().__init__(401, message)


class PayloadTooLargeException(AppException):
    def __init__(self, message: str) -> None:
        super().__init__(413, message)


class RateLimitException(AppException):
    def __init__(self, message: str) -> None:
        super().__init__(429, message)


class InferenceException(AppException):
    def __init__(self, message: str) -> None:
        super().__init__(500, message)


class ServiceUnavailableException(AppException):
    def __init__(self, message: str) -> None:
        super().__init__(503, message)
