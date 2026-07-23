from typing import Optional

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.modules.system.models import SysRole, SysUser


def get(db: Session, user_id: int) -> Optional[SysUser]:
    return db.get(SysUser, user_id)


def get_by_username(db: Session, username: str) -> Optional[SysUser]:
    return db.scalar(select(SysUser).where(SysUser.username == username))


def username_exists(db: Session, username: str, exclude_id: Optional[int] = None) -> bool:
    stmt = select(SysUser).where(SysUser.username == username)
    if exclude_id is not None:
        stmt = stmt.where(SysUser.id != exclude_id)
    return db.scalar(stmt) is not None


def list_users(
    db: Session,
    *,
    username: Optional[str] = None,
    real_name: Optional[str] = None,
    name: Optional[str] = None,
    status: Optional[int] = None,
    start_time: Optional[str] = None,
    end_time: Optional[str] = None,
) -> list[SysUser]:
    stmt = select(SysUser)
    if username:
        stmt = stmt.where(SysUser.username.like(f"%{username}%"))
    if real_name:
        stmt = stmt.where(SysUser.real_name.like(f"%{real_name}%"))
    if name:
        stmt = stmt.where(
            SysUser.username.like(f"%{name}%") | SysUser.real_name.like(f"%{name}%")
        )
    if status in (0, 1):
        stmt = stmt.where(SysUser.status == status)
    if start_time:
        stmt = stmt.where(SysUser.create_time >= start_time)
    if end_time:
        stmt = stmt.where(SysUser.create_time <= end_time)
    return list(db.scalars(stmt.order_by(SysUser.id)).all())


def set_roles(db: Session, user: SysUser, role_ids: list[int]) -> None:
    if role_ids:
        user.roles = list(db.scalars(select(SysRole).where(SysRole.id.in_(role_ids))))
        return
    user.roles = []


def add(db: Session, user: SysUser) -> None:
    db.add(user)


def delete(db: Session, user: SysUser) -> None:
    db.delete(user)
