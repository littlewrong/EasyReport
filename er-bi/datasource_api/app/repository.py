"""Persistence operations for HTTP, SQL and Python datasource metadata."""

from typing import Optional, TypeVar

from sqlalchemy import func, select
from sqlalchemy.orm import Session

from app.models import (
    BiHttpDatasource,
    BiHttpDatasourceVersion,
    BiPythonDatasource,
    BiPythonDatasourceVersion,
    BiSqlDatasource,
    BiSqlDatasourceVersion,
)

T = TypeVar("T")


def add(db: Session, model) -> None:
    db.add(model)


def delete(db: Session, model) -> None:
    db.delete(model)


def get_http(db: Session, datasource_id: int) -> Optional[BiHttpDatasource]:
    return db.get(BiHttpDatasource, datasource_id)


def get_sql(db: Session, datasource_id: int) -> Optional[BiSqlDatasource]:
    return db.get(BiSqlDatasource, datasource_id)


def get_python(db: Session, datasource_id: int) -> Optional[BiPythonDatasource]:
    return db.get(BiPythonDatasource, datasource_id)


def get_python_by_endpoint(
    db: Session, endpoint_key: str
) -> Optional[BiPythonDatasource]:
    return db.scalar(
        select(BiPythonDatasource).where(BiPythonDatasource.endpoint_key == endpoint_key)
    )


def list_http(
    db: Session, name: Optional[str] = None, status: Optional[int] = None
) -> list[BiHttpDatasource]:
    stmt = select(BiHttpDatasource)
    if name:
        stmt = stmt.where(BiHttpDatasource.name.like(f"%{name}%"))
    if status in (0, 1):
        stmt = stmt.where(BiHttpDatasource.status == status)
    return list(db.scalars(stmt.order_by(BiHttpDatasource.id.desc())).all())


def list_sql(
    db: Session,
    name: Optional[str] = None,
    connector_id: Optional[int] = None,
    status: Optional[int] = None,
) -> list[BiSqlDatasource]:
    stmt = select(BiSqlDatasource)
    if name:
        stmt = stmt.where(BiSqlDatasource.name.like(f"%{name}%"))
    if connector_id:
        stmt = stmt.where(BiSqlDatasource.connector_id == connector_id)
    if status in (0, 1):
        stmt = stmt.where(BiSqlDatasource.status == status)
    return list(db.scalars(stmt.order_by(BiSqlDatasource.id.desc())).all())


def list_python(
    db: Session,
    name: Optional[str] = None,
    connector_id: Optional[int] = None,
    state: Optional[int] = None,
    status: Optional[int] = None,
) -> list[BiPythonDatasource]:
    stmt = select(BiPythonDatasource)
    if name:
        stmt = stmt.where(BiPythonDatasource.name.like(f"%{name}%"))
    if connector_id:
        stmt = stmt.where(BiPythonDatasource.connector_id == connector_id)
    if state in (-1, 1):
        stmt = stmt.where(BiPythonDatasource.state == state)
    if status in (0, 1):
        stmt = stmt.where(BiPythonDatasource.status == status)
    return list(db.scalars(stmt.order_by(BiPythonDatasource.id.desc())).all())


def _versions(db: Session, model: type[T], datasource_id: int) -> list[T]:
    return list(
        db.scalars(
            select(model)
            .where(model.datasource_id == datasource_id)
            .order_by(model.version_no.desc())
        ).all()
    )


def list_http_versions(db: Session, datasource_id: int):
    return _versions(db, BiHttpDatasourceVersion, datasource_id)


def list_sql_versions(db: Session, datasource_id: int):
    return _versions(db, BiSqlDatasourceVersion, datasource_id)


def list_python_versions(db: Session, datasource_id: int):
    return _versions(db, BiPythonDatasourceVersion, datasource_id)


def _next_version(db: Session, model, datasource_id: int) -> int:
    latest = db.scalar(
        select(func.max(model.version_no)).where(model.datasource_id == datasource_id)
    )
    return int(latest or 0) + 1


def next_http_version(db: Session, datasource_id: int) -> int:
    return _next_version(db, BiHttpDatasourceVersion, datasource_id)


def next_sql_version(db: Session, datasource_id: int) -> int:
    return _next_version(db, BiSqlDatasourceVersion, datasource_id)


def next_python_version(db: Session, datasource_id: int) -> int:
    return _next_version(db, BiPythonDatasourceVersion, datasource_id)
