"""Read DB connector execution profiles from the main ER-BI control process."""

import json
import urllib.error
import urllib.request

from fastapi import HTTPException

from app.core.config import settings


def _get(path: str):
    request = urllib.request.Request(
        f"{settings.MAIN_API_URL}{path}",
        headers={"X-Internal-Service-Token": settings.INTERNAL_SERVICE_TOKEN},
        method="GET",
    )
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        try:
            detail = json.loads(exc.read().decode("utf-8")).get("detail")
        except Exception:
            detail = None
        raise HTTPException(
            status_code=exc.code if exc.code in (400, 404) else 502,
            detail=detail or "主服务读取DB连接器失败",
        ) from exc
    except Exception as exc:
        raise HTTPException(status_code=503, detail=f"主服务不可用：{exc}") from exc

    if payload.get("code") != 0:
        raise HTTPException(
            status_code=400, detail=payload.get("message") or "DB连接器读取失败"
        )
    return payload.get("data")


def resolve(connector_id: int) -> dict:
    connector = _get(f"/internal/bi/db-connectors/{connector_id}/resolve")
    if not connector:
        raise HTTPException(status_code=404, detail="DB连接器不存在")
    return connector


def options() -> list[dict]:
    return _get("/internal/bi/db-connectors/options") or []


def ensure_exists(connector_id: int) -> dict:
    return resolve(connector_id)
