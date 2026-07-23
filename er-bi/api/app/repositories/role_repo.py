from typing import Optional

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.modules.system.models import SysMenu, SysRole


def get(db: Session, role_id: int) -> Optional[SysRole]:
    return db.get(SysRole, role_id)


def name_exists(db: Session, name: str, exclude_id: Optional[int] = None) -> bool:
    stmt = select(SysRole).where(SysRole.name == name)
    if exclude_id is not None:
        stmt = stmt.where(SysRole.id != exclude_id)
    return db.scalar(stmt) is not None


def list_roles(
    db: Session,
    *,
    name: Optional[str] = None,
    role_id: Optional[str] = None,
    remark: Optional[str] = None,
    status: Optional[int] = None,
    start_time: Optional[str] = None,
    end_time: Optional[str] = None,
) -> list[SysRole]:
    stmt = select(SysRole)
    if name:
        stmt = stmt.where(SysRole.name.like(f"%{name}%"))
    if role_id:
        stmt = stmt.where(SysRole.id == role_id)
    if remark:
        stmt = stmt.where(SysRole.remark.like(f"%{remark}%"))
    if status in (0, 1):
        stmt = stmt.where(SysRole.status == status)
    if start_time:
        stmt = stmt.where(SysRole.create_time >= start_time)
    if end_time:
        stmt = stmt.where(SysRole.create_time <= end_time)
    return list(db.scalars(stmt.order_by(SysRole.id)).all())


def set_permissions(db: Session, role: SysRole, permission_ids: list[int]) -> None:
    if permission_ids:
        role.menus = list(
            db.scalars(select(SysMenu).where(SysMenu.id.in_(permission_ids))).all()
        )
        return
    role.menus = []


def add(db: Session, role: SysRole) -> None:
    db.add(role)


def delete(db: Session, role: SysRole) -> None:
    db.delete(role)
