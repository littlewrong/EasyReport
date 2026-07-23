import secrets

import jwt
from fastapi import Depends, Header, HTTPException, Request
from sqlalchemy.orm import Session

from app.core.config import settings
from app.db.session import SessionLocal
from app.models import SysUser


def get_db():
    db = SessionLocal()
    try:
        yield db
    finally:
        db.close()


def get_current_user(request: Request, db: Session = Depends(get_db)) -> SysUser:
    auth = request.headers.get("Authorization", "")
    if not auth.startswith("Bearer "):
        raise HTTPException(status_code=401, detail="Unauthorized Exception")
    try:
        payload = jwt.decode(
            auth[7:], settings.JWT_SECRET, algorithms=[settings.JWT_ALGORITHM]
        )
    except jwt.PyJWTError as exc:
        raise HTTPException(status_code=401, detail="Unauthorized Exception") from exc
    if payload.get("type") != "access":
        raise HTTPException(status_code=401, detail="Unauthorized Exception")
    user = db.get(SysUser, int(payload["sub"]))
    if not user or user.status != 1:
        raise HTTPException(status_code=401, detail="Unauthorized Exception")
    return user


def require_internal_service(
    x_internal_service_token: str = Header(default=""),
) -> None:
    if not secrets.compare_digest(
        x_internal_service_token, settings.INTERNAL_SERVICE_TOKEN
    ):
        raise HTTPException(status_code=403, detail="Invalid internal service token")
