"""SQLAlchemy models owned by the ER-BI control process."""

from datetime import datetime
from typing import Optional

from sqlalchemy import BigInteger, DateTime, JSON, String
from sqlalchemy.dialects.mysql import LONGTEXT
from sqlalchemy.orm import Mapped, mapped_column

from app.db.session import Base


class BiProject(Base):
    __tablename__ = "bi_project"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    name: Mapped[str] = mapped_column(String(128))
    state: Mapped[int] = mapped_column(default=-1)
    content: Mapped[Optional[str]] = mapped_column(LONGTEXT, nullable=True)
    index_image: Mapped[Optional[str]] = mapped_column(LONGTEXT, nullable=True)
    remarks: Mapped[Optional[str]] = mapped_column(String(512), nullable=True)
    create_user_id: Mapped[Optional[int]] = mapped_column(BigInteger, nullable=True)
    create_time: Mapped[datetime] = mapped_column(DateTime, default=datetime.now)
    update_time: Mapped[datetime] = mapped_column(
        DateTime, default=datetime.now, onupdate=datetime.now
    )


class BiDbConnector(Base):
    """Shared DB connector configuration, owned by the main control process."""

    __tablename__ = "bi_db_connector"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    name: Mapped[str] = mapped_column(String(128))
    db_type: Mapped[str] = mapped_column(String(32))
    host: Mapped[str] = mapped_column(String(255))
    port: Mapped[int] = mapped_column(default=3306)
    database: Mapped[str] = mapped_column(String(128))
    username: Mapped[str] = mapped_column(String(128))
    password: Mapped[Optional[str]] = mapped_column(String(512), nullable=True)
    extra: Mapped[Optional[dict]] = mapped_column(JSON, nullable=True)
    status: Mapped[int] = mapped_column(default=1)
    remark: Mapped[Optional[str]] = mapped_column(String(512), nullable=True)
    create_time: Mapped[datetime] = mapped_column(DateTime, default=datetime.now)
    update_time: Mapped[datetime] = mapped_column(
        DateTime, default=datetime.now, onupdate=datetime.now
    )
