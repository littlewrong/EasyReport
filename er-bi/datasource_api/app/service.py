"""HTTP, SQL and Python datasource management and execution orchestration."""

import uuid
from typing import Any, Optional

from fastapi import HTTPException
from sqlalchemy.orm import Session

from app import connector_client, repository
from app.executors import http as http_executor
from app.executors import python as python_executor
from app.executors import sql as sql_executor
from app.models import (
    BiHttpDatasource,
    BiHttpDatasourceVersion,
    BiPythonDatasource,
    BiPythonDatasourceVersion,
    BiSqlDatasource,
    BiSqlDatasourceVersion,
)
from app.schemas import (
    HttpDatasourceBody,
    HttpDatasourceTestBody,
    PythonDatasourceBody,
    PythonDatasourceTestBody,
    SqlDatasourceBody,
    SqlDatasourceTestBody,
)

TIME_FMT = "%Y/%m/%d %H:%M:%S"


def _time(value) -> Optional[str]:
    return value.strftime(TIME_FMT) if value else None


def _page(rows: list, page: int, page_size: int) -> tuple[list, int]:
    start = max(page - 1, 0) * page_size
    return rows[start : start + page_size], len(rows)


def _http_or_404(db: Session, datasource_id: int) -> BiHttpDatasource:
    item = repository.get_http(db, datasource_id)
    if not item:
        raise HTTPException(status_code=404, detail="HTTP数据源不存在")
    return item


def _sql_or_404(db: Session, datasource_id: int) -> BiSqlDatasource:
    item = repository.get_sql(db, datasource_id)
    if not item:
        raise HTTPException(status_code=404, detail="SQL数据源不存在")
    return item


def _python_or_404(db: Session, datasource_id: int) -> BiPythonDatasource:
    item = repository.get_python(db, datasource_id)
    if not item:
        raise HTTPException(status_code=404, detail="Python数据源不存在")
    return item


def _connector_map() -> dict[int, dict]:
    return {item["id"]: item for item in connector_client.options()}


def _http_path(item: BiHttpDatasource) -> Optional[str]:
    return f"/dsapi/http/bi/datasource/public/http/{item.id}" if item.status == 1 else None


def _sql_path(item: BiSqlDatasource) -> Optional[str]:
    return f"/dsapi/sql/bi/datasource/public/sql/{item.id}" if item.status == 1 else None


def _python_path(item: BiPythonDatasource) -> Optional[str]:
    if item.status != 1 or not item.endpoint_key:
        return None
    return f"/dsapi/python/bi/datasource/public/python/{item.endpoint_key}"


def _http_item(item: BiHttpDatasource) -> dict:
    return {
        "id": item.id,
        "name": item.name,
        "requestMethod": item.request_method,
        "requestUrl": item.request_url,
        "requestHeaders": item.request_headers,
        "requestBody": item.request_body,
        "publicPath": _http_path(item),
        "status": item.status,
        "remark": item.remark,
        "createTime": _time(item.create_time),
        "updateTime": _time(item.update_time),
    }


def _sql_item(item: BiSqlDatasource, connectors: dict[int, dict]) -> dict:
    connector = connectors.get(item.connector_id)
    return {
        "id": item.id,
        "name": item.name,
        "connectorId": item.connector_id,
        "connectorName": connector.get("name") if connector else None,
        "sqlContent": item.sql_content,
        "publicPath": _sql_path(item),
        "status": item.status,
        "remark": item.remark,
        "createTime": _time(item.create_time),
        "updateTime": _time(item.update_time),
    }


def _python_item(item: BiPythonDatasource, connectors: dict[int, dict]) -> dict:
    connector = connectors.get(item.connector_id)
    return {
        "id": item.id,
        "name": item.name,
        "connectorId": item.connector_id,
        "connectorName": connector.get("name") if connector else None,
        "pythonCode": item.python_code,
        "state": item.state,
        "endpointKey": item.endpoint_key,
        "publicPath": _python_path(item),
        "status": item.status,
        "remark": item.remark,
        "createTime": _time(item.create_time),
        "updateTime": _time(item.update_time),
    }


def list_http(
    db: Session,
    *,
    page: int,
    page_size: int,
    name: Optional[str],
    status: Optional[int],
) -> tuple[list[dict], int]:
    rows, total = _page(repository.list_http(db, name, status), page, page_size)
    return [_http_item(row) for row in rows], total


def create_http(db: Session, body: HttpDatasourceBody) -> int:
    item = BiHttpDatasource(
        name=body.name,
        connector_id=None,
        request_method=body.requestMethod,
        request_url=body.requestUrl,
        request_headers=body.requestHeaders,
        request_body=body.requestBody,
        status=body.status,
        remark=body.remark,
    )
    repository.add(db, item)
    db.flush()
    _snapshot_http(db, item)
    db.commit()
    return item.id


