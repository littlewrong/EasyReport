from fastapi import APIRouter, Depends, HTTPException, Request, Response
from sqlalchemy.orm import Session

from app.api.deps import get_current_user, get_db
from app.core.config import settings
from app.core.response import ok
from app.core.security import create_access_token, create_refresh_token
from app.modules.auth import service as auth_service
from app.modules.auth.schemas import LoginParams
from app.modules.system.models import SysUser

router = APIRouter(prefix="/auth", tags=["auth"])


def _set_refresh_cookie(response: Response, token: str) -> None:
    response.set_cookie(
        key=settings.REFRESH_COOKIE_NAME,
        value=token,
        max_age=settings.REFRESH_TOKEN_EXPIRE_DAYS * 24 * 3600,
        httponly=True,
        samesite="lax",
    )


def _clear_refresh_cookie(response: Response) -> None:
    response.delete_cookie(key=settings.REFRESH_COOKIE_NAME, httponly=True, samesite="lax")


@router.post("/login")
def login(params: LoginParams, response: Response, db: Session = Depends(get_db)):
    try:
        user = auth_service.authenticate_user(db, params.username, params.password)
    except HTTPException:
        _clear_refresh_cookie(response)
        raise

    access_token = create_access_token(user.id, user.username)
    refresh_token = create_refresh_token(user.id, user.username)
    _set_refresh_cookie(response, refresh_token)
    return ok({**auth_service.user_payload(user), "accessToken": access_token})


@router.post("/refresh")
def refresh(request: Request, response: Response, db: Session = Depends(get_db)):
    try:
        user = auth_service.resolve_refresh_user(
            db, request.cookies.get(settings.REFRESH_COOKIE_NAME)
        )
    except HTTPException:
        _clear_refresh_cookie(response)
        raise

    access_token = create_access_token(user.id, user.username)
    _set_refresh_cookie(response, create_refresh_token(user.id, user.username))
    return access_token


@router.post("/logout")
def logout(response: Response):
    _clear_refresh_cookie(response)
    return ok("")


@router.get("/codes")
def get_access_codes(
    user: SysUser = Depends(get_current_user), db: Session = Depends(get_db)
):
    return ok(auth_service.get_access_codes(db, user))
