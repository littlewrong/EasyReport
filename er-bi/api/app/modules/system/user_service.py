from typing import Optional

from fastapi import HTTPException
from sqlalchemy.orm import Session

from app.core.security import hash_password
from app.modules.system.menu_tree import TIME_FMT
from app.modules.system.models import SysUser
from app.modules.system.schemas import UserBody
from app.repositories import user_repo

DEFAULT_PASSWORD = "123456"


def serialize_user(user: SysUser) -> dict:
    return {
        "id": user.id,
        "username": user.username,
        "realName": user.real_name,
        "homePath": user.home_path,
        "status": user.status,
        "remark": user.remark,
        "roles": [role.id for role in user.roles],
        "roleNames": [role.name for role in user.roles],
        "createTime": user.create_time.strftime(TIME_FMT)
        if user.create_time
        else None,
    }


def list_users(
    db: Session,
    *,
    page: int,
    page_size: int,
    username: Optional[str] = None,
    real_name: Optional[str] = None,
    name: Optional[str] = None,
    status: Optional[int] = None,
    start_time: Optional[str] = None,
    end_time: Optional[str] = None,
) -> tuple[list[dict], int]:
    users = user_repo.list_users(
        db,
        username=username,
        real_name=real_name,
        name=name,
        status=status,
        start_time=start_time,
        end_time=end_time,
    )
    total = len(users)
    offset = (page - 1) * page_size
    return [serialize_user(user) for user in users[offset : offset + page_size]], total


def create_user(db: Session, body: UserBody) -> int:
    if user_repo.username_exists(db, body.username):
        raise HTTPException(status_code=400, detail="用户名已存在")
    user = SysUser(
        username=body.username,
        password=hash_password(body.password or DEFAULT_PASSWORD),
        real_name=body.realName,
        home_path=body.homePath,
        status=body.status,
        remark=body.remark,
    )
    user_repo.set_roles(db, user, body.roles)
    user_repo.add(db, user)
    db.commit()
    return user.id


def update_user(db: Session, user_id: int, body: UserBody) -> int:
    user = user_repo.get(db, user_id)
    if not user:
        raise HTTPException(status_code=404, detail="用户不存在")
    if user_repo.username_exists(db, body.username, exclude_id=user_id):
        raise HTTPException(status_code=400, detail="用户名已存在")

    user.username = body.username
    user.real_name = body.realName
    user.home_path = body.homePath
    user.status = body.status
    user.remark = body.remark
    if body.password:
        user.password = hash_password(body.password)
    user_repo.set_roles(db, user, body.roles)
    db.commit()
    return user_id


def delete_user(db: Session, user_id: int, current_user: SysUser) -> int:
    if user_id == current_user.id:
        raise HTTPException(status_code=400, detail="不能删除当前登录用户")
    user = user_repo.get(db, user_id)
    if not user:
        raise HTTPException(status_code=404, detail="用户不存在")
    user_repo.delete(db, user)
    db.commit()
    return user_id
