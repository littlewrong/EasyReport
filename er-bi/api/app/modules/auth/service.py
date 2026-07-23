from typing import Optional

from fastapi import HTTPException
from sqlalchemy.orm import Session

from app.core.security import decode_token, verify_password
from app.modules.system.models import SysMenu, SysUser
from app.modules.system.permissions import is_super
from app.repositories import menu_repo, user_repo


def authenticate_user(db: Session, username: str, password: str) -> SysUser:
    user = user_repo.get_by_username(db, username)
    if not user or not verify_password(password, user.password):
        raise HTTPException(status_code=403, detail="用户名或密码错误")
    if user.status != 1:
        raise HTTPException(status_code=403, detail="用户已被禁用")
    return user


def resolve_refresh_user(db: Session, refresh_token: Optional[str]) -> SysUser:
    if not refresh_token:
        raise HTTPException(status_code=403, detail="Forbidden Exception")
    payload = decode_token(refresh_token, "refresh")
    if not payload:
        raise HTTPException(status_code=403, detail="Forbidden Exception")
    user = user_repo.get(db, int(payload["sub"]))
    if not user or user.status != 1:
        raise HTTPException(status_code=403, detail="Forbidden Exception")
    return user


def user_payload(user: SysUser) -> dict:
    return {
        "id": user.id,
        "username": user.username,
        "realName": user.real_name,
        "roles": [role.name for role in user.roles if role.status == 1],
        "homePath": user.home_path,
    }


def get_access_codes(db: Session, user: SysUser) -> list[str]:
    if is_super(user):
        menus = menu_repo.list_with_auth_codes(db)
        codes = {menu.auth_code for menu in menus}
    else:
        codes = {
            menu.auth_code
            for role in user.roles
            if role.status == 1
            for menu in role.menus
            if menu.auth_code and menu.status == 1
        }
    return sorted(code for code in codes if code)
