"""Database execution isolated from the ER-BI control process."""

import re
from typing import Any

from fastapi import HTTPException

from app.core.config import settings

SQL_NAMED_PARAM_RE = re.compile(r"(?<!:):([A-Za-z_][A-Za-z0-9_]*)")
READ_PREFIX_RE = re.compile(r"^\s*(?:--[^\n]*\n\s*)*(select|with|show|explain)\b", re.I)


def _prepare(sql: str, params: Any = None) -> tuple[str, Any]:
    if settings.SQL_READ_ONLY and not READ_PREFIX_RE.match(sql):
        raise HTTPException(status_code=400, detail="当前SQL执行器仅允许只读查询")
    names = set(SQL_NAMED_PARAM_RE.findall(sql))
    if not names:
        return sql, params
    bound_params = dict(params) if isinstance(params, dict) else {}
    for name in names:
        bound_params.setdefault(name, None)
    escaped_sql = sql.replace("%", "%%")
    return SQL_NAMED_PARAM_RE.sub(r"%(\1)s", escaped_sql), bound_params


def _mysql(connector: dict, sql: str, params: Any) -> Any:
    import pymysql

    connection = pymysql.connect(
        host=connector["host"],
        port=connector["port"],
        user=connector["username"],
        password=connector.get("password") or "",
        database=connector["database"],
        charset="utf8mb4",
        connect_timeout=10,
        read_timeout=30,
        write_timeout=30,
        cursorclass=pymysql.cursors.DictCursor,
    )
    try:
        with connection.cursor() as cursor:
            cursor.execute(sql, params)
            if cursor.description:
                return cursor.fetchmany(settings.SQL_MAX_ROWS)
            if settings.SQL_READ_ONLY:
                connection.rollback()
                raise HTTPException(status_code=400, detail="只读执行器拒绝写入语句")
            connection.commit()
            return {"affectedRows": cursor.rowcount}
    finally:
        connection.close()


def _postgresql(connector: dict, sql: str, params: Any) -> Any:
    try:
        import psycopg
        from psycopg.rows import dict_row
    except ImportError as exc:
        raise HTTPException(status_code=500, detail="缺少 psycopg[binary] 依赖") from exc

    connection = psycopg.connect(
        host=connector["host"],
        port=connector["port"],
        user=connector["username"],
        password=connector.get("password") or "",
        dbname=connector["database"],
        connect_timeout=10,
        row_factory=dict_row,
    )
    try:
        with connection.cursor() as cursor:
            cursor.execute(sql, params)
            if cursor.description:
                return cursor.fetchmany(settings.SQL_MAX_ROWS)
            if settings.SQL_READ_ONLY:
                connection.rollback()
                raise HTTPException(status_code=400, detail="只读执行器拒绝写入语句")
            connection.commit()
            return {"affectedRows": cursor.rowcount}
    finally:
        connection.close()


def _sql_server(connector: dict, sql: str, params: Any) -> Any:
    try:
        import pymssql
    except ImportError as exc:
        raise HTTPException(status_code=500, detail="缺少 pymssql 依赖") from exc

    connection = pymssql.connect(
        server=connector["host"],
        port=connector["port"],
        user=connector["username"],
        password=connector.get("password") or "",
        database=connector["database"],
        charset="UTF-8",
        login_timeout=10,
        timeout=30,
    )
    cursor = connection.cursor()
    try:
        cursor.execute(sql, params)
        if cursor.description:
            names = []
            used = set()
            for index, description in enumerate(cursor.description):
                name = (description[0] if description else None) or f"column_{index + 1}"
                if name in used:
                    name = f"{name}_{index + 1}"
                used.add(name)
                names.append(name)
            return [
                dict(zip(names, row))
                for row in cursor.fetchmany(settings.SQL_MAX_ROWS)
            ]
        if settings.SQL_READ_ONLY:
            connection.rollback()
            raise HTTPException(status_code=400, detail="只读执行器拒绝写入语句")
        connection.commit()
        return {"affectedRows": cursor.rowcount}
    finally:
        cursor.close()
        connection.close()


def query(connector: dict, sql: str, params: Any = None) -> Any:
    sql, params = _prepare(sql, params)
    db_type = connector.get("dbType")
    if db_type in {"MySQL", "TiDB", "StarRocks"}:
        return _mysql(connector, sql, params)
    if db_type == "PostgreSQL":
        return _postgresql(connector, sql, params)
    if db_type == "SQLServer":
        return _sql_server(connector, sql, params)
    raise HTTPException(status_code=400, detail="不支持的数据库类型")


def test_connector(connector: dict) -> dict:
    try:
        query(connector, "SELECT 1")
    except HTTPException:
        raise
    except Exception as exc:
        raise HTTPException(status_code=400, detail=f"连接失败：{exc}") from exc
    return {"success": True, "message": "连接成功"}
