from datetime import datetime
from typing import Optional

from sqlalchemy import JSON, BigInteger, Column, DateTime, ForeignKey, String, Table
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.db.session import Base

user_role = Table(
    "sys_user_role",
    Base.metadata,
    Column(
        "user_id",
        BigInteger,
        ForeignKey("sys_user.id", ondelete="CASCADE"),
        primary_key=True,
    ),
    Column(
        "role_id",
        BigInteger,
        ForeignKey("sys_role.id", ondelete="CASCADE"),
        primary_key=True,
    ),
)

role_menu = Table(
    "sys_role_menu",
    Base.metadata,
    Column(
        "role_id",
        BigInteger,
        ForeignKey("sys_role.id", ondelete="CASCADE"),
        primary_key=True,
    ),
    Column(
        "menu_id",
        BigInteger,
        ForeignKey("sys_menu.id", ondelete="CASCADE"),
        primary_key=True,
    ),
)


class SysUser(Base):
    __tablename__ = "sys_user"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    username: Mapped[str] = mapped_column(String(64), unique=True, index=True)
    password: Mapped[str] = mapped_column(String(256))
    real_name: Mapped[str] = mapped_column(String(64), default="")
    home_path: Mapped[Optional[str]] = mapped_column(String(128), nullable=True)
    status: Mapped[int] = mapped_column(default=1)
    remark: Mapped[Optional[str]] = mapped_column(String(255), nullable=True)
    create_time: Mapped[datetime] = mapped_column(DateTime, default=datetime.now)

    roles: Mapped[list["SysRole"]] = relationship(secondary=user_role, lazy="selectin")


class SysRole(Base):
    __tablename__ = "sys_role"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    name: Mapped[str] = mapped_column(String(64), unique=True)
    status: Mapped[int] = mapped_column(default=1)
    remark: Mapped[Optional[str]] = mapped_column(String(255), nullable=True)
    create_time: Mapped[datetime] = mapped_column(DateTime, default=datetime.now)

    menus: Mapped[list["SysMenu"]] = relationship(secondary=role_menu, lazy="selectin")


class SysMenu(Base):
    __tablename__ = "sys_menu"

    id: Mapped[int] = mapped_column(BigInteger, primary_key=True, autoincrement=True)
    pid: Mapped[int] = mapped_column(BigInteger, default=0)
    name: Mapped[str] = mapped_column(String(64))
    path: Mapped[Optional[str]] = mapped_column(String(128), nullable=True)
    component: Mapped[Optional[str]] = mapped_column(String(128), nullable=True)
    redirect: Mapped[Optional[str]] = mapped_column(String(128), nullable=True)
    type: Mapped[str] = mapped_column(String(16), default="menu")
    auth_code: Mapped[Optional[str]] = mapped_column(String(64), nullable=True)
    status: Mapped[int] = mapped_column(default=1)
    meta: Mapped[Optional[dict]] = mapped_column(JSON, nullable=True)
    create_time: Mapped[datetime] = mapped_column(DateTime, default=datetime.now)
