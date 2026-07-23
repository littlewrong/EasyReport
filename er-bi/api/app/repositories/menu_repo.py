from typing import Optional

from sqlalchemy import select
from sqlalchemy.orm import Session

from app.modules.system.models import SysMenu


def get(db: Session, menu_id: int) -> Optional[SysMenu]:
    return db.get(SysMenu, menu_id)


def list_all(db: Session) -> list[SysMenu]:
    return list(db.scalars(select(SysMenu)).all())


def list_active(db: Session) -> list[SysMenu]:
    return list(db.scalars(select(SysMenu).where(SysMenu.status == 1)).all())


def list_with_auth_codes(db: Session) -> list[SysMenu]:
    return list(
        db.scalars(
            select(SysMenu).where(SysMenu.auth_code.isnot(None), SysMenu.status == 1)
        ).all()
    )


def name_exists(db: Session, name: str, exclude_id: Optional[int] = None) -> bool:
    stmt = select(SysMenu).where(SysMenu.name == name)
    if exclude_id is not None:
        stmt = stmt.where(SysMenu.id != exclude_id)
    return db.scalar(stmt) is not None


def path_exists(db: Session, path: str, exclude_id: Optional[int] = None) -> bool:
    stmt = select(SysMenu).where(SysMenu.path == path)
    if exclude_id is not None:
        stmt = stmt.where(SysMenu.id != exclude_id)
    return db.scalar(stmt) is not None


def has_children(db: Session, menu_id: int) -> bool:
    return db.scalar(select(SysMenu).where(SysMenu.pid == menu_id)) is not None


def add(db: Session, menu: SysMenu) -> None:
    db.add(menu)


def delete(db: Session, menu: SysMenu) -> None:
    db.delete(menu)
