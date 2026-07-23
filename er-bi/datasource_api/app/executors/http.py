import ipaddress
import json
import socket
import time
import urllib.error
import urllib.parse
import urllib.request
from typing import Any, Optional

from fastapi import HTTPException

from app.core.config import settings


def _render(value: Optional[str], params: Optional[dict]) -> Optional[str]:
    if value is None or not params:
        return value
    rendered = value
    for key, param_value in params.items():
        rendered = rendered.replace(
            "{{" + str(key) + "}}", "" if param_value is None else str(param_value)
        )
    return rendered


def _body(request_body: Optional[str], params: Optional[dict]) -> Optional[str]:
    rendered = _render(request_body, params)
    if not params or not rendered:
        return rendered
    try:
        parsed = json.loads(rendered)
    except Exception:
        return rendered
    if not isinstance(parsed, dict):
        return rendered
    if isinstance(parsed.get("params"), dict):
        parsed["params"].update(params)
    else:
        parsed["params"] = params
    return json.dumps(parsed, ensure_ascii=False)


def _validate_url(url: str) -> None:
    parsed = urllib.parse.urlsplit(url)
    if parsed.scheme not in {"http", "https"} or not parsed.hostname:
        raise HTTPException(status_code=400, detail="仅允许有效的 HTTP/HTTPS 地址")
    if settings.HTTP_ALLOW_PRIVATE_NETWORKS:
        return
    try:
        addresses = {
            item[4][0]
            for item in socket.getaddrinfo(parsed.hostname, parsed.port or 443)
        }
    except OSError as exc:
        raise HTTPException(status_code=400, detail=f"数据源域名解析失败：{exc}") from exc
    for address in addresses:
        ip = ipaddress.ip_address(address)
        if (
            ip.is_private
            or ip.is_loopback
            or ip.is_link_local
            or ip.is_reserved
            or ip.is_multicast
            or ip.is_unspecified
        ):
            raise HTTPException(status_code=400, detail="HTTP数据源不允许访问内网地址")


class _SafeRedirectHandler(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, req, fp, code, msg, headers, newurl):
        _validate_url(newurl)
        return super().redirect_request(req, fp, code, msg, headers, newurl)


def execute(
    *,
    request_method: str,
    request_url: str,
    request_headers: Optional[dict] = None,
    request_body: Optional[str] = None,
    params: Optional[dict] = None,
    preview: bool = False,
) -> Any:
    headers = {
        key: _render(str(value), params) or ""
        for key, value in (request_headers or {}).items()
    }
    url = _render(request_url, params) or request_url
    _validate_url(url)
    body = _body(request_body, params)
    data = None
    method = request_method.upper()
    if body and method not in {"GET", "HEAD"}:
        data = body.encode("utf-8")
        headers.setdefault("Content-Type", "application/json")

    request = urllib.request.Request(url, data=data, headers=headers, method=method)
    opener = urllib.request.build_opener(_SafeRedirectHandler())
    start = time.perf_counter()
    try:
        with opener.open(request, timeout=settings.HTTP_TIMEOUT_SECONDS) as response:
            raw = response.read(settings.HTTP_MAX_RESPONSE_BYTES + 1)
            if len(raw) > settings.HTTP_MAX_RESPONSE_BYTES:
                raise HTTPException(status_code=400, detail="HTTP数据源响应超过大小限制")
            elapsed_ms = round((time.perf_counter() - start) * 1000)
            text = raw.decode("utf-8", errors="replace")
            try:
                result = json.loads(text)
            except Exception:
                result = text
            if preview:
                return {
                    "success": True,
                    "message": "请求成功",
                    "statusCode": response.status,
                    "elapsedMs": elapsed_ms,
                    "preview": text[:4096],
                }
            return result
    except HTTPException:
        raise
    except urllib.error.HTTPError as exc:
        error_body = exc.read(2048).decode("utf-8", errors="replace")
        raise HTTPException(
            status_code=400,
            detail=f"请求失败：HTTP {exc.code} {error_body[:300]}",
        ) from exc
    except Exception as exc:
        raise HTTPException(status_code=400, detail=f"请求失败：{exc}") from exc
