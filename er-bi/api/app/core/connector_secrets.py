"""Transparent encryption for DB connector passwords."""

import base64
import hashlib
from typing import Optional

from cryptography.fernet import Fernet, InvalidToken

from app.core.config import settings

PREFIX = "enc:v1:"


def _fernet() -> Optional[Fernet]:
    if not settings.CONNECTOR_SECRET_KEY:
        return None
    key = base64.urlsafe_b64encode(
        hashlib.sha256(settings.CONNECTOR_SECRET_KEY.encode("utf-8")).digest()
    )
    return Fernet(key)


def encrypt(value: Optional[str]) -> Optional[str]:
    if not value or value.startswith(PREFIX):
        return value
    fernet = _fernet()
    if not fernet:
        return value
    return PREFIX + fernet.encrypt(value.encode("utf-8")).decode("ascii")


def decrypt(value: Optional[str]) -> Optional[str]:
    if not value or not value.startswith(PREFIX):
        # Backward compatibility for existing plaintext rows.
        return value
    fernet = _fernet()
    if not fernet:
        raise RuntimeError("CONNECTOR_SECRET_KEY 未配置，无法解密DB连接器密码")
    try:
        return fernet.decrypt(value[len(PREFIX) :].encode("ascii")).decode("utf-8")
    except InvalidToken as exc:
        raise RuntimeError("CONNECTOR_SECRET_KEY 不正确，DB连接器密码解密失败") from exc
