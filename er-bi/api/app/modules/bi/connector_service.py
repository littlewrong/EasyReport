"""DB connector control-plane service.

Connection metadata remains owned by the main API. Actual connectivity tests are
delegated to the isolated datasource process.
"""

from typing import Optional

from fastapi import HTTPException
from sqlalchemy.orm import Session

from app.integrations import datasource_client
from app.core import connector_secrets
from app.modules.bi.models import BiDbConnector
from app.modules.bi.schemas import DbConnectorBody
from app.repositories import datasource_repo

DB_TYPES = {"MySQL", "TiDB", "StarRocks", "PostgreSQL", "SQLServer"}


def _validate_db_type(db_type: str) -> None:
    if db_type not in DB_TYPES:
        raise HTTPException(status_code=400, detail="不支持的数据库类型")


def _get_or_404(db: Session, connector_id: int) -> BiDbConnector:
    connector = datasource_repo.get_db_connector(db, connector_id)
    if not connector:
        raise HTTPException(status_code=404, detail="DB连接器不存在")
    return connector


def _item(connector: BiDbConnector) -> dict:
    return {
        "id": connector.id,
        "name": connector.name,
        "dbType": connector.db_type,
        "host": connector.host,
        "port": connector.port,
        "database": connector.database,
        "username": connector.username,
        # Never send the stored secret back to a browser.
        "password": None,
        "hasPassword": bool(connector.password),
        "extra": connector.extra,
        "status": connector.status,
        "remark": connector.remark,
        "createTime": connector.create_time.strftime("%Y/%m/%d %H:%M:%S")
        if connector.create_time
        else None,
        "updateTime": connector.update_time.strftime("%Y/%m/%d %H:%M:%S")
        if connector.update_time
        else None,
    }


def execution_profile(connector: BiDbConnector) -> dict:
    """Return a secret-bearing profile for authenticated service-to-service use."""
    return {
        "id": connector.id,
        "name": connector.name,
        "dbType": connector.db_type,
        "host": connector.host,
        "port": connector.port,
        "database": connector.database,
        "username": connector.username,
        "password": connector_secrets.decrypt(connector.password),
        "extra": connector.extra,
        "status": connector.status,
    }


def list_connectors(
    db: Session,
    *,
    page: int,
    page_size: int,
    name: Optional[str] = None,
    db_type: Optional[str] = None,
    status: Optional[int] = None,
) -> tuple[list[dict], int]:
    rows = datasource_repo.list_db_connectors(
        db, name=name, db_type=db_type, status=status
    )
    total = len(rows)
    start = max(page - 1, 0) * page_size
    return [_item(row) for row in rows[start : start + page_size]], total


def list_options(db: Session) -> list[dict]:
    return [
        {
            "id": item.id,
            "name": item.name,
            "dbType": item.db_type,
            "host": item.host,
            "database": item.database,
        }
        for item in datasource_repo.list_db_connectors(db, status=1)
    ]


def list_all_options(db: Session) -> list[dict]:
    return [
        {
            "id": item.id,
            "name": item.name,
            "dbType": item.db_type,
            "host": item.host,
            "database": item.database,
            "status": item.status,
        }
        for item in datasource_repo.list_db_connectors(db)
    ]


def create(db: Session, body: DbConnectorBody) -> int:
    _validate_db_type(body.dbType)
    connector = BiDbConnector(
        name=body.name,
        db_type=body.dbType,
        host=body.host,
        port=body.port,
        database=body.database,
        username=body.username,
        password=connector_secrets.encrypt(body.password),
        extra=body.extra,
        status=body.status,
        remark=body.remark,
    )
    datasource_repo.add(db, connector)
    db.commit()
    return connector.id


def update(db: Session, connector_id: int, body: DbConnectorBody) -> int:
    _validate_db_type(body.dbType)
    connector = _get_or_404(db, connector_id)
    connector.name = body.name
    connector.db_type = body.dbType
    connector.host = body.host
    connector.port = body.port
    connector.database = body.database
    connector.username = body.username
    # An empty edit value means "keep the existing secret".
    if body.password:
        connector.password = connector_secrets.encrypt(body.password)
    connector.extra = body.extra
    connector.status = body.status
    connector.remark = body.remark
    db.commit()
    return connector.id


def delete(db: Session, connector_id: int) -> bool:
    connector = _get_or_404(db, connector_id)
    if datasource_repo.connector_reference_count(db, connector_id) > 0:
        raise HTTPException(status_code=400, detail="该DB连接器已被数据源使用，无法删除")
    datasource_repo.delete(db, connector)
    db.commit()
    return True


def test(db: Session, connector_id: int) -> dict:
    connector = _get_or_404(db, connector_id)
    return datasource_client.test_connector(execution_profile(connector))