def update_http(db: Session, datasource_id: int, body: HttpDatasourceBody) -> int:
    item = _http_or_404(db, datasource_id)
    item.name = body.name
    item.request_method = body.requestMethod
    item.request_url = body.requestUrl
    item.request_headers = body.requestHeaders
    item.request_body = body.requestBody
    item.status = body.status
    item.remark = body.remark
    _snapshot_http(db, item)
    db.commit()
    return item.id


def delete_http(db: Session, datasource_id: int) -> bool:
    repository.delete(db, _http_or_404(db, datasource_id))
    db.commit()
    return True


def _snapshot_http(db: Session, item: BiHttpDatasource) -> None:
    repository.add(
        db,
        BiHttpDatasourceVersion(
            datasource_id=item.id,
            version_no=repository.next_http_version(db, item.id),
            snapshot={
                "name": item.name,
                "requestMethod": item.request_method,
                "requestUrl": item.request_url,
                "requestHeaders": item.request_headers,
                "requestBody": item.request_body,
                "status": item.status,
                "remark": item.remark,
            },
        ),
    )


def http_versions(db: Session, datasource_id: int) -> list[dict]:
    _http_or_404(db, datasource_id)
    return [
        {
            "id": row.id,
            "datasourceId": row.datasource_id,
            "versionNo": row.version_no,
            "snapshot": row.snapshot or {},
            "name": (row.snapshot or {}).get("name"),
            "requestMethod": (row.snapshot or {}).get("requestMethod"),
            "requestUrl": (row.snapshot or {}).get("requestUrl"),
            "createTime": _time(row.create_time),
        }
        for row in repository.list_http_versions(db, datasource_id)
    ]


def restore_http(db: Session, datasource_id: int, version_id: int) -> bool:
    item = _http_or_404(db, datasource_id)
    version = db.get(BiHttpDatasourceVersion, version_id)
    if not version or version.datasource_id != datasource_id:
        raise HTTPException(status_code=404, detail="HTTP数据源版本不存在")
    data = version.snapshot or {}
    item.name = data.get("name") or item.name
    item.request_method = data.get("requestMethod") or "GET"
    item.request_url = data.get("requestUrl") or item.request_url
    item.request_headers = data.get("requestHeaders")
    item.request_body = data.get("requestBody")
    item.status = data.get("status", item.status)
    item.remark = data.get("remark")
    _snapshot_http(db, item)
    db.commit()
    return True


def test_http_config(body: HttpDatasourceTestBody) -> dict:
    return http_executor.execute(
        request_method=body.requestMethod,
        request_url=body.requestUrl,
        request_headers=body.requestHeaders,
        request_body=body.requestBody,
        params=body.params,
        preview=True,
    )


def test_http(db: Session, datasource_id: int, params: dict) -> dict:
    item = _http_or_404(db, datasource_id)
    return http_executor.execute(
        request_method=item.request_method,
        request_url=item.request_url,
        request_headers=item.request_headers,
        request_body=item.request_body,
        params=params,
        preview=True,
    )


def execute_http(db: Session, datasource_id: int, params: dict) -> Any:
    item = _http_or_404(db, datasource_id)
    if item.status != 1:
        raise HTTPException(status_code=400, detail="HTTP数据源已禁用")
    return http_executor.execute(
        request_method=item.request_method,
        request_url=item.request_url,
        request_headers=item.request_headers,
        request_body=item.request_body,
        params=params,
    )


def list_sql(
    db: Session,
    *,
    page: int,
    page_size: int,
    name: Optional[str],
    connector_id: Optional[int],
    status: Optional[int],
) -> tuple[list[dict], int]:
    rows, total = _page(
        repository.list_sql(db, name, connector_id, status), page, page_size
    )
    connectors = _connector_map()
    return [_sql_item(row, connectors) for row in rows], total


def create_sql(db: Session, body: SqlDatasourceBody) -> int:
    connector_client.ensure_exists(body.connectorId)
    item = BiSqlDatasource(
        name=body.name,
        connector_id=body.connectorId,
        sql_content=body.sqlContent,
        status=body.status,
        remark=body.remark,
    )
    repository.add(db, item)
    db.flush()
    _snapshot_sql(db, item)
    db.commit()
    return item.id


def update_sql(db: Session, datasource_id: int, body: SqlDatasourceBody) -> int:
    connector_client.ensure_exists(body.connectorId)
    item = _sql_or_404(db, datasource_id)
    item.name = body.name
    item.connector_id = body.connectorId
    item.sql_content = body.sqlContent
    item.status = body.status
    item.remark = body.remark
    _snapshot_sql(db, item)
    db.commit()
    return item.id


