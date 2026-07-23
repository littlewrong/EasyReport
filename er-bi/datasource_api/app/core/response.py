from typing import Any


def ok(data: Any = None, message: str = "ok") -> dict:
    return {"code": 0, "data": data, "error": None, "message": message}


def page_ok(items: list, total: int) -> dict:
    return ok({"items": items, "total": total})


def err(message: str, error: Any = None) -> dict:
    return {"code": -1, "data": None, "error": error or message, "message": message}
