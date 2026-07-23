"""Datasource service settings loaded from config file and environment variables."""

import os
import sys
from configparser import ConfigParser
from pathlib import Path
from urllib.parse import quote


API_DIR = Path(__file__).resolve().parents[2]


def _default_config_file() -> Path:
    """Use an editable config next to the binary in a frozen release."""
    if getattr(sys, "frozen", False):
        return Path(sys.executable).resolve().parent / "config.ini"
    return API_DIR / "config.ini"


DEFAULT_CONFIG_FILE = _default_config_file()


class Settings:
    def __init__(self) -> None:
        config_path = Path(
            os.getenv(
                "DATASOURCE_APP_CONFIG",
                os.getenv("APP_CONFIG", DEFAULT_CONFIG_FILE),
            )
        )
        self._config = ConfigParser(interpolation=None)
        self._config.read(config_path, encoding="utf-8")

        self.DB_HOST = self._get("database", "host", "DB_HOST", "localhost")
        self.DB_PORT = self._get_int("database", "port", "DB_PORT", 3306)
        self.DB_USER = self._get("database", "user", "DB_USER", "root")
        self.DB_PASSWORD = self._get("database", "password", "DB_PASSWORD", "")
        self.DB_NAME = self._get(
            "database", "name", "DB_NAME", "easyreport_bi"
        )

        self.JWT_SECRET = self._get(
            "jwt",
            "secret",
            "JWT_SECRET",
            "er-bi-jwt-secret-change-me-in-prod",
        )
        self.JWT_ALGORITHM = self._get(
            "jwt", "algorithm", "JWT_ALGORITHM", "HS256"
        )
        self.INTERNAL_SERVICE_TOKEN = self._get(
            "services",
            "internal_token",
            "INTERNAL_SERVICE_TOKEN",
            "er-bi-internal-dev-change-me",
        )
        self.MAIN_API_URL = self._get(
            "services",
            "main_api_url",
            "MAIN_API_URL",
            "http://127.0.0.1:5320/api",
        ).rstrip("/")

        self.HOST = self._get("server", "host", "HOST", "0.0.0.0")
        self.PORT = self._get_int("server", "port", "PORT", 5321)

        self.HTTP_TIMEOUT_SECONDS = self._get_float(
            "execution", "http_timeout_seconds", "HTTP_TIMEOUT_SECONDS", 10
        )
        self.HTTP_MAX_RESPONSE_BYTES = self._get_int(
            "execution",
            "http_max_response_bytes",
            "HTTP_MAX_RESPONSE_BYTES",
            10485760,
        )
        self.HTTP_ALLOW_PRIVATE_NETWORKS = self._get_bool(
            "execution",
            "http_allow_private_networks",
            "HTTP_ALLOW_PRIVATE_NETWORKS",
            True,
        )
        self.SQL_MAX_ROWS = self._get_int(
            "execution", "sql_max_rows", "SQL_MAX_ROWS", 10000
        )
        self.SQL_READ_ONLY = self._get_bool(
            "execution", "sql_read_only", "SQL_READ_ONLY", False
        )
        self.PYTHON_TIMEOUT_SECONDS = self._get_float(
            "execution", "python_timeout_seconds", "PYTHON_TIMEOUT_SECONDS", 8
        )
        self.PYTHON_MEMORY_LIMIT_MB = self._get_int(
            "execution",
            "python_memory_limit_mb",
            "PYTHON_MEMORY_LIMIT_MB",
            256,
        )

    def _get(self, section: str, key: str, env_name: str, default: str) -> str:
        env_value = os.getenv(env_name)
        if env_value is not None:
            return env_value
        if self._config.has_option(section, key):
            return self._config.get(section, key)
        return default

    def _get_int(self, section: str, key: str, env_name: str, default: int) -> int:
        return int(self._get(section, key, env_name, str(default)))

    def _get_float(
        self, section: str, key: str, env_name: str, default: float
    ) -> float:
        return float(self._get(section, key, env_name, str(default)))

    def _get_bool(
        self, section: str, key: str, env_name: str, default: bool
    ) -> bool:
        value = self._get(section, key, env_name, str(default))
        return value.strip().lower() in {"1", "true", "yes", "on"}

    @property
    def db_url(self) -> str:
        user = quote(self.DB_USER, safe="")
        password = quote(self.DB_PASSWORD, safe="")
        database = quote(self.DB_NAME, safe="")
        return (
            f"mysql+pymysql://{user}:{password}"
            f"@{self.DB_HOST}:{self.DB_PORT}/{database}?charset=utf8mb4"
        )


settings = Settings()
