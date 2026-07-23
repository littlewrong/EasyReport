"""Internal client for the isolated datasource service."""

import json
import urllib.error
import urllib.request
from typing import Any

from fastapi import HTTPException

from app.core.config import settings


def test_connector(connector: dict[str, Any]) -> dict:
    request = urllib.request.Request(
        f"{settings.DATASOURCE_SERVICE_URL}/internal/connectors/test",
        data=json.dumps(connector, ensure_ascii=False).encode("utf-8"),
        headers={
            "Content-Type": "application/json",
            "X-Internal-Service-Token": settings.INTERNAL_SERVICE_TOKEN,
        },
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=35) as response:
            payload = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as exc:
        try:
            payload = json.loads(exc.read().decode("utf-8"))
            detail = payload.get("detail") or payload.get("message")
        except Exception:
            detail = None
        raise HTTPException(
            status_code=400,
            detail=detail or f"数据源服务连接测试失败：HTTP {exc.code}",
        ) from exc
    except Exception as exc:
        raise HTTPException(
            status_code=503,
            detail=f"数据源执行服务不可用：{exc}",
        ) from exc

    if payload.get("code") != 0:
        raise HTTPException(
            status_code=400,
            detail=payload.get("message") or "数据库连接测试失败",
        )
    return payload.get("data") or {}
