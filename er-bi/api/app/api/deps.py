from fastapi import Depends, HTTPException, Request
from sqlalchemy.orm import Session

from app.core.security import decode_token
from app.db.session import SessionLocal
from app.modules.system.models import SysUser
from app.modules.system.permissions import is_super


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
    payload = decode_token(auth[7:], "access")
    if not payload:
        raise HTTPException(status_code=401, detail="Unauthorized Exception")
    user = db.get(SysUser, int(payload["sub"]))
    if not user or user.status != 1:
        raise HTTPException(status_code=401, detail="Unauthorized Exception")
    return user


__all__ = ["get_current_user", "get_db", "is_super"]
