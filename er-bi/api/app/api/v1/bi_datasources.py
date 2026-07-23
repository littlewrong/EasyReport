"""DB connector APIs owned by the ER-BI control process."""

from typing import Optional

from fastapi import APIRouter, Depends
from sqlalchemy.orm import Session

from app.api.deps import get_current_user, get_db
from app.core.response import ok, page_ok
from app.modules.bi import connector_service
from app.modules.bi.schemas import DbConnectorBody

router = APIRouter(
    prefix="/bi/datasource",
    tags=["bi-db-connector"],
    dependencies=[Depends(get_current_user)],
)


@router.get("/db-connectors/list")
def db_connector_list(
    page: int = 1,
    pageSize: int = 20,
    name: Optional[str] = None,
    dbType: Optional[str] = None,
    status: Optional[int] = None,
    db: Session = Depends(get_db),
):
    items, total = connector_service.list_connectors(
        db,
        page=page,
        page_size=pageSize,
        name=name,
        db_type=dbType,
        status=status,
    )
    return page_ok(items, total)


@router.get("/db-connectors/options")
def db_connector_options(db: Session = Depends(get_db)):
    return ok(connector_service.list_options(db))


@router.post("/db-connectors")
def create_db_connector(body: DbConnectorBody, db: Session = Depends(get_db)):
    return ok(connector_service.create(db, body))


@router.put("/db-connectors/{connector_id}")
def update_db_connector(
    connector_id: int, body: DbConnectorBody, db: Session = Depends(get_db)
):
    return ok(connector_service.update(db, connector_id, body))


@router.delete("/db-connectors/{connector_id}")
def delete_db_connector(connector_id: int, db: Session = Depends(get_db)):
    return ok(connector_service.delete(db, connector_id))


@router.post("/db-connectors/{connector_id}/test")
def test_db_connector(connector_id: int, db: Session = Depends(get_db)):
    return ok(connector_service.test(db, connector_id))
