"""
auth.py
Responsibility : FastAPI dependency that validates ArtGrid JWT and extracts device_id.
Pattern used   : FastAPI Depends() injection.
Dependencies   : PyJWT, settings
"""
import jwt
from fastapi import Depends, Request
from fastapi.security import HTTPAuthorizationCredentials, HTTPBearer

from app.config.settings import Settings, get_settings
from app.core.exceptions import UnauthorizedException
from app.core.logger import get_logger

log = get_logger(__name__)
_bearer = HTTPBearer(auto_error=False)


async def get_device_id(
    request: Request,
    credentials: HTTPAuthorizationCredentials | None = Depends(_bearer),
    settings: Settings = Depends(get_settings),
) -> str:
    """
    Validates the ArtGrid JWT from the Authorization: Bearer header.
    Returns the sub claim (user_id) which is used as the device_id for rate limiting.

    In demo mode the JWT is signed with the shared JWT_SECRET.
    The validation logic is identical in demo and deployed modes —
    the difference is only in how the token was initially issued.
    """
    if credentials is None:
        raise UnauthorizedException("Authorization header with Bearer token required")

    token = credentials.credentials
    try:
        payload = jwt.decode(
            token,
            settings.jwt_secret,
            algorithms=["HS256"],
        )
    except jwt.ExpiredSignatureError:
        raise UnauthorizedException("token_expired")
    except jwt.InvalidTokenError as exc:
        log.warning("auth.invalid_token", reason=str(exc))
        raise UnauthorizedException("invalid_token")

    sub = payload.get("sub")
    if not sub:
        raise UnauthorizedException("token_missing_sub_claim")

    return str(sub)
