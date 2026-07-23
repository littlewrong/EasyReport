from typing import Optional

from fastapi import APIRouter, Depends, Request
from sqlalchemy.orm import Session
from starlette.concurrency import run_in_threadpool

from app import service
from app.core.response import ok, page_ok
from app.deps import get_current_user, get_db, require_internal_service
from app.executors.sql import test_connector
from app.schemas import (
    ConnectorProfile,
    HttpDatasourceBody,
    HttpDatasourceTestBody,
    PythonDatasourceBody,
    PythonDatasourcePublish,
    PythonDatasourceTestBody,
    SqlDatasourceBody,
    SqlDatasourceTestBody,
)

router = APIRouter(
    prefix="/bi/datasource",
    tags=["bi-datasource"],
    dependencies=[Depends(get_current_user)],
)
public_router = APIRouter(
    prefix="/bi/datasource/public",
    tags=["bi-datasource-runtime"],
    dependencies=[Depends(get_current_user)],
)
internal_router = APIRouter(
    prefix="/internal",
    tags=["internal"],
    dependencies=[Depends(require_internal_service)],
    include_in_schema=False,
)


async def _params(request: Request) -> dict:
    params = dict(request.query_params)
    if request.method.upper() not in {"POST", "PUT", "PATCH"}:
        return params
    try:
        body = await request.json()
    except Exception:
        return params
    if not isinstance(body, dict):
        return params
    body_params = body.get("params")
    params.update(body_params if isinstance(body_params, dict) else body)
    return params


@internal_router.post("/connectors/test")
def internal_test_connector(body: ConnectorProfile):
    return ok(test_connector(body.model_dump()))


@router.get("/http-sources/list")
def http_list(
    page: int = 1,
    pageSize: int = 20,
    name: Optional[str] = None,
    status: Optional[int] = None,
    db: Session = Depends(get_db),
):
    items, total = service.list_http(
        db, page=page, page_size=pageSize, name=name, status=status
    )
    return page_ok(items, total)


@router.post("/http-sources")
def create_http(body: HttpDatasourceBody, db: Session = Depends(get_db)):
    return ok(service.create_http(db, body))


@router.post("/http-sources/test")
def test_http_config(body: HttpDatasourceTestBody):
    return ok(service.test_http_config(body))


@router.put("/http-sources/{datasource_id}")
def update_http(
    datasource_id: int, body: HttpDatasourceBody, db: Session = Depends(get_db)
):
    return ok(service.update_http(db, datasource_id, body))


@router.delete("/http-sources/{datasource_id}")
def delete_http(datasource_id: int, db: Session = Depends(get_db)):
    return ok(service.delete_http(db, datasource_id))


@router.post("/http-sources/{datasource_id}/test")
async def test_http(datasource_id: int, request: Request, db: Session = Depends(get_db)):
    params = await _params(request)
    return ok(await run_in_threadpool(service.test_http, db, datasource_id, params))


@router.get("/http-sources/{datasource_id}/versions")
def http_versions(datasource_id: int, db: Session = Depends(get_db)):
    return ok(service.http_versions(db, datasource_id))


@router.post("/http-sources/{datasource_id}/versions/{version_id}/restore")
def restore_http(datasource_id: int, version_id: int, db: Session = Depends(get_db)):
    return ok(service.restore_http(db, datasource_id, version_id))


@router.get("/sql-sources/list")
def sql_list(
    page: int = 1,
    pageSize: int = 20,
    name: Optional[str] = None,
    connectorId: Optional[int] = None,
    status: Optional[int] = None,
    db: Session = Depends(get_db),
):
    items, total = service.list_sql(
        db,
        page=page,
        page_size=pageSize,
        name=name,
        connector_id=connectorId,
        status=status,
    )
    return page_ok(items, total)


@router.post("/sql-sources")
def create_sql(body: SqlDatasourceBody, db: Session = Depends(get_db)):
    return ok(service.create_sql(db, body))


@router.post("/sql-sources/test")
def test_sql_config(body: SqlDatasourceTestBody):
    return ok(service.test_sql_config(body))