def delete_sql(db: Session, datasource_id: int) -> bool:
    repository.delete(db, _sql_or_404(db, datasource_id))
    db.commit()
    return True


def _snapshot_sql(db: Session, item: BiSqlDatasource) -> None:
    repository.add(
        db,
        BiSqlDatasourceVersion(
            datasource_id=item.id,
            version_no=repository.next_sql_version(db, item.id),
            snapshot={
                "name": item.name,
                "connectorId": item.connector_id,
                "sqlContent": item.sql_content,
                "status": item.status,
                "remark": item.remark,
            },
        ),
    )


def sql_versions(db: Session, datasource_id: int) -> list[dict]:
    _sql_or_404(db, datasource_id)
    connectors = _connector_map()
    result = []
    for row in repository.list_sql_versions(db, datasource_id):
        data = row.snapshot or {}
        connector = connectors.get(data.get("connectorId"))
        result.append(
            {
                "id": row.id,
                "datasourceId": row.datasource_id,
                "versionNo": row.version_no,
                "snapshot": data,
                "name": data.get("name"),
                "connectorName": connector.get("name") if connector else None,
                "sqlContent": data.get("sqlContent"),
                "createTime": _time(row.create_time),
            }
        )
    return result


def restore_sql(db: Session, datasource_id: int, version_id: int) -> bool:
    item = _sql_or_404(db, datasource_id)
    version = db.get(BiSqlDatasourceVersion, version_id)
    if not version or version.datasource_id != datasource_id:
        raise HTTPException(status_code=404, detail="SQL数据源版本不存在")
    data = version.snapshot or {}
    connector_id = data.get("connectorId")
    if not connector_id:
        raise HTTPException(status_code=400, detail="版本缺少DB连接器")
    connector_client.ensure_exists(connector_id)
    item.name = data.get("name") or item.name
    item.connector_id = connector_id
    item.sql_content = data.get("sqlContent") or item.sql_content
    item.status = data.get("status", item.status)
    item.remark = data.get("remark")
    _snapshot_sql(db, item)
    db.commit()
    return True


def _run_sql(connector_id: int, sql: str, params: Optional[dict]) -> Any:
    connector = connector_client.resolve(connector_id)
    if connector.get("status") != 1:
        raise HTTPException(status_code=400, detail="DB连接器已禁用")
    try:
        return sql_executor.query(connector, sql, params)
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(status_code=400, detail=f"SQL执行失败：{exc}") from exc


def test_sql_config(body: SqlDatasourceTestBody) -> dict:
    data = _run_sql(body.connectorId, body.sqlContent, body.params)
    return {
        "success": True,
        "message": "SQL执行成功",
        "rowCount": len(data) if isinstance(data, list) else None,
        "preview": data[:20] if isinstance(data, list) else data,
    }


def test_sql(db: Session, datasource_id: int, params: dict) -> dict:
    item = _sql_or_404(db, datasource_id)
    data = _run_sql(item.connector_id, item.sql_content, params)
    return {
        "success": True,
        "message": "SQL执行成功",
        "rowCount": len(data) if isinstance(data, list) else None,
        "preview": data[:20] if isinstance(data, list) else data,
    }


def execute_sql(db: Session, datasource_id: int, params: dict) -> Any:
    item = _sql_or_404(db, datasource_id)
    if item.status != 1:
        raise HTTPException(status_code=400, detail="SQL数据源已禁用")
    return _run_sql(item.connector_id, item.sql_content, params)


def list_python(
    db: Session,
    *,
    page: int,
    page_size: int,
    name: Optional[str],
    connector_id: Optional[int],
    state: Optional[int],
    status: Optional[int],
) -> tuple[list[dict], int]:
    rows, total = _page(
        repository.list_python(db, name, connector_id, state, status), page, page_size
    )
    connectors = _connector_map()
    return [_python_item(row, connectors) for row in rows], total


def create_python(db: Session, body: PythonDatasourceBody) -> int:
    connector_client.ensure_exists(body.connectorId)
    item = BiPythonDatasource(
        name=body.name,
        connector_id=body.connectorId,
        python_code=body.pythonCode,
        state=1,
        endpoint_key=uuid.uuid4().hex,
        status=body.status,
        remark=body.remark,
    )
    repository.add(db, item)
    db.flush()
    _snapshot_python(db, item)
    db.commit()
    return item.id


