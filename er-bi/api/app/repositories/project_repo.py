from typing import Optional

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.modules.bi.models import BiProject


def get(db: Session, project_id: int) -> Optional[BiProject]:
    return db.get(BiProject, project_id)


def list_paged(
    db: Session, user_id: int, page: int, limit: int
) -> tuple[list[BiProject], int]:
    base = select(BiProject).where(BiProject.create_user_id == user_id)
    total = db.scalar(
        select(func.count()).select_from(base.subquery())
    )
    rows = db.scalars(
        base.order_by(BiProject.update_time.desc())
        .offset((page - 1) * limit)
        .limit(limit)
    ).all()
    return list(rows), int(total or 0)


def add(db: Session, project: BiProject) -> None:
    db.add(project)


def delete(db: Session, project: BiProject) -> None:
    db.delete(project)