@router.put("/sql-sources/{datasource_id}")
def update_sql(
    datasource_id: int, body: SqlDatasourceBody, db: Session = Depends(get_db)
):
    return ok(service.update_sql(db, datasource_id, body))


@router.delete("/sql-sources/{datasource_id}")
def delete_sql(datasource_id: int, db: Session = Depends(get_db)):
    return ok(service.delete_sql(db, datasource_id))


@router.post("/sql-sources/{datasource_id}/test")
async def test_sql(datasource_id: int, request: Request, db: Session = Depends(get_db)):
    params = await _params(request)
    return ok(await run_in_threadpool(service.test_sql, db, datasource_id, params))


@router.get("/sql-sources/{datasource_id}/versions")
def sql_versions(datasource_id: int, db: Session = Depends(get_db)):
    return ok(service.sql_versions(db, datasource_id))


@router.post("/sql-sources/{datasource_id}/versions/{version_id}/restore")
def restore_sql(datasource_id: int, version_id: int, db: Session = Depends(get_db)):
    return ok(service.restore_sql(db, datasource_id, version_id))


@router.get("/python-sources/list")
def python_list(
    page: int = 1,
    pageSize: int = 20,
    name: Optional[str] = None,
    connectorId: Optional[int] = None,
    state: Optional[int] = None,
    status: Optional[int] = None,
    db: Session = Depends(get_db),
):
    items, total = service.list_python(
        db,
        page=page,
        page_size=pageSize,
        name=name,
        connector_id=connectorId,
        state=state,
        status=status,
    )
    return page_ok(items, total)


@router.post("/python-sources")
def create_python(body: PythonDatasourceBody, db: Session = Depends(get_db)):
    return ok(service.create_python(db, body))


@router.post("/python-sources/test")
def test_python_config(body: PythonDatasourceTestBody):
    return ok(service.test_python_config(body))


@router.put("/python-sources/{datasource_id}")
def update_python(
    datasource_id: int, body: PythonDatasourceBody, db: Session = Depends(get_db)
):
    return ok(service.update_python(db, datasource_id, body))


@router.delete("/python-sources/{datasource_id}")
def delete_python(datasource_id: int, db: Session = Depends(get_db)):
    return ok(service.delete_python(db, datasource_id))


@router.post("/python-sources/{datasource_id}/test")
async def test_python(
    datasource_id: int, request: Request, db: Session = Depends(get_db)
):
    params = await _params(request)
    return ok(await run_in_threadpool(service.test_python, db, datasource_id, params))


@router.get("/python-sources/{datasource_id}/versions")
def python_versions(datasource_id: int, db: Session = Depends(get_db)):
    return ok(service.python_versions(db, datasource_id))


@router.post("/python-sources/{datasource_id}/versions/{version_id}/restore")
def restore_python(
    datasource_id: int, version_id: int, db: Session = Depends(get_db)
):
    return ok(service.restore_python(db, datasource_id, version_id))


@router.post("/python-sources/publish")
def publish_python(body: PythonDatasourcePublish, db: Session = Depends(get_db)):
    return ok(service.publish_python(db, body.id, body.state))


@public_router.api_route("/http/{datasource_id}", methods=["GET", "POST"])
async def execute_http(
    datasource_id: int, request: Request, db: Session = Depends(get_db)
):
    params = await _params(request)
    return ok(await run_in_threadpool(service.execute_http, db, datasource_id, params))


@public_router.api_route("/sql/{datasource_id}", methods=["GET", "POST"])
async def execute_sql(
    datasource_id: int, request: Request, db: Session = Depends(get_db)
):
    params = await _params(request)
    return ok(await run_in_threadpool(service.execute_sql, db, datasource_id, params))


@public_router.api_route("/python/{endpoint_key}", methods=["GET", "POST"])
async def execute_python(
    endpoint_key: str, request: Request, db: Session = Depends(get_db)
):
    params = await _params(request)
    return ok(await run_in_threadpool(service.execute_python, db, endpoint_key, params))