def update_python(
    db: Session, datasource_id: int, body: PythonDatasourceBody
) -> int:
    connector_client.ensure_exists(body.connectorId)
    item = _python_or_404(db, datasource_id)
    item.name = body.name
    item.connector_id = body.connectorId
    item.python_code = body.pythonCode
    item.state = 1
    item.endpoint_key = item.endpoint_key or uuid.uuid4().hex
    item.status = body.status
    item.remark = body.remark
    _snapshot_python(db, item)
    db.commit()
    return item.id


def delete_python(db: Session, datasource_id: int) -> bool:
    repository.delete(db, _python_or_404(db, datasource_id))
    db.commit()
    return True


def _snapshot_python(db: Session, item: BiPythonDatasource) -> None:
    repository.add(
        db,
        BiPythonDatasourceVersion(
            datasource_id=item.id,
            version_no=repository.next_python_version(db, item.id),
            snapshot={
                "name": item.name,
                "connectorId": item.connector_id,
                "pythonCode": item.python_code,
                "status": item.status,
                "remark": item.remark,
            },
        ),
    )


def python_versions(db: Session, datasource_id: int) -> list[dict]:
    _python_or_404(db, datasource_id)
    connectors = _connector_map()
    result = []
    for row in repository.list_python_versions(db, datasource_id):
        data = row.snapshot or {}
        connector = connectors.get(data.get("connectorId"))
        result.append(
            {
                "id": row.id,
                "datasourceId": row.datasource_id,
                "versionNo": row.version_no,
                "snapshot": data,
                "name": data.get("name"),
                "connectorName": connector.get("name") if connector else None,
                "pythonCode": data.get("pythonCode"),
                "createTime": _time(row.create_time),
            }
        )
    return result


def restore_python(db: Session, datasource_id: int, version_id: int) -> bool:
    item = _python_or_404(db, datasource_id)
    version = db.get(BiPythonDatasourceVersion, version_id)
    if not version or version.datasource_id != datasource_id:
        raise HTTPException(status_code=404, detail="Python数据源版本不存在")
    data = version.snapshot or {}
    connector_id = data.get("connectorId")
    if not connector_id:
        raise HTTPException(status_code=400, detail="版本缺少DB连接器")
    connector_client.ensure_exists(connector_id)
    item.name = data.get("name") or item.name
    item.connector_id = connector_id
    item.python_code = data.get("pythonCode") or item.python_code
    item.state = 1
    item.endpoint_key = item.endpoint_key or uuid.uuid4().hex
    item.status = data.get("status", item.status)
    item.remark = data.get("remark")
    _snapshot_python(db, item)
    db.commit()
    return True


def publish_python(db: Session, datasource_id: int, state: int) -> dict:
    if state not in (-1, 1):
        raise HTTPException(status_code=400, detail="发布状态不合法")
    item = _python_or_404(db, datasource_id)
    item.state = 1
    item.status = 1 if state == 1 else 0
    item.endpoint_key = item.endpoint_key or uuid.uuid4().hex
    db.commit()
    return _python_item(item, _connector_map())


def _run_python(connector_id: int, code: str, params: Optional[dict]) -> Any:
    connector = connector_client.resolve(connector_id)
    if connector.get("status") != 1:
        raise HTTPException(status_code=400, detail="DB连接器已禁用")
    return python_executor.execute(code, connector, params)


def _preview(data: Any) -> Any:
    if isinstance(data, list):
        return data[:20]
    if isinstance(data, dict) and isinstance(data.get("items"), list):
        return {**data, "items": data["items"][:20]}
    return data


def _count(data: Any) -> Optional[int]:
    if isinstance(data, list):
        return len(data)
    if isinstance(data, dict):
        if isinstance(data.get("count"), int):
            return data["count"]
        if isinstance(data.get("items"), list):
            return len(data["items"])
    return None


def test_python_config(body: PythonDatasourceTestBody) -> dict:
    data = _run_python(body.connectorId, body.pythonCode, body.params)
    return {
        "success": True,
        "message": "Python执行成功",
        "rowCount": _count(data),
        "preview": _preview(data),
    }


def test_python(db: Session, datasource_id: int, params: dict) -> dict:
    item = _python_or_404(db, datasource_id)
    data = _run_python(item.connector_id, item.python_code, params)
    return {
        "success": True,
        "message": "Python执行成功",
        "rowCount": _count(data),
        "preview": _preview(data),
    }


def execute_python(db: Session, endpoint_key: str, params: dict) -> Any:
    item = repository.get_python_by_endpoint(db, endpoint_key)
    if not item:
        raise HTTPException(status_code=404, detail="Python数据源不存在")
    if item.status != 1:
        raise HTTPException(status_code=400, detail="Python数据源已禁用")
    return _run_python(item.connector_id, item.python_code, params)
