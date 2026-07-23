"""Tables owned or read by the isolated datasource service."""

from datetime import datetime
from typing import Optional

from sqlalchemy import BigInteger, DateTime, JSON, String
from sqlalchemy.dialects.mysql import LONGTEXT
from sqlalchemy.orm import Mapped, mapped_column

from app.db.session import Base


class SysUser(Base):
    """Minimal read model used to reject disabled users."""

    __tablename__ = "sys_user"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True)
    username: Mapped[str] = mapped_column(String(64))
    status: Mapped[int] = mapped_column(default=1)


class BiHttpDatasource(Base):
    __tablename__ = "bi_http_datasource"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    name: Mapped[str] = mapped_column(String(128))
    connector_id: Mapped[Optional[int]] = mapped_column(BigInteger, nullable=True)
    request_method: Mapped[str] = mapped_column(String(16), default="GET")
    request_url: Mapped[str] = mapped_column(String(1024))
    request_headers: Mapped[Optional[dict]] = mapped_column(JSON, nullable=True)
    request_body: Mapped[Optional[str]] = mapped_column(LONGTEXT, nullable=True)
    status: Mapped[int] = mapped_column(default=1)
    remark: Mapped[Optional[str]] = mapped_column(String(512), nullable=True)
    create_time: Mapped[datetime] = mapped_column(DateTime, default=datetime.now)
    update_time: Mapped[datetime] = mapped_column(
        DateTime, default=datetime.now, onupdate=datetime.now
    )


class BiHttpDatasourceVersion(Base):
    __tablename__ = "bi_http_datasource_version"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    datasource_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    version_no: Mapped[int] = mapped_column(default=1)
    snapshot: Mapped[dict] = mapped_column(JSON)
    create_time: Mapped[datetime] = mapped_column(DateTime, default=datetime.now)


class BiSqlDatasource(Base):
    __tablename__ = "bi_sql_datasource"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    name: Mapped[str] = mapped_column(String(128))
    connector_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    sql_content: Mapped[str] = mapped_column(LONGTEXT)
    status: Mapped[int] = mapped_column(default=1)
    remark: Mapped[Optional[str]] = mapped_column(String(512), nullable=True)
    create_time: Mapped[datetime] = mapped_column(DateTime, default=datetime.now)
    update_time: Mapped[datetime] = mapped_column(
        DateTime, default=datetime.now, onupdate=datetime.now
    )


class BiSqlDatasourceVersion(Base):
    __tablename__ = "bi_sql_datasource_version"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    datasource_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    version_no: Mapped[int] = mapped_column(default=1)
    snapshot: Mapped[dict] = mapped_column(JSON)
    create_time: Mapped[datetime] = mapped_column(DateTime, default=datetime.now)


class BiPythonDatasource(Base):
    __tablename__ = "bi_python_datasource"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    name: Mapped[str] = mapped_column(String(128))
    connector_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    python_code: Mapped[str] = mapped_column(LONGTEXT)
    state: Mapped[int] = mapped_column(default=1)
    endpoint_key: Mapped[Optional[str]] = mapped_column(
        String(64), unique=True, nullable=True
    )
    status: Mapped[int] = mapped_column(default=1)
    remark: Mapped[Optional[str]] = mapped_column(String(512), nullable=True)
    create_time: Mapped[datetime] = mapped_column(DateTime, default=datetime.now)
    update_time: Mapped[datetime] = mapped_column(
        DateTime, default=datetime.now, onupdate=datetime.now
    )


class BiPythonDatasourceVersion(Base):
    __tablename__ = "bi_python_datasource_version"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    datasource_id: Mapped[int] = mapped_column(BigInteger, nullable=False)
    version_no: Mapped[int] = mapped_column(default=1)
    snapshot: Mapped[dict] = mapped_column(JSON)
    create_time: Mapped[datetime] = mapped_column(DateTime, default=datetime.now)
