"""Application settings loaded from config file and environment variables."""

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
        config_path = Path(os.getenv("APP_CONFIG", DEFAULT_CONFIG_FILE))
        self._config = ConfigParser(interpolation=None)
        self._config.read(config_path, encoding="utf-8")

        self.DB_HOST = self._get("database", "host", "DB_HOST", "localhost")
        self.DB_PORT = self._get_int("database", "port", "DB_PORT", 3306)
        self.DB_USER = self._get("database", "user", "DB_USER", "root")
        self.DB_PASSWORD = self._get("database", "password", "DB_PASSWORD", "")
        self.DB_NAME = self._get("database", "name", "DB_NAME", "easyreport_bi")

        self.JWT_SECRET = self._get(
            "jwt",
            "secret",
            "JWT_SECRET",
            "er-bi-jwt-secret-change-me-in-prod",
        )
        self.JWT_ALGORITHM = self._get("jwt", "algorithm", "JWT_ALGORITHM", "HS256")
        self.ACCESS_TOKEN_EXPIRE_MINUTES = self._get_int(
            "jwt",
            "access_token_expire_minutes",
            "ACCESS_TOKEN_EXPIRE_MINUTES",
            120,
        )
        self.REFRESH_TOKEN_EXPIRE_DAYS = self._get_int(
            "jwt",
            "refresh_token_expire_days",
            "REFRESH_TOKEN_EXPIRE_DAYS",
            7,
        )
        self.REFRESH_COOKIE_NAME = self._get(
            "jwt", "refresh_cookie_name", "REFRESH_COOKIE_NAME", "jwt"
        )

        self.HOST = self._get("server", "host", "HOST", "0.0.0.0")
        self.PORT = self._get_int("server", "port", "PORT", 5320)

        self.DATASOURCE_SERVICE_URL = self._get(
            "services",
            "datasource_url",
            "DATASOURCE_SERVICE_URL",
            "http://127.0.0.1:5321/api",
        ).rstrip("/")
        self.INTERNAL_SERVICE_TOKEN = self._get(
            "services",
            "internal_token",
            "INTERNAL_SERVICE_TOKEN",
            "er-bi-internal-dev-change-me",
        )
        self.CONNECTOR_SECRET_KEY = self._get(
            "security", "connector_secret_key", "CONNECTOR_SECRET_KEY", ""
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

    @property
    def server_url(self) -> str:
        """Connection string without a database name, used for database creation."""
        user = quote(self.DB_USER, safe="")
        password = quote(self.DB_PASSWORD, safe="")
        return (
            f"mysql+pymysql://{user}:{password}"
            f"@{self.DB_HOST}:{self.DB_PORT}/?charset=utf8mb4"
        )

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
