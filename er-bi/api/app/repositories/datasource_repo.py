"""DB connector persistence owned by the ER-BI control process."""

from typing import Optional

from sqlalchemy import select, text
from sqlalchemy.orm import Session

from app.modules.bi.models import BiDbConnector


def get_db_connector(db: Session, connector_id: int) -> Optional[BiDbConnector]:
    return db.get(BiDbConnector, connector_id)


def list_db_connectors(
    db: Session,
    *,
    name: Optional[str] = None,
    db_type: Optional[str] = None,
    status: Optional[int] = None,
) -> list[BiDbConnector]:
    stmt = select(BiDbConnector)
    if name:
        stmt = stmt.where(BiDbConnector.name.like(f"%{name}%"))
    if db_type:
        stmt = stmt.where(BiDbConnector.db_type == db_type)
    if status in (0, 1):
        stmt = stmt.where(BiDbConnector.status == status)
    return list(db.scalars(stmt.order_by(BiDbConnector.id.desc())).all())


def connector_reference_count(db: Session, connector_id: int) -> int:
    # Datasource tables are owned by the isolated service, but the shared DB
    # constraint must still be checked before a connector is deleted.
    sql_count = db.execute(
        text("SELECT COUNT(*) FROM bi_sql_datasource WHERE connector_id = :id"),
        {"id": connector_id},
    ).scalar_one()
    python_count = db.execute(
        text("SELECT COUNT(*) FROM bi_python_datasource WHERE connector_id = :id"),
        {"id": connector_id},
    ).scalar_one()
    return int(sql_count or 0) + int(python_count or 0)


def add(db: Session, model) -> None:
    db.add(model)


def delete(db: Session, model) -> None:
    db.delete(model)
