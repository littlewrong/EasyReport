"""Private service-to-service endpoints."""

import secrets

from fastapi import APIRouter, Depends, Header, HTTPException
from sqlalchemy.orm import Session

from app.api.deps import get_db
from app.core.config import settings
from app.core.response import ok
from app.modules.bi import connector_service
from app.repositories import datasource_repo

router = APIRouter(prefix="/internal", tags=["internal"], include_in_schema=False)


def require_internal_service(
    x_internal_service_token: str = Header(default=""),
) -> None:
    if not secrets.compare_digest(
        x_internal_service_token, settings.INTERNAL_SERVICE_TOKEN
    ):
        raise HTTPException(status_code=403, detail="Invalid internal service token")


@router.get(
    "/bi/db-connectors/options", dependencies=[Depends(require_internal_service)]
)
def connector_options(db: Session = Depends(get_db)):
    return ok(connector_service.list_all_options(db))


@router.get(
    "/bi/db-connectors/{connector_id}/resolve",
    dependencies=[Depends(require_internal_service)],
)
def resolve_connector(connector_id: int, db: Session = Depends(get_db)):
    connector = datasource_repo.get_db_connector(db, connector_id)
    if not connector:
        raise HTTPException(status_code=404, detail="DB连接器不存在")
    return ok(connector_service.execution_profile(connector))
